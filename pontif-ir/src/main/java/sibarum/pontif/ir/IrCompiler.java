package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.DispatchTable;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IrCompiler {

    // Non-final: compile() re-wires it with the module's struct registry so
    // every consumer of simplifier() (Truffle lowering included) sees the
    // declared-type names the kernel's claim rule keys on.
    private Simplifier simplifier;

    public IrCompiler(Simplifier simplifier) {
        this.simplifier = simplifier;
    }

    public CompiledModule compile(IrModule module) throws CompileException {
        // Resolve type aliases first — strips IrStmt.TypeAlias declarations and
        // substitutes every Named reference to an alias with the aliased sort.
        // After this pass, the rest of the pipeline sees a module with only
        // function declarations and concrete sorts.
        IrModule resolved = AliasResolver.resolve(module);

        // Stamp anonymous aggregate literals with the struct name the context
        // asserts (let annotations, struct-typed params, return positions) —
        // checked construction with the redundant name elided. Runs BEFORE
        // DecimalPromotion so a stamped record's Decimal members then get
        // their literal promotion too.
        resolved = AggregatePromotion.rewrite(resolved);

        // Promote Int literals to Decimal where the declared sort says Decimal
        // (struct members, let bindings) — a lossless embedding. Runs here so
        // both single-file and linked compiles get it.
        resolved = DecimalPromotion.rewrite(resolved);

        // The claim rule at construction sites, three-way: provable fit passes
        // unchecked, provable miss is a compile error, genuine overlap gets a
        // runtime check stamped on the record (enforced by the interpreter and
        // the Truffle lowering). Runs after the promotions so it judges the
        // promoted members.
        resolved = ConstructionGate.rewrite(resolved);

        // Static sort propagation: catch field-access typos and missing-field
        // references before they reach runtime. Best-effort; the runtime still
        // validates fields it couldn't resolve here.
        SortChecker.check(resolved);

        // Overload-overlap check: pairwise per function name, reject provable
        // ambiguity at compile time. Unknown cases (kernel can't decide) pass
        // through; runtime dispatch ambiguity remains the safety net for those.
        OverloadOverlap.check(resolved);

        // Nominal struct registry: name → compiled structural Sort, for both the
        // alias name and the struct's own name (see TypeRegistry). Lets the
        // runtime/dispatch resolve a by-reference struct sort to its shape on
        // demand — and tells the kernel's claim rule which names are DECLARED
        // (a name bites iff it's registered). Built before function compilation
        // and wired into the simplifier so the Truffle path's MatchNodes carry
        // it too, not just the IrInterpreter path.
        Map<String, Sort> structRegistry = new LinkedHashMap<>();
        for (Map.Entry<String, IrSort.Structural> e : TypeRegistry.collect(resolved).entrySet()) {
            structRegistry.put(e.getKey(), compileSort(e.getValue()));
        }
        this.simplifier = this.simplifier.withRegistry(structRegistry);

        DispatchTable dispatch = new DispatchTable();
        Map<FunctionDecl, CompiledModule.CompiledFunction> functions = new LinkedHashMap<>();
        // Eager pre-compilation: every IrSort reachable from the module gets
        // compiled to a Sort here. Match-branch patterns and other runtime sort
        // lookups read from this map via CompiledModule.sortFor, so the runtime
        // path is exception-free.
        Map<IrSort, Sort> compiledSorts = new IdentityHashMap<>();
        // Declaration-ordered top-level let names — the engines' force list.
        List<String> topLevelLets = new ArrayList<>();

        for (IrStmt stmt : resolved.statements()) {
            switch (stmt) {
                case IrStmt.FunctionDecl fd -> {
                    for (IrParam p : fd.params()) registerSort(p.sort(), compiledSorts);
                    registerSort(fd.returnSort(), compiledSorts);
                    registerSortsInExpr(fd.body(), compiledSorts);
                    compileFunctionDecl(fd, dispatch, functions, compiledSorts);
                    if (fd.topLevelLet()) topLevelLets.add(fd.name());
                }
                case IrStmt.TraitImpl ti -> {
                    // Register methods as regular FunctionDecls in the
                    // dispatch table, then add the (typeName, traitName)
                    // pair to the trait registry so the slice-1 fallback
                    // rule can resolve trait-method calls at runtime.
                    for (IrStmt.FunctionDecl m : ti.methods()) {
                        for (IrParam p : m.params()) registerSort(p.sort(), compiledSorts);
                        registerSort(m.returnSort(), compiledSorts);
                        registerSortsInExpr(m.body(), compiledSorts);
                        compileFunctionDecl(m, dispatch, functions, compiledSorts);
                    }
                    dispatch.traitRegistry().register(ti.traitName(), ti.typeName());
                }
                case IrStmt.TypeAlias ta -> {
                    // AliasResolver keeps trait TypeAliases so SortChecker
                    // can find contracts; also register the trait name so
                    // bare-named param sorts can be identified as traits at
                    // dispatch time, even before any impl block is seen.
                    if (ta.sort() instanceof IrSort.Trait t) {
                        dispatch.traitRegistry().declareTrait(t.name());
                    }
                }
                case IrStmt.Proof p -> { /* proof metadata; consumed by the return-refinement gate (PontifCompiler), never compiled or evaluated */ }
                case IrStmt.ReturnProof rp -> { /* assign-proof metadata; consumed by the return-refinement gate, never compiled or evaluated */ }
                case IrStmt.Requires r -> { /* import decl; consumed by the module loader/linker + name resolver, not the per-module compile */ }
                case IrStmt.Exports e -> { /* export decl; consumed by the linker's visibility check */ }
                case IrStmt.NoOp np -> { /* parser placeholder; no compilation */ }
            }
        }

        registerSortsInExpr(resolved.main(), compiledSorts);

        return new CompiledModule(
                resolved.name(), dispatch, functions, resolved.main(), compiledSorts,
                structRegistry, topLevelLets);
    }

    private void compileFunctionDecl(
            IrStmt.FunctionDecl fd,
            DispatchTable dispatch,
            Map<FunctionDecl, CompiledModule.CompiledFunction> functions,
            Map<IrSort, Sort> compiledSorts) throws CompileException {
        List<FunctionDecl.Param> params = new ArrayList<>();
        for (IrParam p : fd.params()) {
            params.add(new FunctionDecl.Param(p.name(), compiledSorts.get(p.sort())));
        }
        Sort returnSort = compiledSorts.get(fd.returnSort());
        FunctionDecl decl = FunctionDecl.declaration(fd.name(), params, returnSort);
        dispatch.register(decl);

        functions.put(
                decl,
                new CompiledModule.CompiledFunction(decl, fd.body(), fd.params()));
    }

    /**
     * Compiles {@code sort} (and all inner sorts it contains) and stores each
     * in {@code map} keyed by its {@link IrSort} identity. Idempotent — a sort
     * already in the map is skipped, which makes it safe to call multiple times
     * with overlapping subtrees.
     */
    private static void registerSort(IrSort sort, Map<IrSort, Sort> map) throws CompileException {
        if (map.containsKey(sort)) return;
        Sort compiled = compileSort(sort);
        map.put(sort, compiled);
        switch (sort) {
            case IrSort.Named n -> { /* leaf */ }
            case IrSort.Refined r -> { /* predicate is SymExpr — not an IrSort */ }
            case IrSort.Structural s -> {
                for (IrSort inner : s.members().values()) registerSort(inner, map);
            }
            case IrSort.Method f -> {
                for (IrSort p : f.paramSorts()) registerSort(p, map);
                registerSort(f.returnSort(), map);
            }
            case IrSort.Dispatch d -> {
                for (IrSort k : d.keySorts()) registerSort(k, map);
                registerSort(d.returnSort(), map);
            }
            case IrSort.Trait t -> {
                // Method contract sorts are Function sorts; recurse into each.
                for (IrSort.Method f : t.methods().values()) registerSort(f, map);
            }
            case IrSort.Union u -> {
                for (IrSort b : u.branches()) registerSort(b, map);
            }
            case IrSort.Intersection i -> {
                for (IrSort b : i.branches()) registerSort(b, map);
            }
        }
    }

    /**
     * Walks an IrExpr tree and registers every {@link IrSort} referenced
     * (let-binding sorts, lambda param/return sorts, match-branch patterns).
     */
    private static void registerSortsInExpr(IrExpr expr, Map<IrSort, Sort> map) throws CompileException {
        switch (expr) {
            case IrExpr.Lit l -> { }
            case IrExpr.Dec d -> { }
            case IrExpr.Chr c -> { }
            case IrExpr.Bool b -> { }
            case IrExpr.Var v -> { }
            case IrExpr.SelfRef s -> { }
            case IrExpr.DispatchRef d -> {
                for (IrSort k : d.keySorts()) registerSort(k, map);
            }
            case IrExpr.BinOp op -> {
                registerSortsInExpr(op.left(), map);
                registerSortsInExpr(op.right(), map);
            }
            case IrExpr.LetIn l -> {
                registerSort(l.declaredSort(), map);
                // Binding claims kept by the gate are runtime-checked via
                // CompiledModule.sortFor — register them like record checks.
                if (l.claim() != null) registerSort(l.claim(), map);
                registerSortsInExpr(l.value(), map);
                registerSortsInExpr(l.body(), map);
            }
            case IrExpr.Call c -> {
                for (IrExpr arg : c.args()) registerSortsInExpr(arg, map);
            }
            case IrExpr.Lambda lam -> {
                for (IrParam p : lam.params()) registerSort(p.sort(), map);
                registerSort(lam.returnSort(), map);
                registerSortsInExpr(lam.body(), map);
            }
            case IrExpr.Apply app -> {
                registerSortsInExpr(app.fn(), map);
                for (IrExpr arg : app.args()) registerSortsInExpr(arg, map);
            }
            case IrExpr.Match m -> {
                registerSortsInExpr(m.scrutinee(), map);
                for (IrExpr.MatchBranch b : m.branches()) {
                    registerSort(b.pattern(), map);
                    registerSortsInExpr(b.result(), map);
                }
            }
            case IrExpr.Record r -> {
                // Stamped construction checks read their sorts at runtime via
                // CompiledModule.sortFor — register them like match patterns.
                for (IrSort s : r.runtimeChecks().values()) registerSort(s, map);
                for (IrExpr v : r.members().values()) registerSortsInExpr(v, map);
            }
            case IrExpr.FieldAccess fa -> registerSortsInExpr(fa.base(), map);
        }
    }

    public static Sort compileSort(IrSort sort) throws CompileException {
        return switch (sort) {
            case IrSort.Named n -> Sort.of(n.name());
            case IrSort.Refined r -> Sort.refined(r.name(), compileSymExpr(r.predicate()));
            case IrSort.Dispatch d -> {
                List<Sort> keys = new ArrayList<>(d.keySorts().size());
                for (IrSort k : d.keySorts()) keys.add(compileSort(k));
                yield Sort.dispatch(keys, compileSort(d.returnSort()));
            }
            case IrSort.Structural s -> {
                java.util.Map<String, Sort> members = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<String, IrSort> e : s.members().entrySet()) {
                    members.put(e.getKey(), compileSort(e.getValue()));
                }
                yield Sort.structural(s.name(), members);
            }
            case IrSort.Method f -> {
                java.util.List<Sort> params = new java.util.ArrayList<>(f.paramSorts().size());
                for (IrSort p : f.paramSorts()) {
                    params.add(compileSort(p));
                }
                yield Sort.method(params, compileSort(f.returnSort()));
            }
            case IrSort.Trait t -> {
                // At the Sort layer, a trait collapses to a bare named sort.
                // The runtime trait-fallback rule looks up the name in the
                // TraitRegistry to find satisfying concrete types — the
                // method contract itself is only needed at compile time for
                // SortChecker validation, not at runtime.
                yield Sort.of(t.name());
            }
            case IrSort.Union u -> {
                java.util.List<Sort> branches = new java.util.ArrayList<>(u.branches().size());
                for (IrSort b : u.branches()) branches.add(compileSort(b));
                yield Sort.union(branches);
            }
            case IrSort.Intersection i -> {
                java.util.List<Sort> branches = new java.util.ArrayList<>(i.branches().size());
                for (IrSort b : i.branches()) branches.add(compileSort(b));
                yield Sort.intersection(branches);
            }
        };
    }

    public static SymExpr compileSymExpr(IrExpr expr) throws CompileException {
        return switch (expr) {
            case IrExpr.Lit l -> SymExpr.lit(l.value());
            // Decimal literals appear in Decimal narrows ([Decimal:@>=1.5]);
            // SortChecker's shape validation governs where they're allowed.
            case IrExpr.Dec d -> SymExpr.dec(d.value());
            case IrExpr.Chr c -> SymExpr.chr(c.codePoint());
            case IrExpr.DispatchRef d -> {
                List<Sort> keys = new ArrayList<>(d.keySorts().size());
                for (IrSort k : d.keySorts()) keys.add(compileSort(k));
                yield new SymExpr.DispatchRef(d.functionName(), keys);
            }
            case IrExpr.Bool b -> SymExpr.bool(b.value());
            case IrExpr.Var v -> SymExpr.var(v.name());
            case IrExpr.SelfRef s -> SymExpr.self();
            case IrExpr.BinOp op -> compileBinOp(op);
            case IrExpr.LetIn l -> {
                // LetIn in a refinement predicate context: rare. Encode as
                // App(Lam(name, body), value) so substitution machinery handles it.
                yield SymExpr.app(
                        SymExpr.lam(l.name(), compileSymExpr(l.body())),
                        compileSymExpr(l.value()));
            }
            case IrExpr.Call c -> {
                // Multi-arg call lifted as a left-fold of App over Var(functionName).
                // Simplifier rules can pattern-match App-chains rooted at a named Var
                // and reduce them against the dispatch table when arguments are concrete.
                SymExpr fn = SymExpr.var(c.functionName());
                for (IrExpr arg : c.args()) {
                    fn = SymExpr.app(fn, compileSymExpr(arg));
                }
                yield fn;
            }
            case IrExpr.Lambda lambda -> throw new CompileException(
                    "Lambdas inside refinement predicates are not yet supported",
                    lambda.origin());
            case IrExpr.Apply apply -> throw new CompileException(
                    "Function applications inside refinement predicates are not yet supported",
                    apply.origin());
            case IrExpr.Match match -> throw new CompileException(
                    "Match expressions inside refinement predicates are not yet supported",
                    match.origin());
            case IrExpr.Record r -> {
                java.util.Map<String, SymExpr> members = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<String, IrExpr> e : r.members().entrySet()) {
                    members.put(e.getKey(), compileSymExpr(e.getValue()));
                }
                yield SymExpr.record(r.typeName(), members);
            }
            case IrExpr.FieldAccess fa -> SymExpr.fieldAccess(compileSymExpr(fa.base()), fa.fieldName());
        };
    }

    private static SymExpr compileBinOp(IrExpr.BinOp op) throws CompileException {
        SymExpr l = compileSymExpr(op.left());
        SymExpr r = compileSymExpr(op.right());
        return switch (op.op()) {
            case ADD -> SymExpr.add(l, r);
            case MUL -> SymExpr.mul(l, r);
            case SUB -> SymExpr.add(l, SymExpr.mul(SymExpr.lit(-1), r));
            // Division/remainder are not in the linear integer fragment the
            // refinement kernel reasons over — reject in predicate position.
            case DIV, MOD, POW -> throw new CompileException(
                    "Division/remainder/power ('/', '%', '^') is not supported inside "
                            + "refinement predicates — the discharge kernel is linear.", op.origin());
            // Approximate equality is a runtime value operator; the proof layer
            // never forgives — predicates and narrows stay exact.
            case APPROX -> throw new CompileException(
                    "'~=' is not allowed inside refinement predicates — sorts use exact "
                            + "equality; approximate equality is a runtime value operator.",
                    op.origin());
            case LT -> SymExpr.cmp(l, SymExpr.CmpOp.LT, r);
            case LE -> SymExpr.cmp(l, SymExpr.CmpOp.LE, r);
            case GT -> SymExpr.cmp(l, SymExpr.CmpOp.GT, r);
            case GE -> SymExpr.cmp(l, SymExpr.CmpOp.GE, r);
            case EQ -> SymExpr.cmp(l, SymExpr.CmpOp.EQ, r);
            case NE -> SymExpr.cmp(l, SymExpr.CmpOp.NE, r);
            case AND -> SymExpr.and(l, r);
            case OR -> SymExpr.or(l, r);
        };
    }

    public Simplifier simplifier() {
        return simplifier;
    }
}

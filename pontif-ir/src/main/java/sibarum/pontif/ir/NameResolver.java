package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.QualifiedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites a module's function/method/operator declarations and every call site
 * to fully-qualified keys ({@code module/localKey}), so that when modules are
 * merged their dispatch keys are globally collision-proof and a cross-module
 * call resolves to the right declaration.
 *
 * <p>Invoked <b>only by the module linker</b> (multi-file projects). A
 * single-file compile via {@code PontifCompiler.compile}/{@code compileAlt}
 * never runs this pass, so its dispatch keys stay bare and identical to today —
 * backward compatibility is structural, not rule-based.
 *
 * <p>Resolution of a call name {@code n} in module {@code M}:
 * <ol>
 *   <li>{@code M} declares {@code n} locally → {@code M/n};</li>
 *   <li>{@code n} is a {@code requires}-imported name from module {@code S} →
 *       {@code S/n};</li>
 *   <li>{@code n} is dotted {@code X.rest} where {@code X} is a required module →
 *       {@code X/rest};</li>
 *   <li>otherwise (primitive, local lambda binding, or genuinely unknown) →
 *       left bare for {@code SortChecker}/dispatch to handle as today.</li>
 * </ol>
 *
 * <p>Type references are FQN-rewritten too (per-module type namespacing): see
 * {@link #resolveTypeName}, which mirrors {@link #resolveCallName} over
 * {@code typeOwners} and additionally rejects an <b>ambiguous</b> bare type name
 * (declared in two or more other modules, not resolvable locally/by-import) with
 * a precise error instead of leaving it bare to fail later as "unknown sort".
 */
public final class NameResolver {

    /**
     * Type names that are never module-qualified: primitives and the parser's
     * internal sentinels. Mirrors {@code SortChecker.PRIMITIVE_SORT_NAMES}
     * (kept in sync — both must agree on "not a user type").
     */
    private static final Set<String> PRIMITIVES = Set.of(
            "Int", "Bool", "Decimal", "_", "_record", "_tuple",
            // Builtin dispatch/algebra type names — global, never module-qualified, so the
            // metareference sort stamp + runtime Metaref value + the Algebraic trait all agree
            // on one bare spelling (docs/dispatch-method-elimination.md §2). Recognized like a
            // primitive; only name RECOGNITION, not behavior (that is capability data).
            "Algebraic", "DispatchBase", "AlgebraicDispatch");

    private NameResolver() {}

    public static IrModule resolve(IrModule module, ModuleSymbolTable table) throws CompileException {
        String m = module.name();
        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt stmt : module.statements()) {
            out.add(switch (stmt) {
                case IrStmt.FunctionDecl fd -> new IrStmt.FunctionDecl(
                        ModuleSymbolTable.fqn(m, fd.name()), rewriteParams(fd.params(), m, table),
                        rewriteSort(fd.returnSort(), m, table), rewrite(fd.body(), m, table), fd.origin(),
                        fd.topLevelLet(), fd.typeParams());
                case IrStmt.TraitImpl ti -> {
                    List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods().size());
                    for (IrStmt.FunctionDecl mm : ti.methods()) {
                        methods.add(new IrStmt.FunctionDecl(
                                ModuleSymbolTable.fqn(m, mm.name()), rewriteParams(mm.params(), m, table),
                                rewriteSort(mm.returnSort(), m, table), rewrite(mm.body(), m, table), mm.origin()));
                    }
                    List<IrStmt.FunctionDecl> attrs = new ArrayList<>(ti.attributeProducers().size());
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                        attrs.add(new IrStmt.FunctionDecl(
                                ModuleSymbolTable.fqn(m, a.name()), rewriteParams(a.params(), m, table),
                                rewriteSort(a.returnSort(), m, table), rewrite(a.body(), m, table), a.origin()));
                    }
                    Map<String, IrSort> binds = new LinkedHashMap<>();
                    for (Map.Entry<String, IrSort> e : ti.typeBindings().entrySet()) {
                        binds.put(e.getKey(), rewriteSort(e.getValue(), m, table));
                    }
                    // The impl's `[type T]` bounds and the trait's applied args
                    // are sorts: resolve qualified names in them. A forwarded
                    // type variable (`T`) is not a known type name, so it stays.
                    Map<String, IrSort> implParams = new LinkedHashMap<>();
                    for (Map.Entry<String, IrSort> e : ti.typeParams().entrySet()) {
                        implParams.put(e.getKey(),
                                e.getValue() == null ? null : rewriteSort(e.getValue(), m, table));
                    }
                    List<IrSort> traitArgs = new ArrayList<>(ti.traitTypeArgs().size());
                    for (IrSort a : ti.traitTypeArgs()) {
                        traitArgs.add(rewriteSort(a, m, table));
                    }
                    yield new IrStmt.TraitImpl(
                            resolveTypeName(ti.typeName(), m, table, ti.origin()),
                            resolveTypeName(ti.traitName(), m, table, ti.origin()),
                            methods, attrs, binds, implParams, traitArgs, ti.origin());
                }
                case IrStmt.TypeAlias ta -> new IrStmt.TypeAlias(
                        resolveTypeName(ta.name(), m, table, ta.origin()),
                        rewriteSort(ta.sort(), m, table), ta.origin());
                case IrStmt.Proof p -> new IrStmt.Proof(
                        resolveCallName(p.functionName(), m, table),
                        rewrite(p.proofTree(), m, table), p.origin());
                // A coercion's source/target sorts and body are FQN-rewritten like a
                // function's, so its dispatch key (Coercions.coerceKey on the target
                // base) and the cast invocation's key agree across the module boundary.
                case IrStmt.Coercion c -> new IrStmt.Coercion(
                        rewriteSort(c.sourceSort(), m, table),
                        rewriteSort(c.targetSort(), m, table),
                        c.paramName(), rewrite(c.body(), m, table), c.origin());
                default -> stmt;  // Requires / Exports / NoOp unchanged
            });
        }
        return new IrModule(m, out, rewrite(module.main(), m, table));
    }

    private static List<IrParam> rewriteParams(List<IrParam> params, String m, ModuleSymbolTable table)
            throws CompileException {
        List<IrParam> out = new ArrayList<>(params.size());
        for (IrParam p : params) out.add(new IrParam(p.name(), rewriteSort(p.sort(), m, table)));
        return out;
    }

    /**
     * FQN-resolve a trait default-method {@link IrStmt.FunctionDecl}: its param
     * sorts, return sort, and body — exactly as an impl method is resolved (the
     * TraitImpl case above). The NAME is left untouched: a default is keyed by its
     * short member name in {@link IrSort.Trait#methodDefaults()}, and
     * {@link TraitDefaultExpansion} mints the final {@code Type.member} name from
     * that key when it clones the default into an impl.
     */
    private static IrStmt.FunctionDecl resolveTraitFn(
            IrStmt.FunctionDecl fd, String m, ModuleSymbolTable table) throws CompileException {
        return new IrStmt.FunctionDecl(
                fd.name(), rewriteParams(fd.params(), m, table),
                rewriteSort(fd.returnSort(), m, table), rewrite(fd.body(), m, table),
                fd.origin(), fd.topLevelLet(), fd.typeParams());
    }

    /**
     * Resolve a type name to its FQN (mirrors {@link #resolveCallName} over
     * {@code typeOwners}). A bare name not resolvable locally, by import, or by
     * module-qualification but declared in <b>two or more</b> other modules is an
     * <b>ambiguous-type-reference</b> error rather than a silent leave-bare that
     * later surfaces as a generic "unknown sort". The sole-owner-unimported case
     * stays leave-bare (a separate design call — see TODO).
     */
    static String resolveTypeName(String t, String m, ModuleSymbolTable table, Origin origin)
            throws CompileException {
        if (QualifiedName.parse(t).isQualified() || PRIMITIVES.contains(t)) return t;
        if (table.typeOwners(t).contains(m)) return ModuleSymbolTable.fqn(m, t);
        ModuleSymbolTable.ImportedName imported = table.importedName(m, t);
        // FQN via the DECLARING ORIGIN — a renamed type import
        // (`requires geo.{Point -> Pt}`) resolves local Pt to geo/Point, and a
        // RE-EXPORTED import chases through the exporting module to the true
        // declarer (std.proof re-exporting std.common's Leaf resolves to
        // std.common/Leaf — one nominal, however many doors it's served from).
        if (imported != null) {
            ModuleSymbolTable.ImportedName declarer = table.originOf(m, t);
            return declarer != null
                    ? ModuleSymbolTable.fqn(declarer.sourceModule(), declarer.remoteName())
                    : ModuleSymbolTable.fqn(imported.sourceModule(), imported.remoteName());
        }
        int dot = t.indexOf('.');
        if (dot > 0 && table.requiredModules(m).contains(t.substring(0, dot))) {
            return ModuleSymbolTable.fqn(t.substring(0, dot), t.substring(dot + 1));
        }
        Set<String> owners = table.typeOwners(t);
        if (owners.size() >= 2) {
            throw new CompileException(
                    "type '" + t + "' referenced in module '" + m + "' is ambiguous — "
                            + "declared in " + owners + "; qualify it (e.g. `"
                            + owners.iterator().next() + "." + t + "`) or import exactly one",
                    origin);
        }
        return t;  // primitive handled above / unknown / sole-owner-unimported — leave bare for SortChecker
    }

    /** FQN-rewrites each sort in a parametric application's type-argument list. */
    private static List<IrSort> rewriteSortArgs(
            List<IrSort> args, String m, ModuleSymbolTable table) throws CompileException {
        if (args.isEmpty()) return args;
        List<IrSort> out = new ArrayList<>(args.size());
        for (IrSort a : args) out.add(rewriteSort(a, m, table));
        return out;
    }

    /** Recursively FQN-rewrites every type name appearing in a sort. */
    private static IrSort rewriteSort(IrSort sort, String m, ModuleSymbolTable table)
            throws CompileException {
        return switch (sort) {
            case IrSort.Named n -> new IrSort.Named(
                    resolveTypeName(n.name(), m, table, n.origin()),
                    rewriteSortArgs(n.typeArgs(), m, table), n.origin());
            case IrSort.Refined r -> new IrSort.Refined(
                    resolveTypeName(r.name(), m, table, r.origin()),
                    rewriteSortArgs(r.typeArgs(), m, table), r.predicate(), r.origin());
            case IrSort.Structural s -> {
                Map<String, IrSort> members = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort> e : s.members().entrySet()) {
                    members.put(e.getKey(), rewriteSort(e.getValue(), m, table));
                }
                IrSort base = s.baseSort() == null
                        ? null : rewriteSort(s.baseSort(), m, table);
                yield new IrSort.Structural(
                        resolveTypeName(s.name(), m, table, s.origin()), members, base,
                        s.typeParams(), s.origin());
            }
            case IrSort.CallSig c -> {
                List<IrSort> ps = new ArrayList<>(c.paramSorts().size());
                for (IrSort p : c.paramSorts()) ps.add(rewriteSort(p, m, table));
                yield new IrSort.CallSig(c.typeName(), ps, c.paramNames(),
                        rewriteSort(c.returnSort(), m, table), c.origin());
            }
            case IrSort.Trait t -> {
                Map<String, IrSort.CallSig> methods = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort.CallSig> e : t.methods().entrySet()) {
                    methods.put(e.getKey(), (IrSort.CallSig) rewriteSort(e.getValue(), m, table));
                }
                Map<String, IrSort> attrs = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort> e : t.attributes().entrySet()) {
                    attrs.put(e.getKey(), rewriteSort(e.getValue(), m, table));
                }
                Map<String, IrSort> assoc = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort> e : t.associatedTypes().entrySet()) {
                    // bound may be null (unbounded `type X`); resolve a present bound.
                    assoc.put(e.getKey(),
                            e.getValue() == null ? null : rewriteSort(e.getValue(), m, table));
                }
                // Operator contract members are self-typed Dispatch sorts — no
                // type names to FQN-resolve — so carry them through verbatim.
                // Applied typeArgs (§8.6 carrier) ARE resolved — an element type may
                // be a user type needing FQN-qualification — and preserved.
                List<IrSort> resolvedArgs = new ArrayList<>(t.typeArgs().size());
                for (IrSort a : t.typeArgs()) resolvedArgs.add(rewriteSort(a, m, table));
                // A default-method / shell signature is the SAME signature as the
                // contract member (`methods` above), so it must be FQN-resolved the
                // SAME way — a default whose return names a user type (`simplify():Expr`
                // on a trait `Expr`) otherwise keeps a bare `Expr` while the contract
                // gets `mod/Expr`, and TraitDefaultExpansion then clones the bare name
                // into the impl where nothing in scope resolves it (AliasResolver's table
                // is FQN-keyed) — an unknown-sort error at a name that IS declared. The
                // bodies are resolved here too, exactly as impl-method bodies are (above),
                // so a default body naming a user type resolves as well.
                Map<String, IrStmt.FunctionDecl> defs = new LinkedHashMap<>();
                for (Map.Entry<String, IrStmt.FunctionDecl> e : t.methodDefaults().entrySet()) {
                    defs.put(e.getKey(), resolveTraitFn(e.getValue(), m, table));
                }
                Map<String, IrExpr.Lambda> retShells = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr.Lambda> e : t.returnShells().entrySet()) {
                    retShells.put(e.getKey(), (IrExpr.Lambda) rewrite(e.getValue(), m, table));
                }
                Map<String, Map<Integer, IrExpr.Lambda>> argShells = new LinkedHashMap<>();
                for (Map.Entry<String, Map<Integer, IrExpr.Lambda>> e : t.argShells().entrySet()) {
                    Map<Integer, IrExpr.Lambda> byPos = new LinkedHashMap<>();
                    for (Map.Entry<Integer, IrExpr.Lambda> pe : e.getValue().entrySet()) {
                        byPos.put(pe.getKey(), (IrExpr.Lambda) rewrite(pe.getValue(), m, table));
                    }
                    argShells.put(e.getKey(), byPos);
                }
                yield new IrSort.Trait(
                        resolveTypeName(t.name(), m, table, t.origin()), methods, attrs, assoc,
                        t.typeParams(), t.operators(), t.baseTrait(), resolvedArgs,
                        defs, retShells, argShells, t.origin());
            }
            case IrSort.Union u -> {
                List<IrSort> bs = new ArrayList<>(u.branches().size());
                for (IrSort b : u.branches()) bs.add(rewriteSort(b, m, table));
                yield new IrSort.Union(bs, u.origin());
            }
            case IrSort.Intersection i -> {
                List<IrSort> bs = new ArrayList<>(i.branches().size());
                for (IrSort b : i.branches()) bs.add(rewriteSort(b, m, table));
                yield new IrSort.Intersection(bs, i.origin());
            }
        };
    }

    /** Resolve a call name to its FQN per the rules in the class doc. */
    static String resolveCallName(String n, String m, ModuleSymbolTable table) {
        // Already an FQN iff a non-empty module prefix precedes the '/'. A name
        // that *starts* with '/' is the bare division operator (slash at index 0),
        // not an FQN — it still needs resolving to `module//`. QualifiedName
        // encodes exactly this rule.
        if (QualifiedName.parse(n).isQualified()) return n;
        if (table.moduleDeclaresFunction(m, n)) return ModuleSymbolTable.fqn(m, n);
        ModuleSymbolTable.ImportedName imported = table.importedName(m, n);
        // The FQN uses the DECLARING ORIGIN — the symbol as the module that
        // truly declares it knows it. The local name is just how this module
        // refers to it (rename), and the import's direct source may itself be
        // a RE-EXPORT (the chase finds the declarer).
        if (imported != null) {
            ModuleSymbolTable.ImportedName origin = table.originOf(m, n);
            return origin != null
                    ? ModuleSymbolTable.fqn(origin.sourceModule(), origin.remoteName())
                    : ModuleSymbolTable.fqn(imported.sourceModule(), imported.remoteName());
        }
        int dot = n.indexOf('.');
        if (dot > 0) {
            String prefix = n.substring(0, dot);
            String rest = n.substring(dot + 1);
            // Qualified function call: prefix is a required module → module/rest.
            if (table.requiredModules(m).contains(prefix)) {
                return ModuleSymbolTable.fqn(prefix, rest);
            }
            // Method / trait-method call: prefix is a TYPE (e.g. p.magnitude
            // routed to `Point.magnitude`, or `Show.render`). The method lives
            // in the type's owning module → ownerModule/Type.method.
            String typeOwner = table.soleTypeOwner(prefix);
            if (typeOwner != null) {
                return ModuleSymbolTable.fqn(typeOwner, n);
            }
        }
        return n;  // primitive / local lambda / unknown — leave bare
    }

    /**
     * If {@code fa} is {@code T.member} where the current module IMPORTS the type
     * {@code T} and {@code T.member} is a 0-arg static attribute (import-by-association,
     * docs/cross-module-dispatch.md), resolves it to a 0-arg dispatch call to that
     * member's FQN. Returns null otherwise (an ordinary field access on a value, or a
     * type the module doesn't import). Only nullary members are surfaced this way — a
     * bare {@code T.member} is a value reference; methods/operators are not.
     */
    private static IrExpr tryStaticMemberRef(
            IrExpr.FieldAccess fa, String m, ModuleSymbolTable table) {
        if (!(fa.base() instanceof IrExpr.Var v)) return null;
        ModuleSymbolTable.ImportedName imported = table.importedName(m, v.name());
        if (imported == null) return null;  // not an imported name → ordinary field access
        String memberKey = imported.remoteName() + "." + fa.fieldName();
        for (ModuleSymbolTable.Association a : table.associatedDecls(imported.remoteName())) {
            if (a.nullary() && a.localKey().equals(memberKey)) {
                return new IrExpr.Call(
                        ModuleSymbolTable.fqn(a.module(), memberKey), List.of(), fa.origin());
            }
        }
        return null;
    }

    private static IrExpr rewrite(IrExpr e, String m, ModuleSymbolTable table) throws CompileException {
        return switch (e) {
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            case IrExpr.Chr c -> c;
            case IrExpr.Str s -> s;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> {
                // A bare reference to an IMPORTED 0-arg value — a `requires $a.b`
                // data binding, or an imported top-level `let`. The parser
                // rewrites LOCAL top-level lets to 0-arg Calls (via
                // declaredTopLevelLets), but an imported one can't be seen at
                // parse time and arrives here as a bare Var. Resolve it the same
                // way the parser resolves a local one and tryStaticMemberRef
                // resolves an imported static: a 0-arg Call to the declarer's
                // FQN. Gated to imported names, so local Var handling is
                // unchanged; gated to function owners, so an imported TYPE name
                // is left for the sort machinery.
                ModuleSymbolTable.ImportedName imported = table.importedName(m, v.name());
                if (imported != null) {
                    ModuleSymbolTable.ImportedName origin = table.originOf(m, v.name());
                    if (origin != null
                            && table.moduleDeclaresFunction(origin.sourceModule(), origin.remoteName())) {
                        yield new IrExpr.Call(
                                ModuleSymbolTable.fqn(origin.sourceModule(), origin.remoteName()),
                                List.of(), v.origin());
                    }
                }
                yield v;
            }
            case IrExpr.SelfRef s -> s;
            // A metareference names a dispatch — FQN-resolve the name like a
            // call site's, and rewrite type names inside the key sorts.
            case IrExpr.DispatchRef d -> {
                List<IrSort> keys = new ArrayList<>(d.keySorts().size());
                for (IrSort k : d.keySorts()) keys.add(rewriteSort(k, m, table));
                yield new IrExpr.DispatchRef(
                        resolveCallName(d.functionName(), m, table), keys, d.origin());
            }
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(), rewrite(op.left(), m, table), rewrite(op.right(), m, table), op.origin());
            case IrExpr.LetIn l -> new IrExpr.LetIn(
                    l.name(), rewriteSort(l.declaredSort(), m, table), rewrite(l.value(), m, table),
                    rewrite(l.body(), m, table), l.origin(),
                    l.claim() == null ? null : rewriteSort(l.claim(), m, table));
            case IrExpr.Call c -> {
                List<IrExpr> args = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) args.add(rewrite(a, m, table));
                yield new IrExpr.Call(resolveCallName(c.functionName(), m, table), args, c.origin());
            }
            case IrExpr.Lambda lam -> new IrExpr.Lambda(
                    rewriteParams(lam.params(), m, table), rewriteSort(lam.returnSort(), m, table),
                    rewrite(lam.body(), m, table), lam.origin());
            case IrExpr.Apply app -> {
                List<IrExpr> args = new ArrayList<>(app.args().size());
                for (IrExpr a : app.args()) args.add(rewrite(a, m, table));
                yield new IrExpr.Apply(rewrite(app.fn(), m, table), args, app.origin());
            }
            case IrExpr.Match mt -> {
                List<IrExpr.MatchBranch> bs = new ArrayList<>(mt.branches().size());
                for (IrExpr.MatchBranch b : mt.branches()) {
                    bs.add(new IrExpr.MatchBranch(
                            rewriteSort(b.pattern(), m, table), rewrite(b.result(), m, table)));
                }
                yield new IrExpr.Match(rewrite(mt.scrutinee(), m, table), bs, mt.origin());
            }
            case IrExpr.Record r -> {
                Map<String, IrExpr> mem = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> en : r.members().entrySet()) {
                    mem.put(en.getKey(), rewrite(en.getValue(), m, table));
                }
                String typeName = r.typeName() == null ? null : resolveTypeName(r.typeName(), m, table, r.origin());
                yield new IrExpr.Record(typeName, mem, r.origin());
            }
            case IrExpr.FieldAccess fa -> {
                // Import-by-association: `T.member` where T is an IMPORTED type and
                // T.member is a 0-arg static attribute resolves to that member, as a
                // 0-arg dispatch call — importing the type surfaces its statics, the
                // same way methods come with the type. (A LOCAL static is already
                // rewritten to a Call by the parser via declaredTopLevelLets, so it
                // never reaches here as a Var-rooted field access.)
                IrExpr staticRef = tryStaticMemberRef(fa, m, table);
                yield staticRef != null ? staticRef
                        : new IrExpr.FieldAccess(rewrite(fa.base(), m, table), fa.fieldName(), fa.origin());
            }
            case IrExpr.MethodCall mc -> {
                List<IrExpr> args = new ArrayList<>(mc.args().size());
                for (IrExpr a : mc.args()) args.add(rewrite(a, m, table));
                // The receiver/args names get FQN-resolved here; the method name
                // stays bare and is resolved against the receiver type by
                // MethodResolver (which keys it Type.method itself).
                yield new IrExpr.MethodCall(
                        rewrite(mc.receiver(), m, table), mc.methodName(), args, mc.origin());
            }
            case IrExpr.Iterate it -> {
                List<IrExpr.OutputSpec> outs = new ArrayList<>(it.outputs().size());
                for (IrExpr.OutputSpec os : it.outputs()) {
                    outs.add(new IrExpr.OutputSpec(os.name(), os.kind(),
                            os.init() == null ? null : rewrite(os.init(), m, table)));
                }
                List<IrExpr.Arm> arms = new ArrayList<>(it.arms().size());
                for (IrExpr.Arm arm : it.arms()) {
                    List<IrExpr.Write> ws = new ArrayList<>(arm.writes().size());
                    for (IrExpr.Write w : arm.writes()) {
                        ws.add(new IrExpr.Write(w.output(),
                                w.key() == null ? null : rewrite(w.key(), m, table),
                                rewrite(w.value(), m, table)));
                    }
                    arms.add(new IrExpr.Arm(rewriteSort(arm.pattern(), m, table), ws));
                }
                List<IrExpr> coSources = new ArrayList<>(it.coSources().size());
                for (IrExpr cs : it.coSources()) coSources.add(rewrite(cs, m, table));
                yield new IrExpr.Iterate(
                        rewrite(it.source(), m, table), coSources, it.element(),
                        outs, arms, it.origin(), it.gpu());
            }
            case IrExpr.Emit em -> new IrExpr.Emit(
                    rewrite(em.event(), m, table), rewrite(em.body(), m, table), em.origin());
            // The cast's target sort names a type — FQN-resolve it like any sort.
            case IrExpr.Cast cast -> new IrExpr.Cast(
                    rewriteSort(cast.targetSort(), m, table),
                    rewrite(cast.value(), m, table), cast.origin());
        };
    }
}

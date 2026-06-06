package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.predicates.ComplementResult;
import sibarum.pontif.predicates.PredicateArithmetic;
import sibarum.pontif.predicates.SatResult;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time validation pass over a post-{@link AliasResolver} IR module.
 *
 * <p>Three responsibilities:
 * <ol>
 *   <li><b>Unknown sort names.</b> Every {@link IrSort.Named} and the base
 *       name of every {@link IrSort.Refined} must be a primitive or one of
 *       the internal sentinels produced by parser desugars. Alias references
 *       are already substituted away by {@link AliasResolver}, so anything
 *       still named must be a primitive — otherwise it's a typo / undeclared
 *       type and we error at compile time rather than runtime.</li>
 *   <li><b>Unknown function calls.</b> Every {@link IrExpr.Call} must refer
 *       to a declared function name. The set of declared names is collected
 *       up front from the module's {@link IrStmt.FunctionDecl}s.</li>
 *   <li><b>Field access against structural sorts.</b> For every
 *       {@link IrExpr.FieldAccess} whose base sort can be statically inferred
 *       as {@link IrSort.Structural}, the field name must exist in that
 *       sort. Sort inference looks at scope (params / let-bindings), nested
 *       field-access chains, and now {@code Call} expressions via the
 *       collected function-return map.</li>
 * </ol>
 *
 * <p>Errors are {@link CompileException}s carrying the offending node's
 * origin so the editor can point at the source location.
 */
public final class SortChecker {

    /**
     * Sort names that aren't aliases — accepted as terminal sort references.
     *
     * <p>{@code Int}, {@code Bool} are primitives. Function-typed bindings
     * use {@link IrSort.Method} (with explicit param + return shapes),
     * not a placeholder Named sort.
     *
     * <p>{@code "_"}, {@code "_record"}, and {@code "_tuple"} are internal
     * sentinels produced by parser desugars (match-destructure scrutinee
     * placeholder, anonymous by-name record inference fallback, and anonymous
     * positional aggregate / tuple, respectively). They aren't user-typable but
     * leak into the IR and must validate.
     */
    private static final Set<String> PRIMITIVE_SORT_NAMES = Set.of(
            "Int", "Bool", "Decimal", "Char",
            "_", "_record", "_tuple");

    private SortChecker() {}

    public static void check(IrModule module) throws CompileException {
        Map<String, IrSort> functionReturns = collectFunctionReturns(module);
        Map<String, IrSort.Trait> traitContracts = collectTraitContracts(module);
        Map<String, IrSort.Structural> structDefs = TypeRegistry.collect(module);

        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                Map<String, IrSort> typeEnv = new HashMap<>();
                for (IrParam p : fd.params()) {
                    validateSortNames(p.sort(), structDefs);
                    typeEnv.put(p.name(), p.sort());
                }
                validateSortNames(fd.returnSort(), structDefs);
                checkExpr(fd.body(), typeEnv, functionReturns, structDefs);
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                validateTraitImpl(ti, traitContracts, functionReturns, structDefs);
            }
        }
        checkExpr(module.main(), new HashMap<>(), functionReturns, structDefs);
    }

    /**
     * Collects declared traits from preserved {@link IrStmt.TypeAlias}
     * statements whose sort is an {@link IrSort.Trait}.
     */
    private static Map<String, IrSort.Trait> collectTraitContracts(IrModule module) {
        Map<String, IrSort.Trait> map = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.TypeAlias ta
                    && ta.sort() instanceof IrSort.Trait t) {
                map.put(t.name(), t);
            }
        }
        return map;
    }

    /**
     * Verifies a {@link IrStmt.TraitImpl} block against the trait's contract:
     * every contract method must have a matching implementation declared in
     * the block (matched by short name — stripping the {@code Type.}
     * prefix), and the impl's arity must equal {@code 1 + contract param
     * count} (self prepended). Also validates the methods' own sorts
     * recursively and checks their bodies via {@link #checkExpr}.
     */
    private static void validateTraitImpl(
            IrStmt.TraitImpl ti,
            Map<String, IrSort.Trait> traitContracts,
            Map<String, IrSort> functionReturns,
            Map<String, IrSort.Structural> structDefs) throws CompileException {
        IrSort.Trait contract = traitContracts.get(ti.traitName());
        if (contract == null) {
            throw new CompileException(
                    "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                            + "' references unknown trait '" + ti.traitName()
                            + "' (no trait declaration in module)",
                    ti.origin());
        }

        // Build short-name -> impl map (stripping the "Type." prefix).
        Map<String, IrStmt.FunctionDecl> implByShortName = new LinkedHashMap<>();
        String prefix = ti.typeName() + ".";
        for (IrStmt.FunctionDecl m : ti.methods()) {
            String shortName = m.name().startsWith(prefix)
                    ? m.name().substring(prefix.length())
                    : m.name();
            implByShortName.put(shortName, m);
        }

        // Verify every contract method has a matching impl with self-prepended arity.
        for (Map.Entry<String, IrSort.Method> e : contract.methods().entrySet()) {
            String methodName = e.getKey();
            IrSort.Method contractSig = e.getValue();
            IrStmt.FunctionDecl impl = implByShortName.get(methodName);
            if (impl == null) {
                throw new CompileException(
                        "Trait impl '" + ti.typeName() + " : " + ti.traitName()
                                + "' is missing method '" + methodName + "'",
                        ti.origin());
            }
            int expectedArity = 1 + contractSig.paramSorts().size();
            if (impl.params().size() != expectedArity) {
                throw new CompileException(
                        "Method '" + impl.name() + "' has " + impl.params().size()
                                + " param(s); trait '" + ti.traitName()
                                + "' contract requires " + expectedArity
                                + " (self + " + contractSig.paramSorts().size()
                                + " from contract)",
                        impl.origin());
            }
        }

        // Validate the impl method bodies themselves.
        for (IrStmt.FunctionDecl m : ti.methods()) {
            Map<String, IrSort> typeEnv = new HashMap<>();
            for (IrParam p : m.params()) {
                validateSortNames(p.sort(), structDefs);
                typeEnv.put(p.name(), p.sort());
            }
            validateSortNames(m.returnSort(), structDefs);
            checkExpr(m.body(), typeEnv, functionReturns, structDefs);
        }
    }

    /**
     * Builds {@code functionName → returnSort} from the module's function
     * declarations. With overloads, the last-encountered overload's return
     * sort wins — best-effort, since cross-overload return narrowing waits
     * on the in-progress dispatch-inference work.
     */
    private static Map<String, IrSort> collectFunctionReturns(IrModule module) {
        Map<String, IrSort> map = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                map.put(fd.name(), fd.returnSort());
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                // TraitImpl methods are first-class FunctionDecls in the
                // dispatch table — include their return sorts here so call
                // sites validate against them.
                for (IrStmt.FunctionDecl m : ti.methods()) {
                    map.put(m.name(), m.returnSort());
                }
            } else if (stmt instanceof IrStmt.TypeAlias ta
                    && ta.sort() instanceof IrSort.Trait t) {
                // Trait.method call names are valid even with no impl yet —
                // the dispatch fallback resolves them to ConcreteType.method
                // at runtime against the trait registry. Return sort is the
                // contract's declared return.
                for (Map.Entry<String, IrSort.Method> e : t.methods().entrySet()) {
                    map.put(t.name() + "." + e.getKey(), e.getValue().returnSort());
                }
            }
        }
        return map;
    }

    /**
     * Recursively validates sort references. Compound sorts recurse into
     * their members / params. The two relevant cases:
     * <ul>
     *   <li>{@link IrSort.Named}: must be a primitive — alias references
     *       have been substituted away by {@link AliasResolver}.</li>
     *   <li>{@link IrSort.Refined}: base must be a primitive <em>or</em> a
     *       declared struct name (so {@code [Point:@.x > 0]} is legal).
     *       When the base is a struct, the predicate's {@code @.field}
     *       references are validated against the struct's declared members.</li>
     * </ul>
     *
     * <p>{@link IrSort.Structural#name()} is intentionally NOT validated —
     * it's the sort's own identity, not a reference to another type. A
     * structural sort can be inline-defined without a preceding {@code
     * struct} declaration.
     */
    /**
     * Validates that a {@code [Decimal:...]} predicate is one of Decimal's
     * three narrows — sign, range, or equality-up-to-precision. Concretely: an
     * And-tree whose leaves are comparisons of {@code @} against a numeric
     * constant. Sign is the zero-bound case, range is the conjunction case,
     * equality is the {@code ==} case; thresholds ({@code @>=1.5}) are
     * degenerate ranges. Anything richer (arithmetic on {@code @},
     * disjunctions, non-constant bounds) is rejected — not a type-narrowing
     * problem, per the Decimal design.
     */
    /** {@code @.unscaled} / {@code @.scale} — a projection of Decimal's anatomy. */
    private static boolean isAnatomyProjection(IrExpr e) {
        return e instanceof IrExpr.FieldAccess fa
                && fa.base() instanceof IrExpr.SelfRef
                && sibarum.pontif.core.Decimals.isAnatomyField(fa.fieldName());
    }

    private static void validateDecimalNarrow(IrExpr predicate, sibarum.pontif.core.Origin origin)
            throws CompileException {
        if (predicate instanceof IrExpr.BinOp op) {
            switch (op.op()) {
                case AND -> {
                    validateDecimalNarrow(op.left(), origin);
                    validateDecimalNarrow(op.right(), origin);
                    return;
                }
                case LT, LE, GT, GE, EQ, NE -> {
                    boolean selfVsConst =
                            (op.left() instanceof IrExpr.SelfRef && isNumericConst(op.right()))
                                    || (op.right() instanceof IrExpr.SelfRef && isNumericConst(op.left()));
                    if (selfVsConst) {
                        return;
                    }
                    // The anatomy narrows (slice 2 of the native-constructor
                    // registry): comparisons of @.unscaled / @.scale against a
                    // numeric constant. These are DISCRETE obligations (scale
                    // is an Int; unscaled is integer-valued), so they sit on
                    // the integer side of the ruled discreteness boundary —
                    // they don't widen the three Decimal-domain narrows.
                    boolean anatomyVsConst =
                            (isAnatomyProjection(op.left()) && isNumericConst(op.right()))
                                    || (isAnatomyProjection(op.right()) && isNumericConst(op.left()));
                    if (anatomyVsConst) {
                        return;
                    }
                }
                default -> { /* falls through to rejection */ }
            }
        }
        throw new CompileException(
                "Not a Decimal narrow. Decimal refinements are limited to sign / range / "
                        + "equality — comparisons of '@' against a numeric constant, optionally "
                        + "joined with '&': e.g. [Decimal:@>0], [Decimal:@>=0 & @<=1], "
                        + "[Decimal:@==2.5].",
                origin);
    }

    private static boolean isNumericConst(IrExpr e) {
        return e instanceof IrExpr.Lit || e instanceof IrExpr.Dec;
    }

    private static void validateSortNames(IrSort sort, Map<String, IrSort.Structural> structDefs)
            throws CompileException {
        switch (sort) {
            case IrSort.Named n -> {
                // A surviving Named is either a primitive or a nominal struct
                // reference (structs are no longer inlined — see AliasResolver).
                // Resolving by name against structDefs, rather than recursing
                // into the struct body, is what keeps this terminating on a
                // recursive type: struct Node(next:Node) validates without
                // unrolling Node.
                if (!PRIMITIVE_SORT_NAMES.contains(n.name())
                        && !structDefs.containsKey(n.name())) {
                    throw new CompileException(
                            "Unknown sort '" + n.name() + "' — not a primitive "
                                    + "and not a declared type (did you forget a "
                                    + "'struct " + n.name() + "(...)' declaration?)",
                            n.origin());
                }
            }
            case IrSort.Refined r -> {
                if (r.name().equals("Decimal")) {
                    // Decimal's refinement vocabulary is exactly three narrows —
                    // sign, range, and equality-up-to-precision — i.e. And-trees
                    // of comparisons of @ against a numeric constant. Anything
                    // richer isn't a type-narrowing problem (and the dense
                    // discharge path covers exactly these shapes).
                    validateDecimalNarrow(r.predicate(), r.origin());
                    break;
                }
                if (PRIMITIVE_SORT_NAMES.contains(r.name())) {
                    // Primitive-base refinement; predicate not yet structurally
                    // validated — refinement-predicate sort-checking is its
                    // own future work (TODO: "Sort checking inside refinement
                    // predicates").
                    break;
                }
                IrSort.Structural baseStruct = structDefs.get(r.name());
                if (baseStruct == null) {
                    throw new CompileException(
                            "Unknown base sort '" + r.name() + "' in refinement "
                                    + "[" + r.name() + ":...] — refinements must be "
                                    + "over a primitive (Int, Bool) or a declared struct.",
                            r.origin());
                }
                validateSelfFieldAccesses(r.predicate(), baseStruct, r.origin());
            }
            case IrSort.Structural s -> {
                for (IrSort member : s.members().values()) {
                    validateSortNames(member, structDefs);
                }
            }
            case IrSort.Method f -> {
                for (IrSort p : f.paramSorts()) validateSortNames(p, structDefs);
                validateSortNames(f.returnSort(), structDefs);
            }
            case IrSort.Dispatch d -> {
                for (IrSort k : d.keySorts()) validateSortNames(k, structDefs);
                validateSortNames(d.returnSort(), structDefs);
            }
            case IrSort.Trait t -> {
                // Trait's name identifies the trait itself — not a reference
                // to another sort. Method-contract sorts are Function sorts;
                // recurse into them to validate param/return types.
                for (IrSort.Method f : t.methods().values()) validateSortNames(f, structDefs);
            }
            case IrSort.Union u -> {
                for (IrSort b : u.branches()) validateSortNames(b, structDefs);
            }
            case IrSort.Intersection i -> {
                for (IrSort b : i.branches()) validateSortNames(b, structDefs);
            }
        }
    }

    /**
     * Walks a struct-refinement's predicate looking for {@code @.field}
     * accesses (i.e., {@link IrExpr.FieldAccess} whose base is
     * {@link IrExpr.SelfRef}) and verifies each {@code field} exists in
     * the struct. Nested field-access chains are not yet validated past
     * the first level — covered when sort inference can statically
     * project nested field sorts.
     */
    private static void validateSelfFieldAccesses(
            IrExpr predicate,
            IrSort.Structural baseStruct,
            sibarum.pontif.core.Origin refOrigin) throws CompileException {
        switch (predicate) {
            case IrExpr.FieldAccess fa -> {
                if (fa.base() instanceof IrExpr.SelfRef) {
                    if (!baseStruct.members().containsKey(fa.fieldName())) {
                        throw new CompileException(
                                "Refinement [" + baseStruct.name() + ":…] references "
                                        + "@." + fa.fieldName() + " but struct '"
                                        + baseStruct.name() + "' has no such field; "
                                        + "available: " + baseStruct.members().keySet(),
                                fa.origin() != null ? fa.origin() : refOrigin);
                    }
                }
                validateSelfFieldAccesses(fa.base(), baseStruct, refOrigin);
            }
            case IrExpr.BinOp op -> {
                validateSelfFieldAccesses(op.left(), baseStruct, refOrigin);
                validateSelfFieldAccesses(op.right(), baseStruct, refOrigin);
            }
            case IrExpr.LetIn l -> {
                validateSelfFieldAccesses(l.value(), baseStruct, refOrigin);
                validateSelfFieldAccesses(l.body(), baseStruct, refOrigin);
            }
            case IrExpr.Call c -> {
                for (IrExpr a : c.args()) validateSelfFieldAccesses(a, baseStruct, refOrigin);
            }
            case IrExpr.Apply a -> {
                validateSelfFieldAccesses(a.fn(), baseStruct, refOrigin);
                for (IrExpr arg : a.args()) validateSelfFieldAccesses(arg, baseStruct, refOrigin);
            }
            case IrExpr.Lambda lam -> validateSelfFieldAccesses(lam.body(), baseStruct, refOrigin);
            case IrExpr.Match m -> {
                validateSelfFieldAccesses(m.scrutinee(), baseStruct, refOrigin);
                for (IrExpr.MatchBranch b : m.branches()) {
                    validateSelfFieldAccesses(b.result(), baseStruct, refOrigin);
                }
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) {
                    validateSelfFieldAccesses(v, baseStruct, refOrigin);
                }
            }
            case IrExpr.Lit ignored -> {}
            case IrExpr.Dec ignored -> {}
            case IrExpr.Chr ignored -> {}
            case IrExpr.Bool ignored -> {}
            case IrExpr.Var ignored -> {}
            case IrExpr.SelfRef ignored -> {}
            case IrExpr.DispatchRef ignored -> {}
        }
    }

    private static void checkExpr(IrExpr expr, Map<String, IrSort> typeEnv,
                                  Map<String, IrSort> functionReturns,
                                  Map<String, IrSort.Structural> structDefs)
            throws CompileException {
        switch (expr) {
            case IrExpr.Lit l -> {}
            case IrExpr.Dec d -> {}
            case IrExpr.Chr c -> {}
            case IrExpr.Bool b -> {}
            case IrExpr.SelfRef s -> {}
            case IrExpr.Var v -> {}
            // A metareference must name a declared function — zero candidates
            // is a compile error, not a runtime surprise. Key sorts validate
            // like any other sort reference.
            case IrExpr.DispatchRef d -> {
                for (IrSort k : d.keySorts()) validateSortNames(k, structDefs);
                if (!functionReturns.containsKey(d.functionName())) {
                    throw new CompileException(
                            "Metareference '" + d.functionName()
                                    + "[...]' names no declared function",
                            d.origin());
                }
            }
            case IrExpr.BinOp op -> {
                checkExpr(op.left(), typeEnv, functionReturns, structDefs);
                checkExpr(op.right(), typeEnv, functionReturns, structDefs);
            }
            case IrExpr.LetIn l -> {
                validateSortNames(l.declaredSort(), structDefs);
                checkExpr(l.value(), typeEnv, functionReturns, structDefs);
                Map<String, IrSort> extended = new HashMap<>(typeEnv);
                IrSort bound = l.declaredSort();
                // An undeclared binder ("_") takes the value's inferred sort,
                // so a match on the binding has a real domain to prove
                // totality over (the parser's synthetic scrutinee lets and
                // bare `let x = …` both land here).
                if (bound instanceof IrSort.Named n && n.name().equals("_")) {
                    IrSort inferred = inferSort(l.value(), typeEnv, functionReturns, structDefs);
                    if (inferred != null) bound = inferred;
                }
                extended.put(l.name(), bound);
                checkExpr(l.body(), extended, functionReturns, structDefs);
            }
            case IrExpr.Call c -> {
                // The name might be a top-level function/method, OR a locally
                // bound callable (let-bound lambda, function param of Function
                // sort). Either is legal. Only reject when neither is true.
                if (!functionReturns.containsKey(c.functionName())
                        && !typeEnv.containsKey(c.functionName())) {
                    throw new CompileException(
                            "Unknown function '" + c.functionName() + "' — no "
                                    + "matching declaration (overload-mismatch errors "
                                    + "happen at dispatch time; this means no overload "
                                    + "of '" + c.functionName() + "' exists at all).",
                            c.origin());
                }
                for (IrExpr arg : c.args()) checkExpr(arg, typeEnv, functionReturns, structDefs);
            }
            case IrExpr.Lambda lam -> {
                for (IrParam p : lam.params()) validateSortNames(p.sort(), structDefs);
                validateSortNames(lam.returnSort(), structDefs);
                Map<String, IrSort> extended = new HashMap<>(typeEnv);
                for (IrParam p : lam.params()) extended.put(p.name(), p.sort());
                checkExpr(lam.body(), extended, functionReturns, structDefs);
            }
            case IrExpr.Apply app -> {
                // Applying something that can statically NEVER be a function —
                // a literal, a struct literal, an arithmetic/comparison result —
                // is always a bug; reject at compile time rather than letting
                // it sit inert until (never) invoked.
                IrExpr fn = app.fn();
                if (fn instanceof IrExpr.Lit || fn instanceof IrExpr.Dec
                        || fn instanceof IrExpr.Bool || fn instanceof IrExpr.Record
                        || fn instanceof IrExpr.BinOp) {
                    throw new CompileException(
                            "This expression is not callable (it is a "
                                    + fn.getClass().getSimpleName()
                                    + ", which can never be a function) — applied with "
                                    + app.args().size() + " argument(s).",
                            app.origin());
                }
                checkExpr(app.fn(), typeEnv, functionReturns, structDefs);
                for (IrExpr a : app.args()) checkExpr(a, typeEnv, functionReturns, structDefs);
            }
            case IrExpr.Match m -> {
                checkExpr(m.scrutinee(), typeEnv, functionReturns, structDefs);
                for (IrExpr.MatchBranch b : m.branches()) {
                    validateSortNames(b.pattern(), structDefs);
                    Map<String, IrSort> branchEnv = new HashMap<>(typeEnv);
                    if (m.scrutinee() instanceof IrExpr.Var v
                            && b.pattern() instanceof IrSort.Structural) {
                        branchEnv.put(v.name(), b.pattern());
                    }
                    checkExpr(b.result(), branchEnv, functionReturns, structDefs);
                }
                checkMatchTotality(m, typeEnv, functionReturns, structDefs);
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) checkExpr(v, typeEnv, functionReturns, structDefs);
            }
            case IrExpr.FieldAccess fa -> {
                checkExpr(fa.base(), typeEnv, functionReturns, structDefs);
                IrSort baseSort = inferSort(fa.base(), typeEnv, functionReturns, structDefs);
                IrSort.Structural sp = resolveNominal(baseSort, structDefs);
                if (sp != null) {
                    if (!sp.members().containsKey(fa.fieldName())) {
                        throw new CompileException(
                                "Record of sort '" + sp.name() + "' has no field '"
                                        + fa.fieldName() + "'; available fields: "
                                        + sp.members().keySet(),
                                fa.origin());
                    }
                } else if (baseSort != null) {
                    // Native anatomies get the same typo coverage as structs.
                    String base = matchBaseName(baseSort);
                    if (base != null && NativeConstructors.has(base)
                            && !NativeConstructors.get(base).shape().members()
                                    .containsKey(fa.fieldName())) {
                        throw new CompileException(
                                "'" + base + "' has no field '" + fa.fieldName()
                                        + "' — its anatomy is "
                                        + NativeConstructors.get(base).shape().members().keySet(),
                                fa.origin());
                    }
                }
            }
        }
    }

    /**
     * Match totality (alt-syntax principle 8): every value of the scrutinee's
     * sort must be covered by some arm. <b>The conservation rule: if totality
     * cannot be determined at compile time, a default arm is required.</b>
     * No value may fall through a match unhandled, and "we'll find out at
     * runtime" is not a proof.
     *
     * <p>A match passes iff one of:
     * <ul>
     *   <li>it has a <b>catch-all arm</b> — {@code _} (which the parser
     *       desugars to the precise complement where computable, the universal
     *       {@code [_]} pattern otherwise), an explicit {@code [_]}, or a bare
     *       arm of the scrutinee's own base sort — total by construction;</li>
     *   <li>a proof tier <b>determines</b> totality: Tier A (a bare structural
     *       destructure arm covers the whole struct), Tier C (a union scrutinee
     *       with every branch covered by a bare arm of that branch's type),
     *       Tier B (single-varying-field struct coverage), or the refined-arm
     *       complement check over the decidable {@link PredicateArithmetic}
     *       fragment.</li>
     * </ul>
     * Anything else — unknown scrutinee sort, arms outside the decidable
     * fragment (Decimal predicates, multi-field struct refinements, …), or a
     * kernel {@code Unknown} — is a compile error directing the user to add a
     * {@code _} default.
     */
    private static void checkMatchTotality(
            IrExpr.Match m,
            Map<String, IrSort> typeEnv,
            Map<String, IrSort> functionReturns,
            Map<String, IrSort.Structural> structDefs) throws CompileException {
        IrSort scrutineeIr = inferSort(m.scrutinee(), typeEnv, functionReturns, structDefs);

        // A catch-all arm makes the match total by construction, regardless of
        // what the other arms look like (ordered match: it catches the rest).
        if (hasCatchAllArm(m, scrutineeIr)) {
            return;
        }
        if (scrutineeIr == null) {
            throw cannotProveTotality(m, null, "the scrutinee's sort is not statically known");
        }

        // Struct totality (Tier A): a bare structural arm (no refined fields)
        // whose field set is a subset of the scrutinee's fields matches every
        // value of that struct shape — per Pontif's subset-semantics structural
        // matching — so the match is trivially total.
        IrSort.Structural scrutineeStruct = resolveNominal(scrutineeIr, structDefs);
        if (scrutineeStruct != null) {
            for (IrExpr.MatchBranch b : m.branches()) {
                if (isBareStructuralCovering(b.pattern(), scrutineeStruct, structDefs)) {
                    return;  // trivially total
                }
            }
        }

        // Tier C: union scrutinee where every branch is covered by a bare arm
        // of that branch's type ([Int] / [Ternion(z,n,w)] over [Int|Ternion]) —
        // the canonical sum-type match, determined total structurally.
        if (unionCoveredByBareArms(m, scrutineeIr, structDefs)) {
            return;
        }

        // Tier B: single-varying-field struct totality. If every arm is a
        // structural pattern refining the SAME single field (with all others
        // bare), totality reduces to "does the union of arms' refinements on
        // that field cover the field's domain?" — a single-field problem the
        // existing kernel decides.
        if (tryTierBSingleField(m, scrutineeIr, structDefs)) {
            return;  // total (or threw on non-exhaustive)
        }

        SymExpr union = null;
        for (IrExpr.MatchBranch b : m.branches()) {
            if (!(b.pattern() instanceof IrSort.Refined refined)) {
                throw cannotProveTotality(m, scrutineeIr,
                        "arm patterns are outside the decidable fragment");
            }
            SymExpr armPred;
            try {
                armPred = IrCompiler.compileSymExpr(refined.predicate());
            } catch (CompileException e) {
                throw cannotProveTotality(m, scrutineeIr,
                        "an arm predicate is outside the decidable fragment");
            }
            union = (union == null) ? armPred : SymExpr.or(union, armPred);
        }
        if (union == null) return;  // no arms (the parser rejects empty matches anyway)

        Sort domain;
        try {
            domain = IrCompiler.compileSort(scrutineeIr);
        } catch (CompileException e) {
            throw cannotProveTotality(m, scrutineeIr,
                    "the scrutinee's sort is outside the decidable fragment");
        }

        // Uncovered = domain ∧ ¬(union of arms).
        ComplementResult cr = PredicateArithmetic.complement(union, domain);
        if (!(cr instanceof ComplementResult.Computed computed)) {
            throw cannotProveTotality(m, scrutineeIr,
                    "coverage over this domain is undecidable");
        }
        SymExpr uncovered = computed.predicate();
        SatResult sat = PredicateArithmetic.satisfiable(uncovered, domain);
        if (sat.isYes()) {
            throw new CompileException(
                    "match over " + describeDomain(scrutineeIr) + " is not exhaustive — no arm covers "
                            + renderPredicate(uncovered)
                            + " (every match must be total; add the missing arm or a '_' default)",
                    m.origin());
        }
        if (!(sat instanceof SatResult.No)) {
            throw cannotProveTotality(m, scrutineeIr,
                    "coverage over this domain is undecidable");
        }
    }

    /** The conservation rule's rejection: undeterminable totality, no default. */
    private static CompileException cannotProveTotality(
            IrExpr.Match m, IrSort scrutineeIr, String reason) {
        String domain = scrutineeIr == null ? "" : " over " + describeDomain(scrutineeIr);
        return new CompileException(
                "cannot prove this match" + domain + " is exhaustive — " + reason
                        + ". Every match must be total: add a '_' default arm "
                        + "(or an explicit catch-all like [_]).",
                m.origin());
    }

    /**
     * A catch-all arm: the universal {@code [_]} pattern (also what {@code _}
     * desugars to outside the complement-computable fragment), or a bare arm
     * of the scrutinee's own base sort (e.g. {@code [Ternion]} over a
     * {@code Ternion} scrutinee).
     */
    private static boolean hasCatchAllArm(IrExpr.Match m, IrSort scrutineeIr) {
        String scrutineeBase = scrutineeIr == null ? null : matchBaseName(scrutineeIr);
        for (IrExpr.MatchBranch b : m.branches()) {
            if (b.pattern() instanceof IrSort.Named n) {
                if (n.name().equals("_")) return true;
                if (scrutineeBase != null && n.name().equals(scrutineeBase)) return true;
            }
        }
        return false;
    }

    /**
     * Tier C: a union scrutinee is determined total when every union branch is
     * covered by a bare arm of that branch's type — a bare {@code [Type]} arm
     * or a bare structural destructure {@code [Type(a, b)]}. Refined union
     * branches or arms fall back to the other tiers.
     */
    private static boolean unionCoveredByBareArms(
            IrExpr.Match m, IrSort scrutineeIr, Map<String, IrSort.Structural> structDefs) {
        if (!(scrutineeIr instanceof IrSort.Union u)) return false;
        for (IrSort branch : u.branches()) {
            if (branch instanceof IrSort.Refined) return false;
            String base = matchBaseName(branch);
            if (base == null) return false;
            IrSort.Structural branchStruct = resolveNominal(branch, structDefs);
            boolean covered = false;
            for (IrExpr.MatchBranch b : m.branches()) {
                IrSort p = b.pattern();
                if (p instanceof IrSort.Named n && n.name().equals(base)) {
                    covered = true;
                    break;
                }
                if (branchStruct != null && p instanceof IrSort.Structural st
                        && base.equals(st.name())
                        && isBareStructuralCovering(p, branchStruct, structDefs)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) return false;
        }
        return true;
    }

    private static String matchBaseName(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            default -> null;
        };
    }

    /**
     * Tier B struct totality: when every arm is a structural pattern refining
     * the same single field (others bare), reduce to that field's domain-
     * coverage problem (which the kernel decides). Returns:
     * <ul>
     *   <li>{@code true} if the case applies and the union of arms' field
     *       refinements provably covers the field's domain — total;</li>
     *   <li>throws {@link CompileException} when the case applies but the
     *       union provably leaves the field's domain uncovered;</li>
     *   <li>{@code false} when this isn't the single-varying-field shape, or
     *       the kernel can't decide — defer to the outer check / runtime.</li>
     * </ul>
     */
    private static boolean tryTierBSingleField(
            IrExpr.Match m, IrSort scrutineeIr,
            Map<String, IrSort.Structural> structDefs) throws CompileException {
        IrSort.Structural scrutineeStruct = resolveNominal(scrutineeIr, structDefs);
        if (scrutineeStruct == null) return false;

        String varyingField = null;
        SymExpr union = null;
        for (IrExpr.MatchBranch b : m.branches()) {
            if (!(b.pattern() instanceof IrSort.Structural sp)) return false;
            // Find the arm's single refined field; reject 2+ refined fields or
            // nested-structural fields.
            String armVarying = null;
            IrSort.Refined armRefined = null;
            for (Map.Entry<String, IrSort> entry : sp.members().entrySet()) {
                IrSort fs = entry.getValue();
                if (fs instanceof IrSort.Refined r) {
                    if (armVarying != null) return false;  // 2+ refined fields
                    armVarying = entry.getKey();
                    armRefined = r;
                } else if (fs instanceof IrSort.Structural) {
                    return false;  // nested structural → defer
                }
            }
            if (armVarying == null) return false;  // bare arm — Tier A's territory
            if (varyingField == null) {
                varyingField = armVarying;
            } else if (!varyingField.equals(armVarying)) {
                return false;  // different varying field across arms
            }
            SymExpr armPred;
            try {
                armPred = IrCompiler.compileSymExpr(armRefined.predicate());
            } catch (CompileException e) {
                return false;
            }
            union = (union == null) ? armPred : SymExpr.or(union, armPred);
        }
        if (varyingField == null || union == null) return false;

        // The field's domain — the declared field sort on the scrutinee struct.
        IrSort fieldDeclaredIr = scrutineeStruct.members().get(varyingField);
        if (fieldDeclaredIr == null) return false;  // arm refines a field the struct lacks
        Sort fieldDomain;
        try {
            fieldDomain = IrCompiler.compileSort(fieldDeclaredIr);
        } catch (CompileException e) {
            return false;
        }

        ComplementResult cr = PredicateArithmetic.complement(union, fieldDomain);
        if (!(cr instanceof ComplementResult.Computed computed)) return false;
        SymExpr uncovered = computed.predicate();
        if (PredicateArithmetic.satisfiable(uncovered, fieldDomain).isYes()) {
            throw new CompileException(
                    "match over " + describeDomain(scrutineeIr)
                            + " is not exhaustive — no arm covers field '"
                            + varyingField + "' where " + renderPredicate(uncovered)
                            + " (every match must be total)",
                    m.origin());
        }
        return true;  // total
    }

    /**
     * Resolves a sort to its declared struct definition — directly when it's
     * {@link IrSort.Structural}, by name via {@code structDefs} for a nominal
     * {@link IrSort.Named}/{@link IrSort.Refined} reference. {@code null} when
     * the sort isn't a struct. Resolving by a single name lookup (never
     * recursing into the struct body) is what keeps every consumer terminating
     * on a recursive type.
     */
    private static IrSort.Structural resolveNominal(
            IrSort sort, Map<String, IrSort.Structural> structDefs) {
        if (sort == null) return null;  // unknown inferred sort — not a struct
        return switch (sort) {
            case IrSort.Structural s -> s;
            case IrSort.Named n -> structDefs.get(n.name());
            case IrSort.Refined r -> structDefs.get(r.name());
            default -> null;
        };
    }

    /** Field set of a struct scrutinee, or {@code null} for non-structs. */
    private static Set<String> scrutineeFieldSet(
            IrSort scrutineeIr, Map<String, IrSort.Structural> structDefs) {
        IrSort.Structural def = resolveNominal(scrutineeIr, structDefs);
        return def != null ? def.members().keySet() : null;
    }

    /**
     * An arm "covers" a struct scrutinee iff it's a {@link IrSort.Structural}
     * with <em>no refined fields</em> (and no nested structural fields, which
     * could themselves be partial) and whose field set is a subset of the
     * scrutinee's. By Pontif's subset-match semantics that arm matches every
     * value of that struct shape — sufficient for trivial totality.
     */
    private static boolean isBareStructuralCovering(
            IrSort armPattern, IrSort.Structural scrutinee,
            Map<String, IrSort.Structural> structDefs) {
        if (!(armPattern instanceof IrSort.Structural arm)) return false;
        for (Map.Entry<String, IrSort> e : arm.members().entrySet()) {
            IrSort declared = scrutinee.members().get(e.getKey());
            if (declared == null) return false;  // field not in the scrutinee → constrains
            IrSort member = e.getValue();
            if (member instanceof IrSort.Refined) return false;  // refines → constrains
            if (member instanceof IrSort.Structural nested) {
                // A nested destructure covers iff it covers the DECLARED nested
                // shape recursively (a different nested shape would constrain).
                IrSort.Structural declaredStruct = resolveNominal(declared, structDefs);
                if (declaredStruct == null
                        || !isBareStructuralCovering(nested, declaredStruct, structDefs)) {
                    return false;
                }
            }
            // bare Named members are binder annotations — non-constraining,
            // matching the pre-existing Tier A behavior.
        }
        return true;
    }

    private static String describeDomain(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            default -> sort.toString();
        };
    }

    /** Renders a predicate from the integer-comparison fragment over {@code @}. */
    private static String renderPredicate(SymExpr e) {
        return switch (e) {
            case SymExpr.Bool b -> Boolean.toString(b.value());
            case SymExpr.Self ignored -> "@";
            case SymExpr.Lit l -> Long.toString(l.value());
            case SymExpr.Dec d -> d.value().toPlainString();
            case SymExpr.Cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) ->
                    renderPredicate(l) + " " + renderCmpOp(op) + " " + renderPredicate(r);
            case SymExpr.And(SymExpr l, SymExpr r) ->
                    renderPredicate(l) + " & " + renderPredicate(r);
            case SymExpr.Or(SymExpr l, SymExpr r) ->
                    "(" + renderPredicate(l) + " | " + renderPredicate(r) + ")";
            default -> e.toString();
        };
    }

    private static String renderCmpOp(SymExpr.CmpOp op) {
        return switch (op) {
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case EQ -> "==";
            case NE -> "!=";
        };
    }

    /**
     * Determines the static sort of an expression where possible. Returns
     * null when no inference is available (e.g., complex expressions whose
     * sort can't be derived from local context).
     *
     * <p>Now consults {@code functionReturns} for {@link IrExpr.Call}
     * expressions, so {@code (call f).x} validates against {@code f}'s
     * declared return sort — closes the TODO item about return-sort
     * propagation.
     */
    private static IrSort inferSort(IrExpr expr, Map<String, IrSort> typeEnv,
                                    Map<String, IrSort> functionReturns,
                                    Map<String, IrSort.Structural> structDefs) {
        return switch (expr) {
            case IrExpr.Var v -> typeEnv.get(v.name());
            // Literal scrutinees have the most statically-known sorts there are
            // — their singletons. Needed now that undeterminable match totality
            // is a hard error: `match 5 [@>=0 & @<=10] -> …` is total over
            // [Int:@==5] (provably — only 5 can flow), while `match 20` against
            // the same arm is provably non-exhaustive at compile time.
            case IrExpr.Lit l -> IrSort.refined("Int",
                    IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), l));
            case IrExpr.Bool b -> IrSort.refined("Bool",
                    IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), b));
            // No decimal kernel reasoning — bare Decimal; matches over it need
            // a default arm, per the rule.
            case IrExpr.Dec d -> IrSort.named("Decimal");
            // Same stance for Char in the value slice: bare Char (the engines
            // abstain on Chr for now). Char IS discrete, so singleton/range
            // reasoning via the integer kernel is the narrows slice's upgrade.
            case IrExpr.Chr c -> IrSort.named("Char");
            // Arithmetic results: Int op Int is Int; any Decimal operand makes
            // it Decimal (promotion). Comparisons/logical yield Bool. Lets a
            // computed scrutinee like `match n + 1` have a provable domain.
            case IrExpr.BinOp op -> switch (op.op()) {
                case ADD, SUB, MUL, DIV, MOD -> {
                    IrSort ls = inferSort(op.left(), typeEnv, functionReturns, structDefs);
                    IrSort rs = inferSort(op.right(), typeEnv, functionReturns, structDefs);
                    String lb = ls == null ? null : matchBaseName(ls);
                    String rb = rs == null ? null : matchBaseName(rs);
                    if ("Decimal".equals(lb) || "Decimal".equals(rb)) yield IrSort.named("Decimal");
                    if ("Int".equals(lb) && "Int".equals(rb)) yield IrSort.named("Int");
                    yield null;
                }
                case LT, LE, GT, GE, EQ, NE, APPROX, AND, OR -> IrSort.named("Bool");
            };
            case IrExpr.FieldAccess fa -> {
                IrSort base = inferSort(fa.base(), typeEnv, functionReturns, structDefs);
                // Resolve a nominal struct reference to its definition by name
                // (single lookup — no body recursion), then project the field.
                // A field whose own sort is a nominal struct reference yields
                // that Named back, so a deeper access resolves one level per
                // FieldAccess node: bounded by expression depth, never by the
                // (possibly recursive) type graph.
                IrSort.Structural sp = resolveNominal(base, structDefs);
                if (sp != null) {
                    yield sp.members().get(fa.fieldName());
                }
                yield null;
            }
            case IrExpr.Call c -> functionReturns.get(c.functionName());
            default -> null;
        };
    }
}

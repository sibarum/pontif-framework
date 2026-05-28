package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.predicates.ComplementResult;
import sibarum.pontif.predicates.PredicateArithmetic;

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
     * <p>{@code Int}, {@code Bool} are primitives.
     *
     * <p>{@code Function} is accepted as a placeholder Named sort for
     * function-typed values. The fully-specified form is
     * {@link IrSort.Function} (with explicit param + return shapes); the
     * placeholder Named form is used in tests and in cases where the exact
     * function shape isn't material. Tighter validation requiring the
     * Function variant for function-typed bindings is a follow-up.
     *
     * <p>{@code "_"} and {@code "_record"} are internal sentinels produced
     * by parser desugars (match-destructure scrutinee placeholder,
     * anonymous-record inference fallback). They aren't user-typable but
     * leak into the IR and must validate.
     */
    private static final Set<String> PRIMITIVE_SORT_NAMES = Set.of(
            "Int", "Bool", "Function",
            "_", "_record");

    private SortChecker() {}

    public static void check(IrModule module) throws CompileException {
        Map<String, IrSort> functionReturns = collectFunctionReturns(module);
        Map<String, IrSort.Trait> traitContracts = collectTraitContracts(module);
        Map<String, IrSort.Structural> structDefs = collectStructDefs(module);

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
     * Collects declared struct definitions from preserved {@link IrStmt.TypeAlias}
     * statements whose sort is an {@link IrSort.Structural}. Used by
     * {@link #validateSortNames} to recognize {@code [StructName:…]}-form
     * refinements and validate their {@code @.field} references against the
     * struct's declared members.
     */
    private static Map<String, IrSort.Structural> collectStructDefs(IrModule module) {
        Map<String, IrSort.Structural> map = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.TypeAlias ta
                    && ta.sort() instanceof IrSort.Structural s) {
                map.put(s.name(), s);
            }
        }
        return map;
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
        for (Map.Entry<String, IrSort.Function> e : contract.methods().entrySet()) {
            String methodName = e.getKey();
            IrSort.Function contractSig = e.getValue();
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
                for (Map.Entry<String, IrSort.Function> e : t.methods().entrySet()) {
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
    private static void validateSortNames(IrSort sort, Map<String, IrSort.Structural> structDefs)
            throws CompileException {
        switch (sort) {
            case IrSort.Named n -> {
                if (!PRIMITIVE_SORT_NAMES.contains(n.name())) {
                    throw new CompileException(
                            "Unknown sort '" + n.name() + "' — not a primitive "
                                    + "and not a declared type (did you forget a "
                                    + "'struct " + n.name() + "(...)' declaration?)",
                            n.origin());
                }
            }
            case IrSort.Refined r -> {
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
            case IrSort.Function f -> {
                for (IrSort p : f.paramSorts()) validateSortNames(p, structDefs);
                validateSortNames(f.returnSort(), structDefs);
            }
            case IrSort.Trait t -> {
                // Trait's name identifies the trait itself — not a reference
                // to another sort. Method-contract sorts are Function sorts;
                // recurse into them to validate param/return types.
                for (IrSort.Function f : t.methods().values()) validateSortNames(f, structDefs);
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
            case IrExpr.Bool ignored -> {}
            case IrExpr.Var ignored -> {}
            case IrExpr.SelfRef ignored -> {}
        }
    }

    private static void checkExpr(IrExpr expr, Map<String, IrSort> typeEnv,
                                  Map<String, IrSort> functionReturns,
                                  Map<String, IrSort.Structural> structDefs)
            throws CompileException {
        switch (expr) {
            case IrExpr.Lit l -> {}
            case IrExpr.Bool b -> {}
            case IrExpr.SelfRef s -> {}
            case IrExpr.Var v -> {}
            case IrExpr.BinOp op -> {
                checkExpr(op.left(), typeEnv, functionReturns, structDefs);
                checkExpr(op.right(), typeEnv, functionReturns, structDefs);
            }
            case IrExpr.LetIn l -> {
                validateSortNames(l.declaredSort(), structDefs);
                checkExpr(l.value(), typeEnv, functionReturns, structDefs);
                Map<String, IrSort> extended = new HashMap<>(typeEnv);
                extended.put(l.name(), l.declaredSort());
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
                IrSort baseSort = inferSort(fa.base(), typeEnv, functionReturns);
                if (baseSort instanceof IrSort.Structural sp) {
                    if (!sp.members().containsKey(fa.fieldName())) {
                        throw new CompileException(
                                "Record of sort '" + sp.name() + "' has no field '"
                                        + fa.fieldName() + "'; available fields: "
                                        + sp.members().keySet(),
                                fa.origin());
                    }
                }
            }
        }
    }

    /**
     * Match totality (alt-syntax principle 8): every value of the scrutinee's
     * sort must be covered by some arm, proven at compile time rather than
     * trusted to a runtime check.
     *
     * <p>This enforces it for the <b>decidable fragment</b> — all arms are
     * {@link IrSort.Refined} over a known scrutinee sort the kernel reasons
     * about (the integer-comparison fragment of {@link PredicateArithmetic}).
     * Everywhere else it <b>defers</b>, leaving the interpreter's runtime
     * no-match check as the safety net: a non-{@code Refined} arm
     * (struct/structural destructuring), an un-inferrable scrutinee sort, a
     * non-{@code Int} domain, or a kernel {@code Unknown}. A {@code _} arm is
     * already desugared to the explicit complement by the parser, so those
     * matches are total by construction and pass here trivially.
     *
     * <p>Sound by construction: an error is raised <em>only</em> when the
     * kernel proves there are uncovered values, so the check never rejects a
     * match it can't decide.
     */
    private static void checkMatchTotality(
            IrExpr.Match m,
            Map<String, IrSort> typeEnv,
            Map<String, IrSort> functionReturns,
            Map<String, IrSort.Structural> structDefs) throws CompileException {
        IrSort scrutineeIr = inferSort(m.scrutinee(), typeEnv, functionReturns);
        if (scrutineeIr == null) return;  // unknown domain → defer

        // Struct totality (Tier A): a bare structural arm (no refined fields)
        // whose field set is a subset of the scrutinee's fields matches every
        // value of that struct shape — per Pontif's subset-semantics structural
        // matching — so the match is trivially total. Richer struct totality
        // (per-field coverage across refined-field arms, struct unions) is
        // deferred — that's a multi-arm cross-product problem.
        Set<String> scrutineeFields = scrutineeFieldSet(scrutineeIr, structDefs);
        if (scrutineeFields != null) {
            for (IrExpr.MatchBranch b : m.branches()) {
                if (isBareStructuralCovering(b.pattern(), scrutineeFields)) {
                    return;  // trivially total
                }
            }
        }

        // Tier B: single-varying-field struct totality. If every arm is a
        // structural pattern refining the SAME single field (with all others
        // bare), totality reduces to "does the union of arms' refinements on
        // that field cover the field's domain?" — a single-field problem the
        // existing kernel decides. Multi-varying-field cross-product is harder
        // and deferred.
        if (tryTierBSingleField(m, scrutineeIr, structDefs)) {
            return;  // total (or threw on non-exhaustive)
        }

        SymExpr union = null;
        for (IrExpr.MatchBranch b : m.branches()) {
            if (!(b.pattern() instanceof IrSort.Refined refined)) return;  // non-refined arm → defer
            SymExpr armPred;
            try {
                armPred = IrCompiler.compileSymExpr(refined.predicate());
            } catch (CompileException e) {
                return;  // arm predicate outside what we can lower → defer
            }
            union = (union == null) ? armPred : SymExpr.or(union, armPred);
        }
        if (union == null) return;  // no arms (the parser rejects empty matches anyway)

        Sort domain;
        try {
            domain = IrCompiler.compileSort(scrutineeIr);
        } catch (CompileException e) {
            return;
        }

        // Uncovered = domain ∧ ¬(union of arms). The kernel returns Unknown for
        // anything outside the Int-comparison fragment → defer on Unknown.
        ComplementResult cr = PredicateArithmetic.complement(union, domain);
        if (!(cr instanceof ComplementResult.Computed computed)) return;
        SymExpr uncovered = computed.predicate();
        if (PredicateArithmetic.satisfiable(uncovered, domain).isYes()) {
            throw new CompileException(
                    "match over " + describeDomain(scrutineeIr) + " is not exhaustive — no arm covers "
                            + renderPredicate(uncovered)
                            + " (every match must be total; add the missing arm or a '_' default)",
                    m.origin());
        }
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
        IrSort.Structural scrutineeStruct = scrutineeStructDef(scrutineeIr, structDefs);
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
     * The declared struct definition for a scrutinee — directly when it's
     * {@link IrSort.Structural}, via {@code structDefs} for a
     * {@link IrSort.Named}/{@link IrSort.Refined} pointing at a declared
     * struct. {@code null} when the scrutinee isn't a struct.
     */
    private static IrSort.Structural scrutineeStructDef(
            IrSort scrutineeIr, Map<String, IrSort.Structural> structDefs) {
        return switch (scrutineeIr) {
            case IrSort.Structural s -> s;
            case IrSort.Named n -> structDefs.get(n.name());
            case IrSort.Refined r -> structDefs.get(r.name());
            default -> null;
        };
    }

    /** Field set of a struct scrutinee, or {@code null} for non-structs. */
    private static Set<String> scrutineeFieldSet(
            IrSort scrutineeIr, Map<String, IrSort.Structural> structDefs) {
        IrSort.Structural def = scrutineeStructDef(scrutineeIr, structDefs);
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
            IrSort armPattern, Set<String> scrutineeFields) {
        if (!(armPattern instanceof IrSort.Structural arm)) return false;
        for (IrSort fieldSort : arm.members().values()) {
            if (fieldSort instanceof IrSort.Refined
                    || fieldSort instanceof IrSort.Structural) return false;
        }
        return scrutineeFields.containsAll(arm.members().keySet());
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
                                    Map<String, IrSort> functionReturns) {
        return switch (expr) {
            case IrExpr.Var v -> typeEnv.get(v.name());
            case IrExpr.FieldAccess fa -> {
                IrSort base = inferSort(fa.base(), typeEnv, functionReturns);
                if (base instanceof IrSort.Structural sp) {
                    yield sp.members().get(fa.fieldName());
                }
                yield null;
            }
            case IrExpr.Call c -> functionReturns.get(c.functionName());
            default -> null;
        };
    }
}

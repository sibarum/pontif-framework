package sibarum.pontif.ir;

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

        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                Map<String, IrSort> typeEnv = new HashMap<>();
                for (IrParam p : fd.params()) {
                    validateSortNames(p.sort());
                    typeEnv.put(p.name(), p.sort());
                }
                validateSortNames(fd.returnSort());
                checkExpr(fd.body(), typeEnv, functionReturns);
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                validateTraitImpl(ti, traitContracts, functionReturns);
            }
        }
        checkExpr(module.main(), new HashMap<>(), functionReturns);
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
            Map<String, IrSort> functionReturns) throws CompileException {
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
                validateSortNames(p.sort());
                typeEnv.put(p.name(), p.sort());
            }
            validateSortNames(m.returnSort());
            checkExpr(m.body(), typeEnv, functionReturns);
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
     * Recursively validates that every {@link IrSort.Named} / refined base
     * is a known primitive. Compound sorts ({@code Structural},
     * {@code Function}) recurse into their members / params.
     *
     * <p>{@link IrSort.Structural#name()} is intentionally NOT validated —
     * it's the sort's own identity, not a reference to another type. A
     * structural sort can be inline-defined without a preceding {@code
     * struct} declaration.
     */
    private static void validateSortNames(IrSort sort) throws CompileException {
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
                if (!PRIMITIVE_SORT_NAMES.contains(r.name())) {
                    throw new CompileException(
                            "Unknown base sort '" + r.name() + "' in refinement "
                                    + "[" + r.name() + ":...] — refinements must be "
                                    + "over a primitive (Int, Bool).",
                            r.origin());
                }
            }
            case IrSort.Structural s -> {
                for (IrSort member : s.members().values()) {
                    validateSortNames(member);
                }
            }
            case IrSort.Function f -> {
                for (IrSort p : f.paramSorts()) validateSortNames(p);
                validateSortNames(f.returnSort());
            }
            case IrSort.Trait t -> {
                // Trait's name identifies the trait itself — not a reference
                // to another sort. Method-contract sorts are Function sorts;
                // recurse into them to validate param/return types.
                for (IrSort.Function f : t.methods().values()) validateSortNames(f);
            }
        }
    }

    private static void checkExpr(IrExpr expr, Map<String, IrSort> typeEnv,
                                  Map<String, IrSort> functionReturns)
            throws CompileException {
        switch (expr) {
            case IrExpr.Lit l -> {}
            case IrExpr.Bool b -> {}
            case IrExpr.SelfRef s -> {}
            case IrExpr.Var v -> {}
            case IrExpr.BinOp op -> {
                checkExpr(op.left(), typeEnv, functionReturns);
                checkExpr(op.right(), typeEnv, functionReturns);
            }
            case IrExpr.LetIn l -> {
                validateSortNames(l.declaredSort());
                checkExpr(l.value(), typeEnv, functionReturns);
                Map<String, IrSort> extended = new HashMap<>(typeEnv);
                extended.put(l.name(), l.declaredSort());
                checkExpr(l.body(), extended, functionReturns);
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
                for (IrExpr arg : c.args()) checkExpr(arg, typeEnv, functionReturns);
            }
            case IrExpr.Lambda lam -> {
                for (IrParam p : lam.params()) validateSortNames(p.sort());
                validateSortNames(lam.returnSort());
                Map<String, IrSort> extended = new HashMap<>(typeEnv);
                for (IrParam p : lam.params()) extended.put(p.name(), p.sort());
                checkExpr(lam.body(), extended, functionReturns);
            }
            case IrExpr.Apply app -> {
                checkExpr(app.fn(), typeEnv, functionReturns);
                for (IrExpr a : app.args()) checkExpr(a, typeEnv, functionReturns);
            }
            case IrExpr.Match m -> {
                checkExpr(m.scrutinee(), typeEnv, functionReturns);
                for (IrExpr.MatchBranch b : m.branches()) {
                    validateSortNames(b.pattern());
                    Map<String, IrSort> branchEnv = new HashMap<>(typeEnv);
                    if (m.scrutinee() instanceof IrExpr.Var v
                            && b.pattern() instanceof IrSort.Structural) {
                        branchEnv.put(v.name(), b.pattern());
                    }
                    checkExpr(b.result(), branchEnv, functionReturns);
                }
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) checkExpr(v, typeEnv, functionReturns);
            }
            case IrExpr.FieldAccess fa -> {
                checkExpr(fa.base(), typeEnv, functionReturns);
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

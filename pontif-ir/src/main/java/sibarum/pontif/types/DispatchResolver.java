package sibarum.pontif.types;

import java.util.ArrayList;
import java.util.List;

import sibarum.pontif.ir.InferenceContext;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.ir.MethodOperatorResolver;
import sibarum.pontif.ir.StaticDispatch;

/**
 * Evaluates a {@link DispatchQuery} — the behind-the-facade dispatch answerer, the single point that
 * unifies the deciders that used to each answer their own dispatch question. The query's constraint set
 * selects the determinacy at which it is answered:
 *
 * <ul>
 *   <li><b>Method name-routing</b> — a {@link DispatchQuery#receiver} constraint: does the selector name
 *       a method on the receiver's base type? Answered by the routable-key set (which includes trait
 *       contract keys for a bare-trait receiver's existential boundary). Coarse: it names the routing
 *       target, not a most-specific overload.</li>
 *   <li><b>Operator name-routing</b> — a bare operator symbol selector ({@code "+"}): which operator
 *       family do these operand <em>base</em> sorts route to? A refinement-blind base-name match over the
 *       operators declared for that symbol. Coarse: it names the family; runtime dispatch picks the
 *       most-specific member. (This is symbol-keyed, distinct from the resolved-name-keyed overload
 *       table the refinement path below reads.)</li>
 *   <li><b>Refinement selection</b> — a resolved-name selector with argument narrowings: the
 *       narrowing-aware {@link StaticDispatch} path (the call gate's and inference's question).</li>
 * </ul>
 *
 * <p>Visibility gating (import-by-association) is deliberately <em>not</em> done here — the operator path
 * returns the whole matched family and {@link MethodOperatorResolver} (which holds the per-module
 * {@code ModuleScope}) filters it. An {@link DispatchQuery#expectedReturn} is still not consulted; the
 * return-directed-dispatch slice teaches this resolver to read it.
 */
final class DispatchResolver {

    private DispatchResolver() {}

    static DispatchResult resolve(DispatchQuery query, InferenceContext ctx) {
        if (query.receiver().isPresent()) {
            return routeMethod(query, ctx);
        }
        if (MethodOperatorResolver.isOperatorSymbol(query.selector()) && query.args().size() == 2) {
            return routeOperator(query, ctx);
        }
        return selectByRefinement(query, ctx);
    }

    /**
     * Method name-routing: does {@code base(receiver).selector} name a routable method key? Returns the
     * matching declarations as the routing family (empty for a trait-contract key that has no concrete
     * declaration — the bare-trait existential boundary). A receiver whose base type is unknown, or a
     * selector that names no method key, is {@link DispatchResult.Residual} — the caller then does its
     * field-access fallback / diagnostic.
     */
    private static DispatchResult routeMethod(DispatchQuery query, InferenceContext ctx) {
        String typeName = baseName(query.receiver().orElse(null));
        if (typeName == null) {
            return new DispatchResult.Residual(List.of());
        }
        String key = typeName + "." + query.selector();
        // Tolerate the linker's module qualification: a bare receiver nominal (e.g. a
        // metareference's builtin `AlgebraicDispatch`) routes to a `mod/Type.method` key when
        // the impl lives in a required module. Mirrors the attribute-producer lookup.
        String resolvedKey = ctx.methodKeys().contains(key) ? key
                : ctx.methodKeys().stream()
                        .filter(k -> k.endsWith("/" + key)).findFirst().orElse(null);
        if (resolvedKey != null) {
            return new DispatchResult.Ambiguous(ctx.overloads().getOrDefault(resolvedKey, List.of()));
        }
        return new DispatchResult.Residual(List.of());
    }

    /**
     * Operator name-routing: the family of operator overloads for {@code selector} whose two parameter
     * <em>base</em> names match the operand base sorts (refinement-blind — a base-name match is enough to
     * both decide BinOp-vs-Call and name the dispatch key; most-specific among same-keyed overloads is
     * runtime's job). No match — including an operand with no base name — is {@link DispatchResult.Residual}
     * (the operator stays a BinOp, deferred to the primitive/trait rules).
     */
    private static DispatchResult routeOperator(DispatchQuery query, InferenceContext ctx) {
        String lb = baseName(query.args().get(0).sort());
        String rb = baseName(query.args().get(1).sort());
        if (lb == null || rb == null) {
            return new DispatchResult.Residual(List.of());
        }
        List<IrStmt.FunctionDecl> family = new ArrayList<>();
        for (IrStmt.FunctionDecl fd : ctx.operatorOverloads().getOrDefault(query.selector(), List.of())) {
            if (lb.equals(baseName(fd.params().get(0).sort()))
                    && rb.equals(baseName(fd.params().get(1).sort()))) {
                family.add(fd);
            }
        }
        return family.isEmpty()
                ? new DispatchResult.Residual(List.of())
                : new DispatchResult.Ambiguous(family);
    }

    /** The narrowing-aware refinement-selection path (the call gate's and inference's question). */
    private static DispatchResult selectByRefinement(DispatchQuery query, InferenceContext ctx) {
        List<IrStmt.FunctionDecl> overloads = ctx.overloads().get(query.selector());
        if (overloads == null) overloads = List.of();
        List<IrSort> argSorts = new ArrayList<>(query.args().size());
        for (DispatchQuery.ArgConstraint a : query.args()) argSorts.add(a.sort());
        var registry = ctx.sortRegistry();

        // A unique definite match is the resolved target. Otherwise the three-way classify verdict
        // splits the rest: FAILED (every arity-matching overload provably excluded) ⇒ Unsatisfiable
        // (compile error); PASSED (≥1 definite match but no unique winner) ⇒ Ambiguous (routes, defer
        // which); RESIDUAL (kernel can't decide) ⇒ Residual.
        StaticDispatch.Result res = StaticDispatch.resolve(overloads, argSorts, registry);
        if (res instanceof StaticDispatch.Result.Resolved r) {
            return new DispatchResult.Resolved(r.decl());
        }
        return switch (StaticDispatch.classify(overloads, argSorts, registry, ctx.traitImpls())) {
            case FAILED -> new DispatchResult.Unsatisfiable(
                    "no target satisfies '" + query.selector() + "' at the given argument sorts");
            case PASSED -> new DispatchResult.Ambiguous(overloads);
            case RESIDUAL -> new DispatchResult.Residual(overloads);
        };
    }

    /** The nominal base type name of a sort, or null if it has none. Mirrors the resolver clients. */
    private static String baseName(IrSort sort) {
        if (sort == null) return null;
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            case IrSort.Trait t -> t.name();
            // A call-signature receiver routes methods on its head type — a metareference
            // ($f[…] : AlgebraicDispatch) dispatches its traits' methods like any nominal.
            case IrSort.CallSig c -> c.typeName();
            default -> null;
        };
    }
}

package sibarum.pontif.types;

import java.util.ArrayList;
import java.util.List;

import sibarum.pontif.ir.InferenceContext;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.ir.StaticDispatch;

/**
 * Evaluates a {@link DispatchQuery} — the behind-the-facade dispatch answerer. Today it delegates to the
 * existing {@link StaticDispatch} (the strangler-fig seam): it looks the target overloads up by selector,
 * runs static resolution, and folds {@code StaticDispatch}'s two- and three-valued answers into the one
 * {@link DispatchResult}. As the other dispatch sites (the call gate, name-routing, the runtime table)
 * migrate onto the query, their logic moves here and the scattered copies collapse.
 *
 * <p><b>Coverage so far:</b> free-function / operator dispatch by {@link DispatchQuery#selector} and
 * positional {@link DispatchQuery#args}. A method {@link DispatchQuery#receiver} and an
 * {@link DispatchQuery#expectedReturn} are not yet consulted — the slices that introduce method dispatch
 * and return-directed dispatch teach this resolver to read them.
 */
final class DispatchResolver {

    private DispatchResolver() {}

    static DispatchResult resolve(DispatchQuery query, InferenceContext ctx) {
        List<IrStmt.FunctionDecl> overloads = ctx.overloads().get(query.selector());
        if (overloads == null) overloads = List.of();
        List<IrSort> argSorts = new ArrayList<>(query.args().size());
        for (DispatchQuery.ArgConstraint a : query.args()) argSorts.add(a.sort());
        var registry = ctx.sortRegistry();

        // A unique definite match is the resolved target. Otherwise split the "not unique" cases the way
        // the query demands: every arity-matching overload provably excluded ⇒ Unsatisfiable (compile
        // error); several matches or an undecided kernel ⇒ Residual (defer to a more-determined query).
        StaticDispatch.Result res = StaticDispatch.resolve(overloads, argSorts, registry);
        if (res instanceof StaticDispatch.Result.Resolved r) {
            return new DispatchResult.Resolved(r.decl());
        }
        StaticDispatch.Verdict verdict = StaticDispatch.classify(overloads, argSorts, registry);
        if (verdict == StaticDispatch.Verdict.FAILED) {
            return new DispatchResult.Unsatisfiable(
                    "no target satisfies '" + query.selector() + "' at the given argument sorts");
        }
        return new DispatchResult.Residual(overloads);
    }
}

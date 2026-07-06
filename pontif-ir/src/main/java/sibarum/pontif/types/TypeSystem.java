package sibarum.pontif.types;

import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.InferenceContext;

/**
 * The single front door to the Pontif type system — the API every phase (parser, resolvers, gates,
 * interpreter) is meant to ask, rather than each re-deciding type questions for itself. It exists to
 * end the friction catalogued in {@code docs/language-inventory.md} (§4 route-decided-twice, §5 two
 * divergent inferencers, plus the parser's own third inferencer): route every question here and those
 * duplications collapse.
 *
 * <p><b>Two invariants this surface enforces by construction:</b>
 * <ul>
 *   <li><b>One answerer.</b> There is a single {@link #infer} — "what is this value, exactly?" — so no
 *       shadow inferencer can drift out of agreement with it.</li>
 *   <li><b>Coercion is a query, not an insertion.</b> When it lands, {@code coercionFor(from, to)} will
 *       <em>return</em> the coercion a caller should emit (demote / trait-cast / widen / autobox /
 *       none / error) instead of the parser deciding and baking it in inline.</li>
 * </ul>
 *
 * <p><b>Migration status (strangler-fig).</b> Today this is a thin, behaviour-preserving facade over
 * the existing engine ({@link sibarum.pontif.ir.NarrowingInference} et al.) — see
 * {@link #standard()}. Clients are migrated to call through it one at a time; only once a question's
 * callers all route here does its logic move <em>behind</em> the facade and the scattered copies
 * delete. The surface starts minimal and grows as each migration reveals what it genuinely needs — the
 * perfect API is discovered by migrating real call sites, not designed in the abstract.
 *
 * <p><b>Roadmap surface</b> (added slice by slice as clients migrate, each grounded in a concrete
 * call site it replaces): {@code satisfies(value, claim, ctx)} (subsumption — {@code Refinements});
 * {@code coercionFor(from, to, ctx)} (the parser's let-binding coercion block, as a query);
 * {@code resolveSort(name)} / {@code structOf(name)} (the alias/struct/trait registries);
 * {@code resolveMethod(recv, name, ctx)} / {@code resolveOverload(name, args, ctx)} (dispatch);
 * {@code discharge(obligation)} (the {@code pontif-predicates} kernels).
 */
public interface TypeSystem {

    /**
     * The tightest true sort of {@code expr} under what {@code ctx} knows — the one answerer. A literal
     * is a value-pin ({@code [Int:@==5]}), an arithmetic result an exact pin, and a fact whose inputs
     * have left scope is projected to a bound; when it cannot prove the precise sort it abstains to the
     * honest coarse one rather than bluffing.
     */
    IrSort infer(IrExpr expr, InferenceContext ctx);

    /** The standard type system — today a thin facade over the existing engine (the migration seam). */
    static TypeSystem standard() {
        return DelegatingTypeSystem.INSTANCE;
    }
}

package sibarum.pontif.types;

import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.InferenceContext;
import sibarum.pontif.ir.NarrowingInference;

/**
 * The behaviour-preserving first implementation of {@link TypeSystem}: every method forwards to the
 * existing engine, so routing a client through the facade changes nothing observable. As clients
 * migrate onto the facade, the real logic moves here (or into collaborators in this package) and the
 * forwarded-to copies delete. Stateless — the {@link InferenceContext} carries everything a query
 * needs — so a single shared instance suffices.
 */
final class DelegatingTypeSystem implements TypeSystem {

    static final DelegatingTypeSystem INSTANCE = new DelegatingTypeSystem();

    private DelegatingTypeSystem() {}

    @Override
    public IrSort infer(IrExpr expr, InferenceContext ctx) {
        return NarrowingInference.infer(expr, ctx);
    }

    @Override
    public IrSort inferArg(IrExpr arg, InferenceContext ctx) {
        return NarrowingInference.inferArg(arg, ctx);
    }

    @Override
    public IrSort inferFloor(IrExpr expr, InferenceContext ctx) {
        return NarrowingInference.inferFloor(expr, ctx);
    }

    @Override
    public Coercion coercionFor(IrSort from, IrSort to, CoercionContext ctx) {
        return CoercionResolver.resolve(from, to, ctx);
    }

    @Override
    public DispatchResult dispatch(DispatchQuery query, InferenceContext ctx) {
        return DispatchResolver.resolve(query, ctx);
    }
}

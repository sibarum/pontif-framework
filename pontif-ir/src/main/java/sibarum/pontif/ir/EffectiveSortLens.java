package sibarum.pontif.ir;

import java.util.LinkedHashMap;
import java.util.Map;

import sibarum.pontif.core.Origin;

/**
 * The module-wide effective-sort lens: {@code span → effective sort} for every position in every
 * function body (and {@code main}), computed once and carried on {@link CompiledModule} so it
 * survives compilation — read by the construction/claim gates and, later, an IDE
 * (docs/type-records.md, the Inferred record).
 *
 * <p>A thin driver: it seeds each function's parameters into scope and delegates the actual walk to
 * {@link NarrowingInference#effectiveSorts} (which owns the per-node calculation and the narrowing
 * env-threading). The pass is cohesive — its sole job is assembling the per-function lenses into one
 * module map — and reuses, rather than restates, the sort calculation and the param/scope threading.
 */
final class EffectiveSortLens {

    private EffectiveSortLens() {}

    static Map<Origin.Span, IrSort> of(IrModule module) {
        InferenceContext base = InferenceContext.fromModule(module);
        Map<Origin.Span, IrSort> lens = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            switch (stmt) {
                case IrStmt.FunctionDecl fd -> addFunction(lens, fd, base);
                case IrStmt.TraitImpl ti -> {
                    for (IrStmt.FunctionDecl m : ti.methods()) addFunction(lens, m, base);
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) addFunction(lens, a, base);
                }
                default -> { }  // TypeAlias / Proof / Requires / Exports / NoOp — no expressions
            }
        }
        if (module.main() != null) {
            lens.putAll(NarrowingInference.effectiveSorts(module.main(), base));
        }
        return lens;
    }

    private static void addFunction(
            Map<Origin.Span, IrSort> lens, IrStmt.FunctionDecl fd, InferenceContext base) {
        lens.putAll(NarrowingInference.effectiveSorts(fd.body(), base.withParams(fd.params())));
    }
}

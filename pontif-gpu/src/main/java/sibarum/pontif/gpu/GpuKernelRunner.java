package sibarum.pontif.gpu;

import dev.supirvast.vastir.tools.Accelerator;
import dev.supirvast.vastir.tools.KernelHandle;
import dev.supirvast.vastir.tools.KernelSpec;
import dev.supirvast.vastir.tools.Registration;
import dev.supirvast.vastir.tools.Rejection;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.KernelRunners;
import sibarum.pontif.supirvast.KernelLowering;
import sibarum.pontif.supirvast.ValueMarshaller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The general GPU kernel runner behind the {@code … on Gpu} directive (docs/gpu-kernels.md).
 * Registered into the core {@link KernelRunners} seam by {@link GpuExtension} when the opt-in
 * {@code pontif-gpu} module is loaded; the interpreter hands it any {@code gpu}-marked
 * {@link IrExpr.Iterate} plus the evaluated source stream values.
 *
 * <p>It reuses {@link KernelLowering} for arbitrary map/zip iterations (fold/fork and guarded
 * shapes fail closed there with a source-located {@code LoweringError} — the eligibility check),
 * marshals the sources to honest {@code i64} columns, dispatches on the GPU via {@link Accelerator}
 * (SPIR-V / Vulkan), and un-marshals the result to a Pontif {@code Stream[Int]}.
 */
public final class GpuKernelRunner implements KernelRunners.KernelRunner {

    @Override
    public Object run(IrExpr.Iterate iterate, List<Object> sourceValues) {
        // The parser leaves the fragment APPLIED in the body — `Apply(λ, [element._0, element._1])`
        // for a zip, `Apply(λ, [element])` for a map. KernelLowering/ExprLowering expect it already
        // INLINED (`element._0 + element._1`), so beta-reduce the applied fragment first. (KernelLowering's
        // own tests hand-built the inlined shape; real parser output needs this bridge.)
        KernelSpec spec = new KernelLowering().lower(betaReduce(iterate));

        long[][] inputs = new long[sourceValues.size()][];
        int n = Integer.MAX_VALUE;
        for (int i = 0; i < sourceValues.size(); i++) {
            inputs[i] = GpuKernels.longs(sourceValues.get(i), "source " + i);
            n = Math.min(n, inputs[i].length);            // zip stops at the shortest
        }
        if (n == Integer.MAX_VALUE) n = 0;

        try (Accelerator accelerator = new Accelerator()) {
            Registration registration = accelerator.register(spec);
            if (!(registration instanceof KernelHandle handle)) {
                Rejection rejection = (Rejection) registration;
                throw new RuntimeException("`… on Gpu`: the GPU rejected the kernel — "
                        + rejection.reason() + ": " + rejection.detail());
            }
            int[][] columns = new int[inputs.length + 1][];
            columns[0] = ValueMarshaller.outputColumn(n);         // slot 0 = output
            for (int i = 0; i < inputs.length; i++) {
                columns[i + 1] = ValueMarshaller.toColumn(GpuKernels.prefix(inputs[i], n));
            }
            int[][] result = handle.run(columns, n);
            return GpuKernels.stream(ValueMarshaller.fromColumn(result[0], n));
        }
    }

    /**
     * Inlines the applied fragment in each arm's write value ({@code Apply(λ, args)} → the lambda
     * body with its params substituted by the args), so the kernel body is the arithmetic
     * {@code KernelLowering}/{@code ExprLowering} lower directly.
     */
    private static IrExpr.Iterate betaReduce(IrExpr.Iterate it) {
        List<IrExpr.Arm> arms = new ArrayList<>(it.arms().size());
        for (IrExpr.Arm arm : it.arms()) {
            List<IrExpr.Write> writes = new ArrayList<>(arm.writes().size());
            for (IrExpr.Write w : arm.writes()) {
                writes.add(new IrExpr.Write(w.output(), w.key(), inlineApplied(w.value())));
            }
            arms.add(new IrExpr.Arm(arm.pattern(), writes));
        }
        return new IrExpr.Iterate(it.source(), it.coSources(), it.element(),
                it.outputs(), arms, it.origin(), it.gpu());
    }

    private static IrExpr inlineApplied(IrExpr e) {
        if (e instanceof IrExpr.Apply ap && ap.fn() instanceof IrExpr.Lambda lam
                && lam.params().size() == ap.args().size()) {
            Map<String, IrExpr> env = new HashMap<>();
            for (int i = 0; i < lam.params().size(); i++) {
                env.put(lam.params().get(i).name(), ap.args().get(i));
            }
            return subst(lam.body(), env);
        }
        return e;
    }

    /** Capture-free enough for fragment bodies (arithmetic + math calls + field access + let). */
    private static IrExpr subst(IrExpr e, Map<String, IrExpr> env) {
        return switch (e) {
            case IrExpr.Var v -> env.getOrDefault(v.name(), v);
            case IrExpr.BinOp op -> new IrExpr.BinOp(op.op(), subst(op.left(), env), subst(op.right(), env), op.origin());
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(subst(fa.base(), env), fa.fieldName(), fa.origin());
            case IrExpr.Call c -> new IrExpr.Call(c.functionName(),
                    c.args().stream().map(a -> subst(a, env)).toList(), c.origin());
            case IrExpr.LetIn let -> new IrExpr.LetIn(let.name(), let.declaredSort(),
                    subst(let.value(), env), subst(let.body(), env), let.origin());
            case IrExpr.Apply ap -> new IrExpr.Apply(subst(ap.fn(), env),
                    ap.args().stream().map(a -> subst(a, env)).toList(), ap.origin());
            default -> e;   // Lit/Dec/Bool/etc. carry no vars; unsupported nodes fail closed in lowering
        };
    }
}


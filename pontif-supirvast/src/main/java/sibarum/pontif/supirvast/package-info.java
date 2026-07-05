/**
 * Lowers a restricted slice of Pontif's IR onto SuperVast's target-neutral {@code core} IR and runs it on the
 * GPU through the {@link dev.supirvast.vastir.tools.Accelerator} facade.
 *
 * <p>The guiding principle: <em>attempting to lower is the validation</em>. Lowering is a total function over
 * the supported subset; the first unsupported construct throws a {@code LoweringError} carrying the offending
 * node's {@link sibarum.pontif.core.Origin} and a concrete reason, rather than miscompiling silently. SuperVast's
 * own {@link dev.supirvast.vastir.tools.Rejection} witnesses (capability budget, {@code spirv-val}) surface
 * through the same reporting path.
 *
 * <p>v1 scope: Int-only data-parallel compute kernels ({@code Int} → {@code int64}, {@code Bool} → {@code bool}).
 * Floats, aggregates in buffers, and graphics/PBR are deferred.
 */
package sibarum.pontif.supirvast;

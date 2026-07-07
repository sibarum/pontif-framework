package sibarum.pontif.types;

import java.util.List;

import sibarum.pontif.ir.IrStmt;

/**
 * The answer to a {@link DispatchQuery} — the one dispatch verdict, at whatever level of detail the
 * query's constraints determined. It is a satisfiability answer over the declared targets:
 *
 * <ul>
 *   <li>{@link Resolved} — exactly one target always satisfies the constraints; route/select it.</li>
 *   <li>{@link Ambiguous} — several targets provably satisfy, with no unique most-specific winner. The
 *       call <em>routes</em> (it is satisfiable), but which target needs more determinacy — a
 *       narrower query, ultimately the runtime one.</li>
 *   <li>{@link Unsatisfiable} — no target can <em>ever</em> satisfy them. At compile time this is the
 *       no-lie boundary: a provable misroute, i.e. a compile error (the verdict today's
 *       {@code StaticDispatch.classify} calls {@code FAILED}).</li>
 *   <li>{@link Residual} — undecided at this determinacy: it cannot be proven whether any target
 *       satisfies (the kernel abstained). A more-determined query resolves it.</li>
 * </ul>
 *
 * <p>{@link Resolved} and {@link Ambiguous} both mean "provably routes" (the call gate's {@code PASSED});
 * they differ only on whether a unique target is already pinned. {@link Residual} means "can't prove it
 * routes or misroutes" (the gate's {@code RESIDUAL}); {@link Unsatisfiable} is the gate's {@code FAILED}.
 *
 * <p>The same three-way answer serves every point on the determinacy gradient — coarse name-routing,
 * refinement selection, the call gate, and (as a fast-path specialization) runtime dispatch.
 */
public sealed interface DispatchResult {

    /** Exactly one target always satisfies the query's constraints. */
    record Resolved(IrStmt.FunctionDecl target) implements DispatchResult {}

    /** Several targets provably satisfy, with no unique winner — routes, but not yet pinned. */
    record Ambiguous(List<IrStmt.FunctionDecl> candidates) implements DispatchResult {}

    /** No target can ever satisfy the constraints — a compile-time error (provable misroute). */
    record Unsatisfiable(String reason) implements DispatchResult {}

    /** Undecided at this determinacy — cannot prove any target satisfies (the kernel abstained). */
    record Residual(List<IrStmt.FunctionDecl> candidates) implements DispatchResult {}
}

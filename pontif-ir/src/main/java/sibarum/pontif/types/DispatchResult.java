package sibarum.pontif.types;

import java.util.List;

import sibarum.pontif.ir.IrStmt;

/**
 * The answer to a {@link DispatchQuery} — the one dispatch verdict, at whatever level of detail the
 * query's constraints determined. It is a satisfiability answer over the declared targets:
 *
 * <ul>
 *   <li>{@link Resolved} — exactly one target always satisfies the constraints; route/select it.</li>
 *   <li>{@link Unsatisfiable} — no target can <em>ever</em> satisfy them. At compile time this is the
 *       no-lie boundary: a provable misroute, i.e. a compile error (the verdict today's
 *       {@code StaticDispatch.classify} calls {@code FAILED}).</li>
 *   <li>{@link Residual} — undecided at this determinacy: several targets remain, or the kernel could
 *       not decide. A more-determined query (ultimately the runtime one, with every argument a
 *       constant) resolves it.</li>
 * </ul>
 *
 * <p>The same three-way answer serves every point on the determinacy gradient — coarse name-routing,
 * refinement selection, the call gate, and (as a fast-path specialization) runtime dispatch.
 */
public sealed interface DispatchResult {

    /** Exactly one target always satisfies the query's constraints. */
    record Resolved(IrStmt.FunctionDecl target) implements DispatchResult {}

    /** No target can ever satisfy the constraints — a compile-time error (provable misroute). */
    record Unsatisfiable(String reason) implements DispatchResult {}

    /** Undecided at this determinacy — several candidates remain, or the kernel abstained. */
    record Residual(List<IrStmt.FunctionDecl> candidates) implements DispatchResult {}
}

package sibarum.pontif.conservation;

import java.util.List;

/**
 * An edge of the conservation graph. Per the algebra
 * ({@code docs/conservation-algebra.md}): metadata — constants, naming,
 * binding, path selection — lives on edges, never as nodes. A flow either IS
 * an input attribute (verbatim, content-preserving through any chain of
 * reference/projection/binding), comes from a node, carries a constant, or is
 * {@link Residual} — the algebra's located ignorance: lambdas, applications,
 * and unresolved/recursive calls, nothing else. Residual flows carry the
 * over-approximated set of atoms they touch so conservation queries can fail
 * closed on exactly the right atoms (an empty touch set poisons the whole
 * path).
 */
public sealed interface Flow {

    record Verbatim(AttributePath path) implements Flow {}

    record FromNode(String nodeId) implements Flow {}

    record Constant(String rendering) implements Flow {}

    record Residual(String reason, List<AttributePath> touches) implements Flow {
        public Residual { touches = List.copyOf(touches); }
    }

    default String render() {
        return switch (this) {
            case Verbatim v -> v.path().toString();
            case FromNode n -> n.nodeId();
            case Constant c -> c.rendering();
            case Residual r -> "?(" + r.reason() + ")";
        };
    }
}

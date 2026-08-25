package sibarum.pontif.ast.coerce;

import java.util.List;

import com.oracle.truffle.api.frame.VirtualFrame;

import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.types.CanonicalText;
import sibarum.pontif.core.types.StringValue;

/**
 * The built-in render to {@code String} — the Truffle counterpart of {@code
 * IrInterpreter.evalCast}'s String branch, so {@code (String:value)} means the same thing on
 * both engines instead of being an interpreter-only feature that stopped the Truffle backend
 * with "not yet implemented".
 *
 * <p>The rendering itself is {@link CanonicalText}, the single renderer both engines share —
 * the same rule behind String {@code +} concatenation (docs/soundness-holes.md, family 5).
 * Rendering here rather than reimplementing it is the whole point: a second copy is how the two
 * engines came to disagree about String {@code +} in the first place.
 *
 * <p>Fails closed on a value with no canonical render — an aggregate or a closure — matching the
 * interpreter's message. A user-defined {@code cast Target:(x:Source)} coercion is a different
 * cast target and remains interpreter-only.
 */
public final class RenderToStringNode extends PontifNode {

    @Child private PontifNode value;

    private RenderToStringNode(PontifNode value) {
        this.value = value;
    }

    public static RenderToStringNode of(PontifNode value) {
        if (value == null) {
            throw new IllegalArgumentException("RenderToStringNode value must be non-null");
        }
        return new RenderToStringNode(value);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object v = value.execute(frame);
        String rendered = CanonicalText.of(v);
        if (rendered == null) {
            throw new RuntimeCheckException(
                    "Cannot cast " + (v == null ? "null" : v.getClass().getSimpleName())
                            + " to String — only Int, Decimal, Char, Bool and String render",
                    origin());
        }
        return new StringValue(rendered);
    }

    @Override
    public List<PontifNode> children() {
        return List.of(value);
    }
}

package sibarum.pontif.ast.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.types.CharValue;

import java.util.List;

/** Truffle literal node for a {@code Char} value (Unicode code point). */
public final class CharLiteral extends PontifNode {

    private final CharValue value;

    private CharLiteral(CharValue value) {
        this.value = value;
    }

    public static CharLiteral of(int codePoint) {
        return new CharLiteral(new CharValue(codePoint));
    }

    public CharValue value() {
        return value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }

    @Override
    public List<PontifNode> children() {
        return List.of();
    }
}

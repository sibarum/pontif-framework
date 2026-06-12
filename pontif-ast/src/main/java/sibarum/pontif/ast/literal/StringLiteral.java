package sibarum.pontif.ast.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.types.StringValue;

import java.util.List;

/** Truffle literal node for a {@code String} value (the first Char collection). */
public final class StringLiteral extends PontifNode {

    private final StringValue value;

    private StringLiteral(StringValue value) {
        this.value = value;
    }

    public static StringLiteral of(String content) {
        return new StringLiteral(new StringValue(content));
    }

    public StringValue value() {
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

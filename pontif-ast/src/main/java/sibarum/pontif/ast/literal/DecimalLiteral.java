package sibarum.pontif.ast.literal;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;

import java.math.BigDecimal;
import java.util.List;

/** Truffle literal node for a {@code Decimal} value (BigDecimal-backed). */
public final class DecimalLiteral extends PontifNode {

    private final BigDecimal value;

    private DecimalLiteral(BigDecimal value) {
        this.value = value;
    }

    public static DecimalLiteral of(BigDecimal value) {
        return new DecimalLiteral(value);
    }

    public BigDecimal value() {
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

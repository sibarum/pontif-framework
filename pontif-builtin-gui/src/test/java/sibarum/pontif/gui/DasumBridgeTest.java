package sibarum.pontif.gui;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.types.RecordValue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GL-free unit tests for {@link DasumBridge}'s data marshalling — the risky core of the plotting
 * slice (docs/extensions.md). {@code doubles(...)} converts a Pontif numeric aggregate (a
 * {@code _tuple} RecordValue of Int/Decimal scalars) to the {@code double[]} a dasum-vis
 * {@code Series} consumes. The actual chart render is verified manually (needs GLFW):
 * {@code mvn -pl pontif-builtin-gui -am exec:exec -Dptf=examples/line-plot.ptf}.
 */
class DasumBridgeTest {

    @Test
    void doubles_convertsMixedIntAndDecimalMembersInOrder() {
        // A Pontif {0, 1.5, 4} aggregate: Int → Long, Decimal → BigDecimal, plus a boxed Integer.
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("0", 0L);
        members.put("1", new BigDecimal("1.5"));
        members.put("2", 4);
        double[] result = DasumBridge.doubles(new RecordValue("_tuple", members));
        assertArrayEquals(new double[]{0.0, 1.5, 4.0}, result, 1e-12);
    }

    @Test
    void doubles_nonRecordYieldsEmpty() {
        assertEquals(0, DasumBridge.doubles("not a tuple").length);
        assertEquals(0, DasumBridge.doubles(null).length);
    }
}

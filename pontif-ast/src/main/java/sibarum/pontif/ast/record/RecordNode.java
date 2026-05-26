package sibarum.pontif.ast.record;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecordNode extends PontifNode {

    private final String typeName;  // nullable — nominal struct type, or null for anonymous records
    private final String[] fieldNames;
    @Children private final PontifNode[] valueNodes;

    private RecordNode(String typeName, String[] fieldNames, PontifNode[] valueNodes) {
        this.typeName = typeName;
        this.fieldNames = fieldNames;
        this.valueNodes = valueNodes;
    }

    public static RecordNode of(List<String> fieldNames, List<PontifNode> valueNodes) {
        return of(null, fieldNames, valueNodes);
    }

    public static RecordNode of(String typeName, List<String> fieldNames, List<PontifNode> valueNodes) {
        if (fieldNames.size() != valueNodes.size()) {
            throw new IllegalArgumentException(
                    "RecordNode field name count (" + fieldNames.size()
                            + ") must equal value node count (" + valueNodes.size() + ")");
        }
        return new RecordNode(
                typeName,
                fieldNames.toArray(new String[0]),
                valueNodes.toArray(new PontifNode[0]));
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Map<String, Object> members = new LinkedHashMap<>();
        for (int i = 0; i < fieldNames.length; i++) {
            members.put(fieldNames[i], valueNodes[i].execute(frame));
        }
        return new RecordValue(typeName, members);
    }

    @Override
    public List<PontifNode> children() {
        return Arrays.asList(valueNodes);
    }
}

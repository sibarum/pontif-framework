package sibarum.pontif.net;

import sibarum.elektro.queue.dyn.DynValue;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.CharValue;
import sibarum.pontif.core.types.StringValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between Pontif runtime values and elektro-Q's neutral {@link DynValue} &mdash; the one
 * bridge both the debug port and the {@code pontif.net} builtin rely on.
 *
 * <p>Pontif runtime values are dynamic: a {@link RecordValue} carrying a type name and an ordered
 * field map, with scalars represented as {@code Long} (Int), {@code BigDecimal} (Decimal),
 * {@code Boolean}, {@link CharValue}, and {@link StringValue}. Tuples are a {@code RecordValue}
 * under the {@code "_tuple"} sentinel with positional keys; {@code Nothing} is a {@code RecordValue}
 * whose type name is {@code Nothing}. This class maps that algebra onto {@link DynValue} and back,
 * with no reflection, so the values ride elektro-Q's wire exactly as any generated message would.
 *
 * <p>Decimals round-trip through {@link BigDecimal#toString()} (their canonical form), preserving
 * scale &mdash; Pontif's exact-Decimal contract survives the wire.
 */
public final class PontifDyn {

    private static final String TUPLE = "_tuple";
    private static final String NOTHING = "Nothing";

    private PontifDyn() {}

    // --- Pontif value -> DynValue -----------------------------------------------

    /** Encodes a Pontif runtime value into its neutral {@link DynValue} form. */
    public static DynValue toDyn(Object value) {
        return switch (value) {
            case null -> DynValue.Null.INSTANCE;
            case Boolean b -> new DynValue.Bool(b);
            case Long l -> new DynValue.I64(l);
            case Integer i -> new DynValue.I64(i.longValue());
            case BigDecimal d -> new DynValue.Dec(d.toString());
            case StringValue s -> new DynValue.Str(s.content());
            case CharValue c -> new DynValue.Chr(c.codePoint());
            case RecordValue rec -> recordToDyn(rec);
            default -> throw new IllegalArgumentException(
                    "Cannot encode Pontif value of type " + value.getClass().getName());
        };
    }

    private static DynValue recordToDyn(RecordValue rec) {
        String typeName = rec.typeName();
        if (isNothing(typeName)) {
            return DynValue.Null.INSTANCE;
        }
        if (TUPLE.equals(typeName)) {
            List<DynValue> elements = new ArrayList<>(rec.members().size());
            for (Object element : rec.members().values()) {
                elements.add(toDyn(element));
            }
            return new DynValue.Seq(elements);
        }
        Map<String, DynValue> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> field : rec.members().entrySet()) {
            fields.put(field.getKey(), toDyn(field.getValue()));
        }
        return new DynValue.Struct(typeName == null ? "" : typeName, fields);
    }

    // --- DynValue -> Pontif value -----------------------------------------------

    /** Decodes a neutral {@link DynValue} back into a Pontif runtime value. */
    public static Object toPontif(DynValue value) {
        return switch (value) {
            case DynValue.Null ignored -> nothing();
            case DynValue.Bool b -> b.value();
            case DynValue.I64 i -> i.value();
            case DynValue.Dec d -> new BigDecimal(d.canonical());
            case DynValue.Str s -> new StringValue(s.value());
            case DynValue.Chr c -> new CharValue(c.codePoint());
            case DynValue.Bytes ignored -> throw new IllegalArgumentException(
                    "DynValue.Bytes has no Pontif runtime counterpart");
            case DynValue.Seq seq -> seqToTuple(seq);
            case DynValue.Struct struct -> structToRecord(struct);
        };
    }

    private static RecordValue seqToTuple(DynValue.Seq seq) {
        Map<String, Object> members = new LinkedHashMap<>();
        int i = 0;
        for (DynValue element : seq.elements()) {
            members.put("_" + i++, toPontif(element));
        }
        return new RecordValue(TUPLE, members);
    }

    private static RecordValue structToRecord(DynValue.Struct struct) {
        Map<String, Object> members = new LinkedHashMap<>();
        for (Map.Entry<String, DynValue> field : struct.fields().entrySet()) {
            members.put(field.getKey(), toPontif(field.getValue()));
        }
        return new RecordValue(struct.typeName(), members);
    }

    private static RecordValue nothing() {
        return new RecordValue(NOTHING, new LinkedHashMap<>());
    }

    /** Whether a record type name denotes {@code Nothing} (bare or module-qualified). */
    private static boolean isNothing(String typeName) {
        return typeName != null && (typeName.equals(NOTHING) || typeName.endsWith("/" + NOTHING));
    }
}

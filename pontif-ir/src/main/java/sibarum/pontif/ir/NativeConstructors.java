package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RuntimeCheckException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The native <b>constructor</b> registry: builtin value types expose their
 * anatomy as a declared field signature plus a construct map onto a native
 * carrier. Distinct from the (future) native <em>function</em> registry by
 * contract type, deliberately:
 * <ul>
 *   <li>A constructor entry carries a <b>total-exact contract</b> — every
 *       well-sorted field tuple denotes a carrier value, with no rounding
 *       mode, no policy, <em>no lossy path expressible in the contract's
 *       type</em>. Loss must be performed in user-space arithmetic, where
 *       the conservation ledger sees it. Construction is pairwise
 *       recoverable (like {@code +}: the result plus either argument
 *       determines the other), though not injective — many
 *       (unscaled, scale) pairs denote one Decimal; projection returns the
 *       <b>canonical</b> anatomy.</li>
 *   <li>A native function entry (when the first real tenant arrives) will
 *       carry an <b>axiomatic conservation summary</b> — a trust-me
 *       assertion, fail-closed-able. Keeping the registries separate keeps
 *       a lossy operation from masquerading as a constructor.</li>
 * </ul>
 *
 * <p>Construction rides the record substrate: {@code Decimal(25, 1)} parses
 * to an {@link IrExpr.Record} whose typeName is registered here, so the
 * construction gate, narrowing, and the conservation drafter (a Construction
 * node — the ruled taxonomy) see an ordinary named construction. The runtime
 * (both engines) routes registered names through {@link Entry#construct}
 * instead of building a RecordValue.
 *
 * <p>Deliberately <b>not</b> registered as declared types in {@link sibarum.pontif.types.TypeCatalog}
 * (answered there only by lookup fallback): these names are
 * nominal-only (ruled 2026-06-06 — an anonymous {@code {unscaled=…, scale=…}}
 * never matches {@code [Decimal]}), and the carrier is a scalar, so nothing
 * may flatten the anatomy into per-field atoms or match it structurally.
 * Projection/destructure (the bijection's other half) is the next slice.
 */
public final class NativeConstructors {

    /** One native constructor: declared shape + the construct half of the bijection. */
    public record Entry(IrSort.Structural shape, Construct construct) {
        public String name() { return shape.name(); }
    }

    @FunctionalInterface
    public interface Construct {
        /** Field values in declared order → carrier value. Total over well-sorted inputs. */
        Object apply(Object[] fields, Origin origin);
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    static {
        // Decimal(unscaled:Decimal, scale:Int) = unscaled × 10^-scale. The
        // unscaled field is Decimal (ruled 2026-06-06): an Int there promotes
        // (the lossless embedding) and there is NO Decimal→Int channel
        // anywhere — projection returns the canonical anatomy, whose unscaled
        // is a scale-0 integer-valued Decimal. The anatomy is RECURSIVE with
        // scale-0 values as self-fixpoints (x.unscaled.unscaled ==
        // x.unscaled). Negative scale allowed (Decimal(25, -1) = 250). Naming
        // (unscaled, scale) is provisional: revisit after Strings (docs/TODO.md).
        Map<String, IrSort> decimalFields = new LinkedHashMap<>();
        decimalFields.put("unscaled", IrSort.named("Decimal"));
        decimalFields.put("scale", IrSort.named("Int"));
        register(new Entry(
                new IrSort.Structural("Decimal", decimalFields, Origin.NONE),
                (fields, origin) -> {
                    BigDecimal unscaled = requireDecimal(fields[0], "Decimal", "unscaled", origin);
                    long scale = requireInt(fields[1], "Decimal", "scale", origin);
                    if (scale != (int) scale) {
                        throw new RuntimeCheckException(
                                "Decimal scale " + scale + " is outside the representable range",
                                origin);
                    }
                    try {
                        // Exact always: scaleByPowerOfTen only shifts the scale.
                        return unscaled.scaleByPowerOfTen((int) -scale);
                    } catch (ArithmeticException outOfRange) {
                        throw new RuntimeCheckException(
                                "Decimal scale " + scale + " (against unscaled scale "
                                        + unscaled.scale() + ") is outside the representable range",
                                origin);
                    }
                }));
    }

    private NativeConstructors() {}

    private static void register(Entry entry) {
        ENTRIES.put(entry.name(), entry);
    }

    public static boolean has(String name) {
        return name != null && ENTRIES.containsKey(name);
    }

    public static Entry get(String name) {
        return ENTRIES.get(name);
    }

    private static long requireInt(Object v, String type, String field, Origin origin) {
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        throw new RuntimeCheckException(
                "Construction claim violated: '" + type + "." + field + "' = " + v
                        + " is not an Int",
                origin);
    }

    /** A Decimal-sorted field: BigDecimal directly, or an Int via the lossless embedding. */
    private static BigDecimal requireDecimal(Object v, String type, String field, Origin origin) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Long l) return BigDecimal.valueOf(l);
        if (v instanceof Integer i) return BigDecimal.valueOf(i.longValue());
        throw new RuntimeCheckException(
                "Construction claim violated: '" + type + "." + field + "' = " + v
                        + " is not a Decimal",
                origin);
    }
}

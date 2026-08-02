package sibarum.pontif.runtime.module;

import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The native behind {@code pontif.orchestra}. {@link #conduct} runs the Conductor loop on the calling (main)
 * thread — the same blocking-native pattern as a GUI {@code window(...)} — synthesizing a {@code Tick} event
 * each cadence and firing it through {@link NativeCalls.Context#fireEvent}, which folds the program's matching
 * conduit and routes its {@code emit}s to their sinks. Conduit state persists across ticks in the one live
 * interpreter behind {@code ctx}.
 */
public final class OrchestraBridge {

    private OrchestraBridge() {
    }

    /** The fully-qualified type of the clock event; conduits match it by its bare name {@code Tick}. */
    private static final String TICK_TYPE = "pontif.orchestra/Tick";

    /** {@code conduct(ticks, period)}: fire {@code ticks} Tick beats at a {@code period}-ms cadence. */
    public static Object conduct(List<Object> args, NativeCalls.Context ctx) {
        long ticks = longArg(args, 0, 1);
        long periodMillis = Math.max(0, longArg(args, 1, 500));
        long startNanos = System.nanoTime();
        for (long n = 1; n <= ticks; n++) {
            sleep(periodMillis);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            ctx.fireEvent(tick(n, elapsedMillis));
        }
        return new IrInterpreter.DriveResult();
    }

    /** Builds a {@code Tick(n, elapsed)} event record. */
    private static RecordValue tick(long n, long elapsedMillis) {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("n", n);                                   // Int → Long
        members.put("elapsed", BigDecimal.valueOf(elapsedMillis));   // Decimal → BigDecimal
        return new RecordValue(TICK_TYPE, members);
    }

    private static long longArg(List<Object> args, int i, long def) {
        if (i >= args.size()) {
            return def;
        }
        Object v = args.get(i);
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof BigDecimal d) {
            return d.longValue();
        }
        return def;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

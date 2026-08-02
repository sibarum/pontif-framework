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

    /**
     * {@code conduct(ticks, cadence)}: seat a Tick <b>clock Player</b> — one that fires {@code ticks} Tick beats
     * then retires — on a fresh {@link Conductor} at the given {@link #periodNanos Cadence}, and run it on the
     * main thread. Firing the beats through a Player on the generic Conductor (rather than a bespoke loop) is
     * what lets the graphics render Player seat on the same scheduler (orchestration slice 2b, bullet 2). The
     * headless conductor realizes {@code Fixed(dt)} (beats {@code dt} ms apart) and {@code Eager} (no wait);
     * {@code Vsync}/{@code Retained} pace to a display/event source only a windowed Conductor owns and are
     * rejected here.
     */
    public static Object conduct(List<Object> args, NativeCalls.Context ctx) {
        long ticks = longArg(args, 0, 1);
        long periodNanos = periodNanos(args.size() > 1 ? args.get(1) : null);
        new Conductor().seat(clock(ticks, ctx), periodNanos).run();
        return new IrInterpreter.DriveResult();
    }

    /**
     * A Tick clock as a {@link Player}: each tick fires the next {@code Tick(n, elapsed)} beat, retiring once
     * {@code ticks} beats have been fired. {@code elapsed} is milliseconds since the first tick (the Conductor's
     * {@code nowNanos} is the clock, so a headless {@code Fixed}/{@code Eager} cadence and a real display cadence
     * both time the same way).
     */
    private static Player clock(long ticks, NativeCalls.Context ctx) {
        return new Player() {
            private long fired = 0;
            private long startNanos = Long.MIN_VALUE;

            @Override
            public boolean tick(long nowNanos) {
                if (fired >= ticks) {
                    return false;   // all beats fired — retire
                }
                if (startNanos == Long.MIN_VALUE) {
                    startNanos = nowNanos;
                }
                fired++;
                ctx.fireEvent(tickEvent(fired, (nowNanos - startNanos) / 1_000_000L));
                return fired < ticks;
            }
        };
    }

    /**
     * The per-tick period (ns) a {@code Cadence} value asks for on the headless conductor: {@code Fixed(dt)} →
     * {@code dt} ms, {@code Eager} → {@code 0} (eager, every pass). A missing/bare cadence defaults to
     * {@code Fixed(500)}; {@code Vsync} and {@code Retained} need a windowed Conductor (a swapchain / event
     * source) and are refused honestly rather than silently degraded.
     */
    private static long periodNanos(Object cadence) {
        if (!(cadence instanceof RecordValue rec)) {
            return 500L * 1_000_000L;
        }
        String kind = bareType(rec);
        return switch (kind) {
            case "Fixed" -> Math.max(0, longMember(rec, "dt", 500)) * 1_000_000L;
            case "Eager" -> 0L;
            case "Vsync", "Retained" -> throw new IllegalArgumentException(
                    "the headless conductor supports only Fixed(dt) and Eager; " + kind
                            + " paces to a display/event source that needs the windowed Conductor "
                            + "(orchestration slice 2b, bullet 2)");
            default -> 500L * 1_000_000L;
        };
    }

    /** Builds a {@code Tick(n, elapsed)} event record. */
    private static RecordValue tickEvent(long n, long elapsedMillis) {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("n", n);                                   // Int → Long
        members.put("elapsed", BigDecimal.valueOf(elapsedMillis));   // Decimal → BigDecimal
        return new RecordValue(TICK_TYPE, members);
    }

    /** The bare (unqualified) type name of a record value — {@code pontif.orchestra/Fixed} → {@code Fixed}. */
    private static String bareType(RecordValue rec) {
        String name = rec.typeName();
        if (name == null) {
            return "";
        }
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    /** A record's {@code Int} member as a long (arrives boxed as {@code Long}, or a {@code Decimal}). */
    private static long longMember(RecordValue rec, String field, long def) {
        Object v = rec.members().get(field);
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof BigDecimal d) {
            return d.longValue();
        }
        return def;
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
}

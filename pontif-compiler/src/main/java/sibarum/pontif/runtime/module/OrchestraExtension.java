package sibarum.pontif.runtime.module;

import sibarum.pontif.ir.NativeCalls;

import java.util.Map;

/**
 * The builtin <b>orchestration extension</b> ({@code pontif.orchestra}) — the Conductor: a cooperative
 * main-thread scheduler that ticks conduits (Players) on a cadence and lets their {@code emit}s reach effect
 * sinks (Instruments). First cut: the {@code conduct} native fires synthetic {@code Tick} events on a fixed
 * cadence, driving the program's {@code Tick} conduit. Reuses the existing conduit-fold + emit-routing machinery
 * ({@code IrInterpreter.fireEvent}) with no changes to the core — the Conductor supplies only the clock, which
 * nothing in the runtime provided before (conduits advanced only on GUI input and GPU completions).
 */
public final class OrchestraExtension implements Extension {

    public static final OrchestraExtension INSTANCE = new OrchestraExtension();

    private OrchestraExtension() {
    }

    @Override
    public String moduleName() {
        return "pontif.orchestra";
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        return Map.of("conduct", OrchestraBridge::conduct);
    }
}

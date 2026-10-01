package sibarum.pontif.host;

import dev.vexelray.framework.api.FrameStage;
import dev.vexelray.framework.shell.AppInfo;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Wiring;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.CompiledProgram;

/**
 * A compiled Pontif program as a framework application - the wiring a processor would generate, written by
 * hand because the program is only known at run time (the lane names are a property of the {@code .ptf}, not
 * of the Java build).
 *
 * <ul>
 *   <li>{@code MODEL}: the lanes' transport is built on the application's bus, and registered for shutdown.</li>
 *   <li>{@code TREE}: the program's top level and {@code main} run, which seats the conductors (each becomes a
 *       placement) and builds whatever widget tree {@code main} describes. Buildable with no window, which is
 *       what lets a headless test run a whole program through {@code VexelApplication.tree}.</li>
 *   <li>{@code ATTACH}: the main conductor is served every frame in {@code FrameStage.APP}, and a message sent
 *       to it from another thread wakes the loop. The framework then starts every lane together.</li>
 * </ul>
 *
 * <p>The program's {@code window} native does not yet mount into this application: it still opens a loop of
 * its own, so a GUI program runs through its own launcher until the Anybox window is hosted here.
 */
public final class PontifWiring extends Wiring {

    private final AppInfo info;
    private final CompiledProgram program;
    private FrameworkLaneTransport lanes;

    public PontifWiring(AppInfo info, CompiledProgram program) {
        this.info = info;
        this.program = program;
    }

    @Override
    public AppInfo info() {
        return info;
    }

    /** The transport the program's lanes run on; null before {@code MODEL}. */
    public FrameworkLaneTransport lanes() {
        return lanes;
    }

    @Override
    public void model(Shell shell) {
        lanes = shell.disposer().register(new FrameworkLaneTransport(shell.bus(), shell::place));
    }

    @Override
    public void tree(Shell shell) {
        new IrInterpreter(program.simplifier()).laneTransport(() -> lanes).eval(program.module());
    }

    @Override
    public void attach(Shell shell) {
        shell.wake(lanes);
        shell.hooks().add(FrameStage.APP, lanes.mainDrain());
    }
}

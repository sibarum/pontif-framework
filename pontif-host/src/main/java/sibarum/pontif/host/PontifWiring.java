package sibarum.pontif.host;

import dev.vexelray.framework.api.FrameStage;
import dev.vexelray.framework.shell.AppInfo;
import dev.vexelray.framework.shell.Placements;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Wiring;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.CompiledProgram;

import java.util.function.Consumer;

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
 * <p>A program's {@code window} call does not open a loop: the loop is the framework's. A GUI extension hands
 * the wiring a {@code beforeMain} that installs its window host, and {@code window} then mounts the program's
 * tree into the application's own {@code Gui}.
 */
public final class PontifWiring extends Wiring {

    private final AppInfo info;
    private final CompiledProgram program;
    private final Consumer<Shell> beforeMain;
    private FrameworkLaneTransport lanes;

    public PontifWiring(AppInfo info, CompiledProgram program) {
        this(info, program, shell -> { });
    }

    /**
     * @param beforeMain runs in {@code TREE}, before the program's {@code main}, with the application's
     *                   {@code Shell}: the place a GUI extension installs the host its {@code window} call
     *                   mounts into, without this module knowing that a GUI toolkit exists
     */
    public PontifWiring(AppInfo info, CompiledProgram program, Consumer<Shell> beforeMain) {
        this.info = info;
        this.program = program;
        this.beforeMain = beforeMain;
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
        lanes = shell.disposer().register(new FrameworkLaneTransport(shell.bus(), lane -> Placements.of(shell, lane)));
    }

    @Override
    public void tree(Shell shell) {
        beforeMain.accept(shell);
        new IrInterpreter(program.simplifier()).laneTransport(() -> lanes).eval(program.module());
    }

    @Override
    public void attach(Shell shell) {
        shell.wake(lanes);
        shell.hooks().add(FrameStage.APP, lanes.mainDrain());
    }
}

package sibarum.pontif.host;

import dev.vexelray.framework.automation.Driver;
import dev.vexelray.framework.shell.AppInfo;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Wiring;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.CompiledProgram;
import sibarum.pontif.runtime.module.OrchestraBridge;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A compiled Pontif program as a framework application - the wiring a processor would generate, written by
 * hand because the program is only known at run time (the lane names are a property of the {@code .ptf}, not
 * of the Java build).
 *
 * <ul>
 *   <li>{@code CONFIG}: the program's lanes are seated on the logic tick, one inbox for {@code main} and one
 *       per conductor seated {@code over thread}, where generated wiring makes its components. The conductor
 *       graph is fixed at compile time, so every inbox exists before the first tick (T1.3, T6.1).</li>
 *   <li>{@code TREE}: the program's top level and {@code main} run, on the thread building the tree, and build
 *       whatever widget tree {@code main} describes. No tick runs yet, so {@code main} folds its own events
 *       inline and what it sends a conductor waits for the first tick. Buildable with no window, which is what
 *       lets a headless test run a whole program through {@code VexelApplication.tree}.</li>
 *   <li>{@code ATTACH}: the driving socket, bound only if the launch asked for one ({@code --automation}), as the
 *       generated wiring of an application naming {@code AutomationStarter} does.</li>
 * </ul>
 *
 * <p>From then on no Pontif code runs on the main thread (T7.1): every handler, {@code main}'s lane included,
 * runs in the logic tick, and what reaches the program from the GUI (a click, an edit, a beat) is a message.
 *
 * <p>A program's {@code window} call does not open a loop: the loop is the framework's. A GUI extension hands
 * the wiring a {@code beforeMain} that installs its window host, and {@code window} then mounts the program's
 * tree into the application's own {@code Gui}.
 */
public final class PontifWiring extends Wiring {

    private final AppInfo info;
    private final CompiledProgram program;
    private final BiConsumer<Shell, FrameworkLaneTransport> beforeMain;
    private final Consumer<PontifBusGraph> declares;
    private FrameworkLaneTransport lanes;

    public PontifWiring(AppInfo info, CompiledProgram program) {
        this(info, program, (shell, lanes) -> { });
    }

    /**
     * @param beforeMain runs in {@code TREE}, before the program's {@code main}, with the application's
     *                   {@code Shell}: the place a GUI extension installs the host its {@code window} call
     *                   mounts into, without this module knowing that a GUI toolkit exists; handed the lanes too,
     *                   so the host's commands leave through them ({@link FrameworkLaneTransport#outlet})
     */
    public PontifWiring(AppInfo info, CompiledProgram program, BiConsumer<Shell, FrameworkLaneTransport> beforeMain) {
        this(info, program, beforeMain, graph -> { });
    }

    /**
     * @param declares adds what the extension's host contributes to the message graph (its view, its Instruments'
     *                 topics), beside the lanes this wiring declares itself
     */
    public PontifWiring(AppInfo info, CompiledProgram program, BiConsumer<Shell, FrameworkLaneTransport> beforeMain,
                        Consumer<PontifBusGraph> declares) {
        this.info = info;
        this.program = program;
        this.beforeMain = beforeMain;
        this.declares = declares;
    }

    /**
     * The program's message graph: its lanes, and whatever the extension declared. Known before anything is built,
     * because the conductor graph is fixed when the program compiles.
     */
    @Override
    public String busGraph() {
        PontifBusGraph graph = new PontifBusGraph(program.module().threadedConductors(),
                FrameworkLaneTransport.DEFAULT_CAPACITY);
        declares.accept(graph);
        return graph.text();
    }

    @Override
    public AppInfo info() {
        return info;
    }

    /** The transport the program's lanes run on; null before {@code CONFIG}. */
    public FrameworkLaneTransport lanes() {
        return lanes;
    }

    @Override
    public void config(Shell shell) {
        lanes = new FrameworkLaneTransport(program.module().threadedConductors(), shell);
    }

    @Override
    public void tree(Shell shell) {
        beforeMain.accept(shell, lanes);
        // conduct() paces on this application's loop instead of blocking one of its own; cleared with the
        // application so a later run in the same JVM meets the headless Conductor again.
        OrchestraBridge.host(new FrameworkTickHost(shell));
        shell.disposer().register(() -> OrchestraBridge.host(null));
        new IrInterpreter(program.simplifier()).laneTransport(() -> lanes).eval(program.module());
    }

    @Override
    public void attach(Shell shell) {
        shell.disposer().register(Driver.open(shell));
    }
}

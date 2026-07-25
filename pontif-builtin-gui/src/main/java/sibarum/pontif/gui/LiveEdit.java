package sibarum.pontif.gui;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.input.FocusState;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.text.FontGroups;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.dasum.gui.core.ui.Ui;
import sibarum.dasum.gui.mathtext.LaidOut;
import sibarum.dasum.gui.mathtext.MathBox;
import sibarum.dasum.gui.mathtext.MathConstants;
import sibarum.dasum.gui.mathtext.MathLayout;
import sibarum.dasum.gui.mathtext.MathMarkup;
import sibarum.dasum.gui.mathtext.MathOgl;
import sibarum.dasum.gui.vis.plot.Axis;
import sibarum.dasum.gui.vis.plot.LinePlot;
import sibarum.dasum.gui.vis.plot.PlotFrame;
import sibarum.dasum.gui.vis.plot.PlotStyle;
import sibarum.dasum.gui.vis.plot.PlotView;
import sibarum.dasum.gui.vis.plot.Series;
import sibarum.dasum.gui.vis.scene.Layer;
import sibarum.dasum.gui.vis.scene.SceneStates;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.NativeCalls;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static sibarum.pontif.gui.GuiShared.*;
import static sibarum.pontif.gui.MathText.*;
import static sibarum.pontif.gui.ReliableSeries.*;

/**
 * Interactive live-editing windows (docs/plotting.md, docs/mathtext.md): an editable expression field
 * over a reliable plot + typeset title ({@code plotInput}), and an editable field over a live-typeset
 * equation ({@code markupInput}) — each re-parses on a debounce and re-publishes lock-free, keeping the
 * last good render on a parse error. Plus the static-render markup window. Split out of {@code DasumBridge}.
 */
final class LiveEdit {
    private LiveEdit() {}

    private static final Color ERR_COLOR = new Color(0.95f, 0.45f, 0.45f, 1f);

    // --- Interactive expression input: type an expression, plot updates live ---------------------

    static Component plotInputRoot(String initial, NativeCalls.Context ctx) {
        Component.SceneView plotSV = plotSceneView();
        PlotView plotView = new PlotView(plotSV);
        Component.SceneView titleSV = (Component.SceneView) Ui.sceneView()
                .background(PLOT_BG).height(Em.of(3f)).grow(0).interactive(false).build();
        PlotView titleView = new PlotView(titleSV);

        // content, fontGroup, fontSize, color, width, height, padding, wrapWidth, clip,
        // interactive, selectable, editable, acceptsTab, flexGrow
        Component.Text input = new Component.Text(initial, FontGroups.DEFAULT, Em.of(1.3f), TEXT,
                null, null, Em.of(0.4f), null, true, true, true, true, false, 1);
        Component.Text status = new Component.Text("", FontGroups.DEFAULT, Em.of(0.95f), ERR_COLOR,
                null, null, Em.of(0.4f), null, true, false, false, false, false, 0);
        Component label = new Component.Text("f(x) =", FontGroups.DEFAULT, Em.of(1.2f), TEXT,
                null, null, Em.of(0.4f), null, true, false, false, false, false, 0);

        Debouncer debounce = new Debouncer(280);
        TextStates.onContentChange(input,
                txt -> debounce.submit(() -> updatePlotInput(txt, plotView, titleView, status, ctx)));
        updatePlotInput(initial, plotView, titleView, status, ctx);   // initial render
        FocusState.set(input);

        Component inputRow = Ui.row().padding(Em.of(0.5f)).gap(Em.of(0.5f)).align(AlignItems.CENTER)
                .add(label).add(input).add(status).build();
        return Ui.column().fill().add(inputRow).add(titleSV).add(plotSV).build();
    }

    /** Re-parse {@code text} and, if valid, re-publish the reliable plot + typeset title; otherwise keep
     *  the last good plot and mark the field. Runs off the GLFW thread (debounce) — publishes lock-free. */
    private static void updatePlotInput(String text, PlotView plotView, PlotView titleView,
                                        Component.Text status, NativeCalls.Context ctx) {
        Optional<RecordValue> parsed = ExprParser.parse(text);
        if (parsed.isEmpty()) {
            TextStates.setContent(status, text == null || text.isBlank() ? "" : "cannot parse");
            return;
        }
        RecordValue e = parsed.get();
        try {
            List<Series> series = sampleReliableJava(e, ctx);
            if (series.isEmpty()) { TextStates.setContent(status, ""); return; }
            plotView.showLinePlot(LinePlot.autoFrame(0f, 0f, 10f, 5.5f, series), series, PlotStyle.defaults());
            MathConstants mc = MathConstants.stixTwoMath();
            LaidOut laid = new MathLayout(mathAtlas(), mc).layout(algExprToMathBox(e));
            float w = (float) Math.max(1e-3, laid.width()), h = (float) Math.max(1e-3, laid.ascent() + laid.descent());
            List<Layer> tl = MathOgl.toLayers(laid, mc, TEXT, 1f, 0f, 0f, /*yUp*/ true);
            titleView.show(new PlotFrame(0f, 0f, w, h, Axis.linear(0, w), Axis.linear(0, h)), tl);
            TextStates.setContent(status, "");
        } catch (RuntimeException ex) {
            TextStates.setContent(status, "cannot plot");
        }
    }

    /** A single-slot debounce: each {@link #submit} cancels the pending task and schedules a new one, so
     *  only the last keystroke in a burst fires (on a daemon worker thread). */
    private static final class Debouncer {
        private final long delayMs;
        private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pontif-plot-debounce");
            t.setDaemon(true);
            return t;
        });
        private ScheduledFuture<?> pending;

        Debouncer(long delayMs) { this.delayMs = delayMs; }

        synchronized void submit(Runnable task) {
            if (pending != null) pending.cancel(false);
            pending = exec.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    // --- Math markup: type a notation string, typeset it (docs/mathtext.md) ----------------------

    static Component markupRenderRoot(String s) {
        MathBox box;
        try {
            box = MathMarkup.parse(s);
        } catch (MathMarkup.MarkupError e) {
            return errorLabel("can't parse markup: " + e.getMessage());
        }
        Component math = mathBoxComponent(box);
        Component button = Themed.button("Export SVG", Em.of(11f), Variant.PRIMARY, 0,
                () -> PlotSvg.writeMarkupSvgDialog(s));
        Component buttonRow = Ui.row().justify(sibarum.dasum.gui.core.component.JustifyContent.CENTER)
                .padding(Em.of(0.4f)).add(button).build();
        return Ui.column().fill().grow(1).gap(Em.of(0.2f)).padding(Em.of(0.3f)).add(math).add(buttonRow).build();
    }

    static Component markupInputRoot(String initial) {
        // grow(1) so the math viewport takes the space below the input row (a fill sceneview with
        // grow=0 in a column collapses — the zero-area lint rule).
        Component.SceneView mathSV = (Component.SceneView) Ui.sceneView().background(PLOT_BG).grow(1).build();
        PlotView mathView = new PlotView(mathSV);
        MathConstants mc = MathConstants.stixTwoMath();
        LaidOut[] last = {null};                           // the current good render, re-fit on resize
        SceneStates.onViewportResize(mathSV, px -> { if (last[0] != null) showMathFitted(mathView, mathSV, last[0], mc); });

        Component.Text input = new Component.Text(initial, FontGroups.DEFAULT, Em.of(1.3f), TEXT,
                null, null, Em.of(0.4f), null, true, true, true, true, false, 1);
        Component.Text status = new Component.Text("", FontGroups.DEFAULT, Em.of(0.95f), ERR_COLOR,
                null, null, Em.of(0.4f), null, true, false, false, false, false, 0);

        Debouncer debounce = new Debouncer(220);
        TextStates.onContentChange(input,
                txt -> debounce.submit(() -> updateMarkup(txt, mathView, mathSV, status, last, mc)));
        updateMarkup(initial, mathView, mathSV, status, last, mc);   // initial render
        FocusState.set(input);

        Component inputRow = Ui.row().padding(Em.of(0.5f)).gap(Em.of(0.5f)).align(AlignItems.CENTER)
                .add(input).add(status).build();
        return Ui.column().fill().add(inputRow).add(mathSV).build();
    }

    /** Re-typeset {@code text}; on a parse error keep the last good render and mark the field. Runs off
     *  the GLFW thread (debounce) — {@link PlotView#show} publishes lock-free. */
    private static void updateMarkup(String text, PlotView mathView, Component.SceneView mathSV,
                                     Component.Text status, LaidOut[] last, MathConstants mc) {
        if (text == null || text.isBlank()) { TextStates.setContent(status, ""); return; }
        try {
            LaidOut laid = new MathLayout(mathAtlas(), mc).layout(MathMarkup.parse(text));
            last[0] = laid;
            showMathFitted(mathView, mathSV, laid, mc);
            TextStates.setContent(status, "");
        } catch (MathMarkup.MarkupError e) {
            TextStates.setContent(status, "cannot parse");
        }
    }
}

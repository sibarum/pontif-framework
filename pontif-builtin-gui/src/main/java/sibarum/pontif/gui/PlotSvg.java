package sibarum.pontif.gui;

import sibarum.dasum.gui.core.dialog.FileDialog;
import sibarum.dasum.gui.mathtext.LaidOut;
import sibarum.dasum.gui.mathtext.MathConstants;
import sibarum.dasum.gui.mathtext.MathLayout;
import sibarum.dasum.gui.mathtext.MathSvg;
import sibarum.dasum.gui.vis.plot.PlotFrame;
import sibarum.dasum.gui.vis.plot.SvgPlotWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static sibarum.pontif.gui.ChartBuilder.*;
import static sibarum.pontif.gui.GuiShared.*;
import static sibarum.pontif.gui.MathText.*;

/**
 * SVG export for plots and math (docs/plotting.md): serialises the shared {@link sibarum.dasum.gui.vis.plot.PlotScene2D}
 * IR (via dasum's {@link SvgPlotWriter}) — including the typeset math title band, with the subset STIX
 * Two Math font embedded so the file renders true anywhere — and pops the native Save dialog. Split
 * out of the former god-class {@code DasumBridge}; runs on the GLFW thread (where NFD lives).
 */
final class PlotSvg {
    private PlotSvg() {}

    /** Serialise a chart to a semantic SVG and pop a native Save dialog to write it — shared by the
     *  {@code exportSvg} native and the standalone chart window's Export button. Must run on the GLFW
     *  thread (where NFD lives). No-op when nothing is drawable or the dialog is cancelled. */
    static void writeChartSvgDialog(AnnotatedChart chart) {
        String svg = titledChartSvg(chart);
        if (svg == null) {
            System.err.println("exportSvg: no drawable layers — nothing to export.");
            return;
        }
        Optional<Path> dest = FileDialog.save(null,
                List.of(FileDialog.Filter.of("SVG image", "svg")), null, "plot.svg");
        if (dest.isEmpty()) return;
        Path p = dest.get();
        if (!p.getFileName().toString().toLowerCase().endsWith(".svg")) {
            p = p.resolveSibling(p.getFileName() + ".svg");       // ensure the .svg extension
        }
        try {
            Files.writeString(p, svg);
        } catch (IOException e) {
            System.err.println("exportSvg: could not write " + p + ": " + e.getMessage());
        }
    }

    /** Pixel height of the math-title band in the exported SVG (mirrors the on-screen title bar). */
    private static final double EXPORT_TITLE_BAND = 96.0;

    /**
     * Serialise a chart to SVG <b>including the typeset math title</b> when the chart carries an
     * expression AST — the title band stacked above the plot, exactly like the window. Both the title
     * ({@link MathSvg}) and the plot ({@link SvgPlotWriter}) are emitted as nested {@code <svg>}s in
     * one document, so each keeps its own coordinate system and class-styled markup. Returns just the
     * plot SVG when there's no title, or {@code null} when nothing is drawable. Package-visible test seam.
     */
    static String titledChartSvg(AnnotatedChart chart) {
        PlotFrame frame = annotatedFrame(chart);
        if (frame == null) return null;
        String plotSvg = SvgPlotWriter.write(buildScene(chart, frame), WIDTH, HEIGHT);
        List<TitledExpr> titles = chart.titles();
        if (titles.isEmpty()) return plotSvg;

        double pad = 12.0, outerW = WIDTH, outerH = EXPORT_TITLE_BAND + HEIGHT, bandH = EXPORT_TITLE_BAND - 2 * pad;
        // Lay out each equation and give it a horizontal slot proportional to its width (a gap between),
        // wrapping each nested title svg in a `<g>` whose CSS color drives its `currentColor` fill — so
        // the exported title colour-codes to the curves, exactly like the on-screen title.
        MathConstants mc = MathConstants.stixTwoMath();
        List<LaidOut> laids = new ArrayList<>();
        double totalW = 0;
        for (int i = 0; i < titles.size(); i++) {
            LaidOut laid = new MathLayout(mathAtlas(), mc).layout(algExprToMathBox(titles.get(i).ast()));
            laids.add(laid);
            totalW += (i == 0 ? 0 : TITLE_GAP_EM) + laid.width();
        }
        double usableW = outerW - 2 * pad;
        StringBuilder placedTitle = new StringBuilder();
        double xoff = 0;
        for (int i = 0; i < laids.size(); i++) {
            LaidOut laid = laids.get(i);
            double slotW = usableW * (laid.width() / totalW);
            double slotX = pad + usableW * (xoff / totalW);
            String eqSvg = placeSvg(MathSvg.write(laid, 48.0), slotX, pad, slotW, bandH);
            placedTitle.append("<g style=\"color:").append(hex(titles.get(i).color())).append("\">")
                       .append(eqSvg).append("</g>\n");
            xoff += laid.width() + TITLE_GAP_EM;
        }
        String placedPlot = placeSvg(plotSvg, 0, EXPORT_TITLE_BAND, outerW, HEIGHT);
        // Embed the (subset) STIX Two Math font so the title renders true on any machine — the SVG is
        // fully self-contained. The @font-face defines the family the nested title svg references.
        String fontFace = mathFontFace();
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "
                + fmt(outerW) + " " + fmt(outerH) + "\">\n" + fontFace + placedTitle
                + placedPlot + "\n</svg>\n";
    }

    /** Wrap an SVG-document fragment as a positioned, aspect-preserving nested {@code <svg>} by
     *  injecting {@code x/y/width/height} + {@code preserveAspectRatio} into its root tag. */
    private static String placeSvg(String svg, double x, double y, double w, double h) {
        int at = svg.indexOf("<svg") + 4;
        String attrs = " x=\"" + fmt(x) + "\" y=\"" + fmt(y) + "\" width=\"" + fmt(w) + "\" height=\"" + fmt(h)
                + "\" preserveAspectRatio=\"xMidYMid meet\"";
        return svg.substring(0, at) + attrs + svg.substring(at);
    }

    /** Typeset a markup string and pop a Save dialog. No-op when the markup doesn't parse or the dialog
     *  is cancelled. */
    static void writeMarkupSvgDialog(String markup) {
        String svg = markupSvg(markup);
        if (svg == null) { System.err.println("exportMarkupSvg: markup doesn't parse: " + markup); return; }
        Optional<Path> dest = FileDialog.save(null,
                List.of(FileDialog.Filter.of("SVG image", "svg")), null, "math.svg");
        if (dest.isEmpty()) return;
        Path p = dest.get();
        if (!p.getFileName().toString().toLowerCase().endsWith(".svg")) {
            p = p.resolveSibling(p.getFileName() + ".svg");
        }
        try { Files.writeString(p, svg); }
        catch (IOException e) { System.err.println("exportMarkupSvg: could not write " + p + ": " + e.getMessage()); }
    }
}

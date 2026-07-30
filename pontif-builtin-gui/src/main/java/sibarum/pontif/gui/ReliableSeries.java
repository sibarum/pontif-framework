package sibarum.pontif.gui;

import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.vis.plot.PlotScene2D;
import sibarum.dasum.gui.vis.plot.Series;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.NativeCalls;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static sibarum.pontif.gui.GuiShared.*;

/**
 * Reliable interval-enclosure plotting (docs/reliable-plotting.md): turns per-column classified
 * {@code Span}s into asymptote-safe line series (each maximal run of curve columns → one clipped
 * polyline, poles aimed off-frame, dense poles filled), computes the robust y-range, builds the
 * enclosure band, and replays the whole Pontif pipeline from Java (via the {@code evalInterval}
 * native) so a live edit can re-sample without re-running Pontif. Split out of {@code DasumBridge}.
 */
final class ReliableSeries {
    private ReliableSeries() {}

    /** A classified column: kind 0 = curve span {@code [lo,hi]}, 1 = isolated pole (a single
     *  asymptote — a break the curve blows through), 2 = empty (a domain-edge break), 3 = dense pole
     *  (unresolvable detail — a full-height fill block). The isolated-vs-dense split is decided
     *  Pontif-side by a subdivision probe (pontif.plot's {@code classifyColumn}), not a run length. */
    record ReliableSpan(int kind, double lo, double hi) {}

    /** Parse the Pontif {@code {spans}} tuple of {@code Span(kind,lo,hi)} records, in column order.
     *  Package-visible: the headless test seam. */
    static List<ReliableSpan> parseSpans(Object spansValue) {
        List<ReliableSpan> out = new ArrayList<>();
        if (spansValue instanceof RecordValue tuple) {
            for (Object m : tuple.members().values()) {
                if (m instanceof RecordValue s && "Span".equals(bareType(s.typeName()))) {
                    out.add(new ReliableSpan((int) Math.round(memberD(s, "kind")),
                            memberD(s, "lo"), memberD(s, "hi")));
                }
            }
        }
        return out;
    }

    /**
     * Turn per-column interval spans into readable line series over {@code [xlo, xhi]}. Each maximal
     * run of consecutive CURVE columns becomes ONE connected polyline through the column midpoints
     * (clamped to the viewport) — so the plot reads as a curve, not a field of vertical dashes.
     * A break (an empty column, or an isolated pole — kind 1) ends the current polyline; the curve
     * resumes as a fresh one on the far side — so an asymptote is a clean gap, never a line drawn
     * across it. A DENSE pole (kind 3 — unresolvable detail) still fills as a full-height bar. The
     * y-range is a ROBUST 2nd–98th percentile of the bounded columns so a near-pole spike can't
     * flatten the plot. Package-visible test seam.
     */
    static List<Series> buildReliableSeries(double xlo, double xhi, Object spansValue) {
        return buildReliableSeries(xlo, xhi, parseSpans(spansValue), SERIES_COLOR);
    }

    /** As above, in a chosen colour — so several overlaid reliable plots ({@code expr(e), expr(r)})
     *  each draw in their own palette colour instead of a single indistinguishable cyan. */
    static List<Series> buildReliableSeries(double xlo, double xhi, Object spansValue, Color color) {
        return buildReliableSeries(xlo, xhi, parseSpans(spansValue), color);
    }

    /** From already-parsed spans, default colour — the seam the interactive Java sampler feeds directly. */
    static List<Series> buildReliableSeries(double xlo, double xhi, List<ReliableSpan> spans) {
        return buildReliableSeries(xlo, xhi, spans, SERIES_COLOR);
    }

    /** As above, from already-parsed spans in a chosen {@code color}, with a per-expression robust
     *  y-range. */
    static List<Series> buildReliableSeries(double xlo, double xhi, List<ReliableSpan> spans, Color color) {
        return buildReliableSeries(xlo, xhi, spans, color,
                spans.isEmpty() ? new double[]{-1, 1} : robustRange(reliableBounds(spans)));
    }

    /** The guaranteed {@code [lo, hi]} values of every bounded (curve) column — the sample set the
     *  robust y-range is taken over. */
    static List<Double> reliableBounds(List<ReliableSpan> spans) {
        List<Double> bounds = new ArrayList<>();
        for (ReliableSpan s : spans) if (s.kind() == 0) { bounds.add(s.lo()); bounds.add(s.hi()); }
        return bounds;
    }

    /**
     * As above, but clipped and pole-aimed to an EXPLICIT y-range {@code yr = [ymin, ymax]}. Several
     * overlaid reliable plots pass ONE shared range here (computed across all of them), so every curve
     * blows up to the SAME top/bottom at its poles — otherwise each would stop at its own robust band
     * (a tall function reaching the frame edge while a flatter one halts halfway up).
     */
    static List<Series> buildReliableSeries(double xlo, double xhi, List<ReliableSpan> spans,
                                            Color color, double[] yr) {
        int n = spans.size();
        if (n == 0) return List.of();
        double ymin = yr[0], ymax = yr[1];
        double dx = (xhi - xlo) / n;
        double beyond = ymax - ymin;                 // how far past an edge to aim an ∞-bound point
        List<Series> out = new ArrayList<>();
        List<Double> runX = new ArrayList<>(), runY = new ArrayList<>();   // UN-clamped polyline points
        ReliableSpan lastCurve = null;               // last curve column added to the current run
        double poleBeforeX = Double.NaN;             // x of a pole immediately preceding this run, if any
        for (int i = 0; i < n; i++) {
            ReliableSpan s = spans.get(i);
            double x = xlo + (i + 0.5) * dx;
            if (s.kind() == 0) {                                 // curve → a point on the polyline
                if (!Double.isNaN(poleBeforeX) && runX.isEmpty()) {
                    // this run starts right after a pole → the curve comes FROM ±∞: begin off the
                    // edge so the clip draws it entering at the frame boundary.
                    runX.add(poleBeforeX);
                    runY.add(blowSign(s) >= 0 ? ymax + beyond : ymin - beyond);
                }
                runX.add(x);
                runY.add((s.lo() + s.hi()) / 2.0);               // true midpoint; clipped, not clamped
                lastCurve = s;
                poleBeforeX = Double.NaN;
                continue;
            }
            // A pole (kind 1 isolated OR kind 3 dense) is a proven blow-up: the line keeps going, so
            // aim it off the edge and let the clip draw it TO the boundary. An empty (Undefined, kind
            // 2) column is a domain edge — the curve genuinely stops, so it is left as a plain break.
            boolean pole = s.kind() == 1 || s.kind() == 3;
            if (pole && lastCurve != null && !runX.isEmpty()) {
                runX.add(x);
                runY.add(blowSign(lastCurve) >= 0 ? ymax + beyond : ymin - beyond);
            }
            clipRunToBand(runX, runY, ymin, ymax, out, color);
            runX.clear();
            runY.clear();
            lastCurve = null;
            poleBeforeX = pole ? x : Double.NaN;                 // only a pole makes the next run enter from ∞
            if (s.kind() == 3) {                                 // dense pole → fill (the block)
                out.add(Series.line(new double[]{x, x}, new double[]{ymin, ymax}, color));
            }
        }
        clipRunToBand(runX, runY, ymin, ymax, out, color);
        return out;
    }

    /** Which way a curve column adjacent to a pole is blowing up: the sign of its larger-magnitude
     *  enclosure endpoint (positive → toward the top edge, negative → toward the bottom). */
    private static double blowSign(ReliableSpan s) {
        return (Math.abs(s.hi()) >= Math.abs(s.lo()) ? s.hi() : s.lo()) >= 0 ? 1.0 : -1.0;
    }

    /**
     * Clip a polyline to the horizontal band {@code [ymin, ymax]} and emit the in-band pieces as
     * series. Where a segment crosses an edge the crossing point is interpolated (correct slope),
     * so a curve heading off-screen reaches the frame edge and stops — no pile-up along the edge
     * (the "serif" the clamp-to-viewport version produced). Off-screen stretches draw nothing.
     */
    private static void clipRunToBand(List<Double> xs, List<Double> ys,
                                      double ymin, double ymax, List<Series> out, Color color) {
        List<Double> cx = new ArrayList<>(), cy = new ArrayList<>();
        for (int i = 0; i < xs.size(); i++) {
            double x = xs.get(i), y = ys.get(i);
            boolean in = y >= ymin && y <= ymax;
            if (i > 0) {
                double px = xs.get(i - 1), py = ys.get(i - 1);
                boolean pin = py >= ymin && py <= ymax;
                if (pin && !in) {                                // exiting the band → cut at the edge
                    double edge = y > ymax ? ymax : ymin;
                    cx.add(px + (edge - py) * (x - px) / (y - py));
                    cy.add(edge);
                    flushPts(cx, cy, out, color);
                } else if (!pin && in) {                         // entering → start at the edge
                    double edge = py > ymax ? ymax : ymin;
                    cx.add(px + (edge - py) * (x - px) / (y - py));
                    cy.add(edge);
                } else if (!pin && ((py < ymin && y > ymax) || (py > ymax && y < ymin))) {
                    double e1 = py < ymin ? ymin : ymax;         // crosses the whole band (very steep)
                    double e2 = py < ymin ? ymax : ymin;
                    cx.add(px + (e1 - py) * (x - px) / (y - py)); cy.add(e1);
                    cx.add(px + (e2 - py) * (x - px) / (y - py)); cy.add(e2);
                    flushPts(cx, cy, out, color);
                }
            }
            if (in) { cx.add(x); cy.add(y); }
        }
        flushPts(cx, cy, out, color);
    }

    /** Emit the accumulated points as one polyline (if it has ≥ 2 of them) and reset the buffer. */
    private static void flushPts(List<Double> xs, List<Double> ys, List<Series> out, Color color) {
        if (xs.size() >= 2) {
            double[] ax = new double[xs.size()], ay = new double[ys.size()];
            for (int i = 0; i < ax.length; i++) { ax[i] = xs.get(i); ay[i] = ys.get(i); }
            out.add(Series.line(ax, ay, color));
        }
        xs.clear();
        ys.clear();
    }

    /** {@code [ymin, ymax]} from the 2nd/98th percentile of {@code vals}, padded 5% — robust to
     *  near-pole spikes. An empty set (no bounded columns) yields a default unit window. */
    static double[] robustRange(List<Double> vals) {
        if (vals.isEmpty()) return new double[]{-1, 1};
        List<Double> s = new ArrayList<>(vals);
        Collections.sort(s);
        double lo = percentile(s, 0.02), hi = percentile(s, 0.98);
        if (hi <= lo) { double m = (lo + hi) / 2; lo = m - 1; hi = m + 1; }
        double pad = (hi - lo) * 0.05;
        return new double[]{lo - pad, hi + pad};
    }

    private static double percentile(List<Double> sorted, double p) {
        int i = (int) Math.round(p * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /** Build the reliable enclosure band from an {@code ExprLayer}'s spans: each bounded (curve)
     *  column contributes its guaranteed {@code [lo, hi]} at the column midpoint. Pole/empty columns
     *  are gaps (skipped). Returns {@code null} when there are &lt; 2 bounded columns. */
    static PlotScene2D.EnclosureBand enclosureBand(double xlo, double xhi, Object spansValue) {
        return enclosureBand(xlo, xhi, parseSpans(spansValue));
    }

    /** As above, from already-parsed spans (the re-sampled path). */
    static PlotScene2D.EnclosureBand enclosureBand(double xlo, double xhi, List<ReliableSpan> spans) {
        int n = spans.size();
        if (n == 0) return null;
        double dx = (xhi - xlo) / n;
        List<Double> xs = new ArrayList<>(), lo = new ArrayList<>(), hi = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ReliableSpan s = spans.get(i);
            if (s.kind() != 0) continue;                       // only bounded columns bound the curve
            xs.add(xlo + (i + 0.5) * dx);
            lo.add(s.lo());
            hi.add(s.hi());
        }
        if (xs.size() < 2) return null;
        return new PlotScene2D.EnclosureBand(toArray(xs), toArray(lo), toArray(hi));
    }

    // --- Java replay of the Pontif reliable pipeline (live edit / shared-window re-sampling) -------

    /** Sample an {@code AlgExpr} into reliable line series entirely in Java: auto-frame + per-column
     *  interval enclosure (the {@code evalInterval} native) + {@link #buildReliableSeries}. This is the
     *  Pontif reliable pipeline replayed from Java so a live edit can re-plot without re-running Pontif. */
    static List<Series> sampleReliableJava(RecordValue ast, NativeCalls.Context ctx) {
        double[] win = autoFrameJava(ast, ctx);
        double xlo = win[0], xhi = win[1], dx = (xhi - xlo) / 256.0;
        List<ReliableSpan> spans = new ArrayList<>(256);
        for (int i = 0; i < 256; i++) spans.add(classifyJava(ast, xlo + i * dx, xlo + (i + 1) * dx, ctx));
        return buildReliableSeries(xlo, xhi, spans);
    }

    /** Re-sample an expression's reliable interval enclosure across {@code [xlo, xhi]} (256 columns),
     *  entirely in Java via the {@code evalInterval} native — the same per-column classification the
     *  Pontif {@code expr(...)} runs, replayed over a chosen window so overlaid plots share one frame. */
    static List<ReliableSpan> resampleReliable(RecordValue ast, double xlo, double xhi,
                                               NativeCalls.Context ctx) {
        double dx = (xhi - xlo) / 256.0;
        List<ReliableSpan> spans = new ArrayList<>(256);
        for (int i = 0; i < 256; i++) spans.add(classifyJava(ast, xlo + i * dx, xlo + (i + 1) * dx, ctx));
        return spans;
    }

    /** Java mirror of pontif.plot's autoFrame: scan 256 probe columns over [-32,32], widening to the
     *  span of features (a pole, or a sign flip between real columns); fall back to [-10,10].
     *  Package-visible so a multi-curve plot can UNION each expression's window and resample all
     *  curves over the shared frame (via {@link #resampleReliable}). */
    static double[] autoFrameJava(RecordValue ast, NativeCalls.Context ctx) {
        double prevReal = 7, lo = 1e6, hi = -1e6;
        boolean any = false;
        for (int i = 0; i < 256; i++) {
            double xa = -32.0 + i * 0.25;
            double s = colSignJava(ast, xa, xa + 0.25, ctx);
            boolean pole = s == 2.0;
            boolean feature = pole || (prevReal < 1.5 && s < 1.5 && prevReal != s);
            if (feature) { lo = Math.min(lo, xa); hi = Math.max(hi, xa); any = true; }
            if (s < 1.5) prevReal = s;                       // carry the last real sign forward
        }
        if (!any) return new double[]{-10.0, 10.0};
        double pad = Math.max((hi - lo) * 0.15, 1.0);
        return new double[]{lo - pad, hi + pad};
    }

    /** The reliable core's colSign in Java: -1/0/1 from the enclosure midpoint, 2 = pole, 7 = undefined. */
    private static double colSignJava(RecordValue ast, double xa, double xb, NativeCalls.Context ctx) {
        return switch (encKind(ast, xa, xb, ctx)) {
            case 0 -> Math.signum((intervalLo + intervalHi) / 2.0);
            case 1 -> 2.0;
            default -> 7.0;
        };
    }

    private static ReliableSpan classifyJava(RecordValue ast, double xa, double xb, NativeCalls.Context ctx) {
        return switch (encKind(ast, xa, xb, ctx)) {
            case 0 -> new ReliableSpan(0, intervalLo, intervalHi);
            case 1 -> new ReliableSpan(1, 0.0, 0.0);         // pole
            default -> new ReliableSpan(2, 0.0, 0.0);        // undefined
        };
    }

    // encKind stashes the bounded interval's endpoints here for the caller (single-threaded per call).
    private static double intervalLo, intervalHi;

    /** Evaluate the interval enclosure of {@code ast} over [xa,xb] via the {@code evalInterval} native;
     *  returns 0 = bounded (endpoints in {@link #intervalLo}/{@link #intervalHi}), 1 = Unbounded,
     *  2 = Undefined. */
    private static int encKind(RecordValue ast, double xa, double xb, NativeCalls.Context ctx) {
        Object enc = evalIntervalNative().call(
                List.of(ast, BigDecimal.valueOf(xa), BigDecimal.valueOf(xb)), ctx);
        if (enc instanceof RecordValue r) {
            switch (bareType(r.typeName())) {
                case "Interval" -> { intervalLo = memberD(r, "lo"); intervalHi = memberD(r, "hi"); return 0; }
                case "Unbounded" -> { return 1; }
                default -> { return 2; }
            }
        }
        return 2;
    }

    private static NativeCalls.NativeCall evalIntervalNative;
    private static synchronized NativeCalls.NativeCall evalIntervalNative() {
        if (evalIntervalNative == null) evalIntervalNative = NativeCalls.get("pontif.algebra/evalInterval");
        return evalIntervalNative;
    }
}

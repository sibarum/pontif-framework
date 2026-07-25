package sibarum.pontif.gui;

import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.text.AtlasData;
import sibarum.dasum.gui.core.ui.Ui;
import sibarum.dasum.gui.mathtext.LaidOut;
import sibarum.dasum.gui.mathtext.MathBox;
import sibarum.dasum.gui.mathtext.MathConstants;
import sibarum.dasum.gui.mathtext.MathLayout;
import sibarum.dasum.gui.mathtext.MathMarkup;
import sibarum.dasum.gui.mathtext.MathOgl;
import sibarum.dasum.gui.mathtext.MathSvg;
import sibarum.dasum.gui.vis.plot.Axis;
import sibarum.dasum.gui.vis.plot.PlotFrame;
import sibarum.dasum.gui.vis.plot.PlotView;
import sibarum.dasum.gui.vis.scene.Layer;
import sibarum.dasum.gui.vis.scene.SceneStates;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static sibarum.pontif.gui.GuiShared.*;

/**
 * Math typesetting (docs/mathtext.md): the {@code AlgExpr → MathBox} front-end (precedence-correct
 * tree-walk of a plotted algebraic AST into the semantic MathBox IR), the ASCII-markup path, a
 * contained/centered live viewport, the config-as-data {@link MathConstants} seam, the embedded-font
 * SVG helper, and the lazily-loaded math atlas. Split out of the former god-class {@code DasumBridge}.
 */
final class MathText {
    private MathText() {}

    /** An {@code @font-face} {@code <style>} embedding the subset STIX Two Math (base64 woff2), or
     *  empty when the resource is absent (then the export references the family by name). */
    static String mathFontFace() {
        String b64 = mathFontBase64();
        return b64.isEmpty() ? "" :
                "<style>@font-face{font-family:\"STIX Two Math\";src:url(data:font/woff2;base64,"
                        + b64 + ") format(\"woff2\");}</style>\n";
    }

    private static String mathFontBase64;   // cached
    private static synchronized String mathFontBase64() {
        if (mathFontBase64 == null) {
            try (InputStream in = MathText.class.getResourceAsStream("/dasum/fonts/STIXTwoMath-subset.woff2")) {
                mathFontBase64 = in == null ? "" : java.util.Base64.getEncoder().encodeToString(in.readAllBytes());
            } catch (IOException e) {
                mathFontBase64 = "";
            }
        }
        return mathFontBase64;
    }

    /** A viewport typesetting a {@link MathBox} tree y-up, the equation contained and centered; it
     *  re-fits when the window resizes. The markup counterpart of the chart math title. */
    static Component mathBoxComponent(MathBox box) {
        MathConstants mc = MathConstants.stixTwoMath();
        LaidOut laid = new MathLayout(mathAtlas(), mc).layout(box);
        Component.SceneView view = (Component.SceneView) Ui.sceneView().background(PLOT_BG).grow(1).build();
        PlotView pv = new PlotView(view);
        SceneStates.onViewportResize(view, px -> showMathFitted(pv, view, laid, mc));
        showMathFitted(pv, view, laid, mc);
        return view;
    }

    /**
     * Publish a laid-out equation CONTAINED in {@code sv}'s viewport (centered, never overflowing).
     * {@link PlotView} fits a frame by height with width following the viewport aspect — right for a
     * plot, but a short wide equation would spill past the sides. So the framed region is padded out
     * to the viewport's aspect ratio (with a margin) around the equation, making the fit contain both
     * axes. Reads the live viewport size, so it must be re-run on resize.
     */
    static void showMathFitted(PlotView view, Component.SceneView sv, LaidOut laid, MathConstants mc) {
        List<Layer> layers = MathOgl.toLayers(laid, mc, TEXT, 1f, 0f, 0f, /*yUp*/ true);
        double w = Math.max(1e-3, laid.width()), h = Math.max(1e-3, laid.ascent() + laid.descent());
        SceneStates.ViewportPx px = SceneStates.viewportPxOf(sv);
        double aspect = (px != null && px.width() > 0 && px.height() > 0)
                ? (double) px.width() / px.height() : (double) WIDTH / HEIGHT;
        double margin = 1.25;
        double fw = Math.max(w, h * aspect) * margin;     // frame aspect == viewport aspect, so a
        double fh = Math.max(h, w / aspect) * margin;     // height-fit also fits the width
        float x0 = (float) ((w - fw) / 2), y0 = (float) ((h - fh) / 2);
        float x1 = (float) (x0 + fw), y1 = (float) (y0 + fh);
        view.show(new PlotFrame(x0, y0, x1, y1, Axis.linear(x0, x1), Axis.linear(y0, y1)), layers);
    }

    /** Typeset a markup string to a self-contained SVG (subset STIX Two Math embedded), or {@code null}
     *  if the markup doesn't parse. */
    static String markupSvg(String markup) {
        MathBox box;
        try { box = MathMarkup.parse(markup); } catch (MathMarkup.MarkupError e) { return null; }
        LaidOut laid = new MathLayout(mathAtlas(), MathConstants.stixTwoMath()).layout(box);
        String inner = MathSvg.write(laid, 48.0);
        String fontFace = mathFontFace();
        // Splice the @font-face into the equation SVG so the file renders true anywhere.
        return fontFace.isEmpty() ? inner : inner.replaceFirst("(?s)(<svg[^>]*>\\n?)", "$1" + fontFace);
    }

    /** Whether a record is a recognised {@code AlgExpr} node (so we don't try to typeset e.g. a
     *  {@code Nothing} sentinel as a title). */
    static boolean isAlgExprNode(RecordValue r) {
        return switch (bareType(r.typeName())) {
            case "Add", "Sub", "Mul", "Div", "Pow", "Sin", "Cos", "Tan", "Exp", "Log", "Const", "Param" -> true;
            default -> false;
        };
    }

    /**
     * Read a math-style config record (a {@code requires $…} data module, docs/plotting.md) into the
     * dasum {@link MathConstants} the typesetter consumes — the seam between "config as data" and the
     * engine. Fields are read by name; any omitted field falls back to the STIX Two Math default, so a
     * user config may override only the values they care about. Package-visible test seam.
     */
    static MathConstants mathConstantsFrom(RecordValue cfg) {
        MathConstants d = MathConstants.stixTwoMath();
        if (cfg == null) return d;
        String fontGroup = cfg.members().get("fontGroup") instanceof StringValue s ? s.content() : d.fontGroup();
        return new MathConstants(
                memberOr(cfg, "scriptScale", d.scriptScale()),
                memberOr(cfg, "scriptScriptScale", d.scriptScriptScale()),
                memberOr(cfg, "axisHeight", d.axisHeight()),
                memberOr(cfg, "fractionRuleThickness", d.fractionRuleThickness()),
                memberOr(cfg, "fractionGapNum", d.fractionGapNum()),
                memberOr(cfg, "fractionGapDen", d.fractionGapDen()),
                memberOr(cfg, "superscriptShiftUp", d.superscriptShiftUp()),
                memberOr(cfg, "subscriptShiftDown", d.subscriptShiftDown()),
                memberOr(cfg, "scriptGapAfter", d.scriptGapAfter()),
                memberOr(cfg, "radicalRuleThickness", d.radicalRuleThickness()),
                memberOr(cfg, "radicalGapAbove", d.radicalGapAbove()),
                memberOr(cfg, "radicalKernBefore", d.radicalKernBefore()),
                memberOr(cfg, "radicalKernAfter", d.radicalKernAfter()),
                memberOr(cfg, "spaceBinaryOp", d.spaceBinaryOp()),
                memberOr(cfg, "spaceRelation", d.spaceRelation()),
                memberOr(cfg, "spacePunct", d.spacePunct()),
                memberOr(cfg, "functionGap", d.functionGap()),
                memberOr(cfg, "delimiterPad", d.delimiterPad()),
                fontGroup);
    }

    // --- AlgExpr → MathBox: typeset an algebraic AST (docs/plotting.md) ---------------------------
    // The `AlgExpr` front-end of the math typesetter: a precedence-correct tree-walk from the same
    // AST we plot into the semantic MathBox IR, which then lays out + renders to SVG/OGL. Div →
    // fraction, Pow → superscript (or a radical for the ½ power), Mul → juxtaposition (a middot only
    // before a number, so a coefficient·power reads as `7x`, not `7·x`, but 2·3 doesn't become `23`),
    // functions → an upright name + parenthesised argument. Parentheses are added by precedence.

    /** Operator precedence for parenthesization: higher binds tighter. Div is a fraction (visually
     *  grouped), so it and the atoms never need parens around their children. */
    private static int mathPrec(RecordValue n) {
        return switch (bareType(n.typeName())) {
            case "Add", "Sub" -> 1;
            case "Mul" -> 2;
            case "Pow" -> 4;
            default -> 5;                       // Const / Param / Div / Sin… — atomic or self-grouping
        };
    }

    /** Translate an {@code AlgExpr} AST value into a {@link MathBox}. Package-visible test seam. */
    static MathBox algExprToMathBox(RecordValue ast) {
        return mathConv(ast);
    }

    /** Convert, wrapping in parentheses when the node binds looser than the surrounding context. */
    private static MathBox mathConvP(Object node, int minPrec) {
        MathBox b = mathConv(node);
        return (node instanceof RecordValue rv && mathPrec(rv) < minPrec) ? MathBox.paren(b) : b;
    }

    private static MathBox mathConv(Object node) {
        if (!(node instanceof RecordValue r)) return MathBox.num(String.valueOf(node));
        Map<String, Object> m = r.members();
        return switch (bareType(r.typeName())) {
            case "Const" -> MathBox.num(fmt(memberD(r, "value")));
            case "Param" -> MathBox.var(str(r, "name"));
            case "Add" -> MathBox.row(mathConvP(m.get("left"), 1), MathBox.op("+"), mathConvP(m.get("right"), 1));
            case "Sub" -> MathBox.row(mathConvP(m.get("left"), 1), MathBox.op("−"), mathConvP(m.get("right"), 2));
            case "Mul" -> mathMul(m.get("left"), m.get("right"));
            case "Div" -> MathBox.frac(mathConv(m.get("left")), mathConv(m.get("right")));
            case "Pow" -> mathPow(m.get("base"), m.get("exponent"));
            case "Sin" -> mathFunc("sin", m.get("arg"));
            case "Cos" -> mathFunc("cos", m.get("arg"));
            case "Tan" -> mathFunc("tan", m.get("arg"));
            case "Log" -> mathFunc("log", m.get("arg"));
            case "Exp" -> MathBox.pow(MathBox.sym("e"), mathConv(m.get("arg")));
            default -> MathBox.num("?");
        };
    }

    private static MathBox mathMul(Object l, Object r) {
        MathBox lb = mathConvP(l, 2), rb = mathConvP(r, 2);
        boolean rightIsNumber = r instanceof RecordValue rv && "Const".equals(bareType(rv.typeName()));
        return rightIsNumber ? MathBox.row(lb, MathBox.op("·"), rb) : MathBox.row(lb, rb);
    }

    private static MathBox mathPow(Object base, Object exp) {
        if (exp instanceof RecordValue ev && "Const".equals(bareType(ev.typeName()))
                && Math.abs(memberD(ev, "value") - 0.5) < 1e-9) {
            return MathBox.sqrt(mathConv(base));              // x^(1/2) → √x
        }
        return MathBox.pow(mathConvP(base, 5), mathConv(exp));
    }

    private static MathBox mathFunc(String name, Object arg) {
        return MathBox.row(MathBox.fn(name), MathBox.paren(mathConv(arg)));
    }

    /** The math-font atlas metrics for layout (lazy classpath load; same atlas the FontGroup uses). */
    private static AtlasData mathAtlas;
    static synchronized AtlasData mathAtlas() {
        if (mathAtlas == null) mathAtlas = AtlasData.loadFromResource("/dasum/atlas/math.json");
        return mathAtlas;
    }

    /** Typeset an {@code AlgExpr} AST to an SVG string. Package-visible test seam. */
    static String mathSvg(RecordValue ast) {
        LaidOut laid = new MathLayout(mathAtlas(), MathConstants.stixTwoMath())
                .layout(algExprToMathBox(ast));
        return MathSvg.write(laid, 48.0);
    }
}

package sibarum.pontif.gui;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AlgExpr front-end of the math typesetter: a reflected algebraic function's AST is translated to
 * the MathBox IR and rendered to SVG (String -> AST -> Formatted, over the same AST we plot). Verifies
 * the translation + SVG headlessly; the on-screen OGL render is manual.
 */
class MathRenderTest {

    /** Reflect the rational function and capture its AlgExpr AST via a stubbed exportMathSvg. */
    private static RecordValue rationalAst() {
        Extensions.install(new PlotExtension());
        RecordValue[] captured = new RecordValue[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            captured[0] = (RecordValue) args.get(0);
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("exportMathSvg", stub);
        NativeCalls.register("pontif.plot/exportMathSvg", stub);
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compile("""
                        requires pontif.algebra.{Algebraic}
                        requires pontif.plot.{exportMathSvg}
                        function f(x:Decimal):Decimal -> (7*x^4 - 5*x^3 + 2*x^2 - 11*x + 3) / (13*x^3 - 5*x^2)
                        assign proof f:Algebraic
                        exportMathSvg($f[Decimal].ast)""", "math.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "program should run; got " + r.text());
        assertNotNull(captured[0], "the AST should have reached the native");
        return captured[0];
    }

    @Test
    void algExprToMathBox_topLevelIsAFraction() {
        // The whole rational function is a Div → the typeset root is a Fraction.
        MathBoxProbe probe = MathBoxProbe.of(MathText.algExprToMathBox(rationalAst()));
        assertTrue(probe.isFraction(), "the rational expression typesets as a fraction at the root");
    }

    @Test
    void mathSvg_isWellFormed_andTypesetsFractionAndScripts() {
        String svg = MathText.mathSvg(rationalAst());
        System.out.println("=== math SVG (rational function) ===\n" + svg);   // captured for preview
        assertDoesNotThrow(() -> DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8))),
                () -> "typeset math SVG must be well-formed:\n" + svg);
        assertTrue(svg.contains("<text"), "glyph runs render as <text>");
        assertTrue(svg.contains("<rect"), "the fraction bar renders as a <rect> rule");
        assertTrue(svg.contains("class=\"math\""), "root is classed");
    }

    @Test
    void markupSvg_typesetsNotation_selfContained() {
        // A markup string exercising a fraction, a superscript, a root and a Greek symbol.
        String svg = MathText.markupSvg("(x^2 + 1)/sqrt(pi)");
        assertNotNull(svg, "valid markup should render");
        assertDoesNotThrow(() -> DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8))),
                () -> "markup SVG must be well-formed:\n" + svg);
        assertTrue(svg.contains("<rect"), "the fraction bar / vinculum render as <rect> rules");
        assertTrue(svg.contains("√"), "the radical surd is emitted");
        assertTrue(svg.contains("π"), "the Greek symbol is emitted");
        assertTrue(svg.contains("@font-face"), "the STIX subset is embedded — the SVG is self-contained");
    }

    @Test
    void markupSvg_returnsNull_onMalformedMarkup() {
        assertNull(MathText.markupSvg("(x^2"), "unbalanced input doesn't render");
    }

    /** Minimal reflective peek at a MathBox's kind without exposing dasum types across the test. */
    private record MathBoxProbe(Object box) {
        static MathBoxProbe of(Object box) { return new MathBoxProbe(box); }
        boolean isFraction() { return box.getClass().getSimpleName().equals("Fraction"); }
    }
}

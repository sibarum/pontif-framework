package sibarum.pontif.gui;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Headless verification of the plotting extension (docs/plotting.md V1). A user struct satisfies
 * the {@code Curve2D} shape trait; {@code plotLine} dispatches on the trait and samples the curve
 * <b>in Pontif</b> (range synthesis + map + {@code c.at} method dispatch), handing the numeric
 * aggregates to the native {@code renderCurve}. The test overrides {@code renderCurve} with a
 * capturing stub, so it exercises the full sampling pipeline — trait dispatch, the Pontif sampling
 * loop, and the stream→aggregate boundary crossing — <b>without opening a window</b>. The actual
 * chart render is verified manually:
 * {@code mvn -pl pontif-builtin-gui -am exec:exec -Dptf=examples/curve.ptf}.
 */
class PlotExtensionTest {

    @Test
    void plotLine_samplesCurveOverDomain_inPontif() {
        Extensions.install(new PlotExtension());

        // Override the native renderer with a capturing stub so NO window opens. The extension
        // registers renderCurve under both its bare and module-qualified names; the resolver uses
        // the qualified one, so override BOTH (last registration wins).
        double[][] captured = new double[2][];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            captured[0] = DasumBridge.doubles(args.get(0));
            captured[1] = DasumBridge.doubles(args.get(1));
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderCurve", stub);
        NativeCalls.register("pontif.plot/renderCurve", stub);

        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{Curve2D, plotLine}
                        struct Parabola()
                        assign trait Parabola:Curve2D {
                          at(x:Decimal):Decimal -> x * x
                          domain():[{Decimal,Decimal}] -> {-10.0, 10.0}
                        }
                        plotLine(Parabola())""", "curve.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "plotLine program should run; got " + r.text());
        assertNotNull(captured[0], "renderCurve should have received the xs aggregate");

        // 65 evenly-spaced samples of x over [-10, 10]; y = x^2.
        assertEquals(65, captured[0].length, "xs sample count");
        assertEquals(65, captured[1].length, "ys sample count");
        assertEquals(-10.0, captured[0][0], 1e-9, "first x");
        assertEquals(10.0, captured[0][64], 1e-9, "last x");
        assertEquals(0.0, captured[0][32], 1e-9, "midpoint x");
        assertEquals(100.0, captured[1][0], 1e-9, "y at x=-10");
        assertEquals(100.0, captured[1][64], 1e-9, "y at x=10");
        assertEquals(0.0, captured[1][32], 1e-9, "y at x=0");
    }

    @Test
    void plotCloud_shapesPointsForTheRenderer() {
        Extensions.install(new PlotExtension());

        // Capture the flattened xyz the native renderer would receive — no window opens.
        float[][] captured = new float[1][];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            captured[0] = DasumBridge.xyzTriples(args.get(0));
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderCloud", stub);
        NativeCalls.register("pontif.plot/renderCloud", stub);

        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{Cloud3D, plotCloud}
                        struct Tetra()
                        assign trait Tetra:Cloud3D {
                          points():Stream[[{Decimal,Decimal,Decimal}]] -> { {0.0,0.0,0.0}, {1.0,0.0,0.0}, {0.0,1.0,0.0}, {0.0,0.0,1.0} }
                        }
                        plotCloud(Tetra())""", "cloud.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "plotCloud program should run; got " + r.text());
        assertNotNull(captured[0], "renderCloud should have received the points aggregate");

        // 4 points → 12 floats, row-major xyz.
        assertEquals(12, captured[0].length, "flattened triple count");
        assertArrayEquals(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1}, captured[0], 1e-6f,
                "row-major xyz of the four corners");
    }

    @Test
    void plotSurface_samplesHeightGridInPontif() {
        Extensions.install(new PlotExtension());

        // Capture the row-major height grid the renderer would mesh — no window opens.
        double[][] captured = new double[1][];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            captured[0] = DasumBridge.doubles(args.get(0));
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderSurface", stub);
        NativeCalls.register("pontif.plot/renderSurface", stub);

        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{HeightMap3D, plotSurface}
                        struct Bowl()
                        assign trait Bowl:HeightMap3D {
                          at(x:Decimal, y:Decimal):Decimal -> x * x + y * y
                          domain():[{Decimal,Decimal,Decimal,Decimal}] -> {-3.0, 3.0, -3.0, 3.0}
                        }
                        plotSurface(Bowl())""", "surface.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "plotSurface program should run; got " + r.text());
        assertNotNull(captured[0], "renderSurface should have received the height grid");

        // 33x33 grid; z = x^2 + y^2 over [-3,3]^2, row-major (i -> col i%33, row i/33).
        assertEquals(1089, captured[0].length, "33x33 grid");
        assertEquals(18.0, captured[0][0], 1e-6, "corner (-3,-3) -> 18");          // i=0
        assertEquals(0.0, captured[0][16 * 33 + 16], 1e-6, "centre (0,0) -> 0");   // row 16, col 16
        assertEquals(18.0, captured[0][1088], 1e-6, "corner (3,3) -> 18");         // i=1088
    }
}

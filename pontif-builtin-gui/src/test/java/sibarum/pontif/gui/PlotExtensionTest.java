package sibarum.pontif.gui;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;
import sibarum.dasum.gui.vis.math.Vec3;
import sibarum.dasum.gui.vis.scene.BlendMode;
import sibarum.dasum.gui.vis.scene.Layer;
import sibarum.dasum.gui.vis.scene.LineLayer;
import sibarum.dasum.gui.vis.scene.PointLayer;
import sibarum.dasum.gui.vis.scene.TextLayer;
import sibarum.dasum.gui.vis.scene.VolumeLayer;
import sibarum.pontif.core.types.RecordValue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            captured[0] = GuiShared.doubles(args.get(0));
            captured[1] = GuiShared.doubles(args.get(1));
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
    void varCount_readsDimensionalityFromVariables() {
        // The north-star spine: an expression's VARIABLE COUNT chooses its plot dimensionality
        // (1 → 2D curve, 2 → surface, 3 → volume), by the x/y/z axis convention. usesVar/varCount
        // walk the closed AlgExpr union. Here x*x + y*y names x and y → dimensionality 2.
        Extensions.install(new PlotExtension());
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.algebra.{AlgExpr, Param, Add, Mul}
                        requires pontif.plot.{varCount}
                        let e:AlgExpr = Add(Mul(Param("x"), Param("x")), Mul(Param("y"), Param("y")))
                        varCount(e)""", "vars.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "varCount program should run; got " + r.text());
        assertEquals("2", r.text(), "x*x + y*y uses two spatial variables");
    }

    @Test
    void plotSurfaceExpr_gridSamplesTwoVariableExpression() {
        // Expression-driven surface: z = x*x + y*y sampled on the 33×33 grid over the default
        // [-6, 6]² box via evalSafeAt, handed to the native renderSurface. Corner i=0 is (x,y) =
        // (-6, -6) → z = 72. Captures the grid without opening a window.
        Extensions.install(new PlotExtension());
        double[][] captured = new double[1][];
        double[][] domain = new double[1][];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            captured[0] = GuiShared.doubles(args.get(0));
            domain[0] = new double[]{
                ((BigDecimal) args.get(1)).doubleValue(), ((BigDecimal) args.get(2)).doubleValue(),
                ((BigDecimal) args.get(3)).doubleValue(), ((BigDecimal) args.get(4)).doubleValue()};
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderSurface", stub);
        NativeCalls.register("pontif.plot/renderSurface", stub);

        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.algebra.{AlgExpr, Param, Add, Mul}
                        requires pontif.plot.{plotSurfaceExpr}
                        let e:AlgExpr = Add(Mul(Param("x"), Param("x")), Mul(Param("y"), Param("y")))
                        plotSurfaceExpr(e)""", "surface.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "plotSurfaceExpr program should run; got " + r.text());
        assertNotNull(captured[0], "renderSurface should have received the zs grid");
        assertEquals(1089, captured[0].length, "33×33 surface grid");
        assertArrayEquals(new double[]{-6.0, 6.0, -6.0, 6.0}, domain[0], 1e-9, "default box");
        assertEquals(72.0, captured[0][0], 1e-9, "z at the (-6,-6) corner = 36 + 36");
    }

    @Test
    void plotLine_runtimeSampleRate_choosesPointCount() {
        Extensions.install(new PlotExtension());

        double[][] captured = new double[2][];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            captured[0] = GuiShared.doubles(args.get(0));
            captured[1] = GuiShared.doubles(args.get(1));
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderCurve", stub);
        NativeCalls.register("pontif.plot/renderCurve", stub);

        // plotLine(f, 5): 5 points over [-10, 10] via the runtime `indexRange` generator (NOT the
        // 65-point static preset). step = 20/4 = 5 → x = {-10,-5,0,5,10}, y = x^2.
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{Curve2D, plotLine}
                        struct Parabola()
                        assign trait Parabola:Curve2D {
                          at(x:Decimal):Decimal -> x * x
                          domain():[{Decimal,Decimal}] -> {-10.0, 10.0}
                        }
                        plotLine(Parabola(), 5)""", "curve.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "plotLine(f, 5) program should run; got " + r.text());
        assertNotNull(captured[0], "renderCurve should have received the xs aggregate");

        assertEquals(5, captured[0].length, "chosen sample count (5), not the default 65");
        assertEquals(5, captured[1].length, "ys sample count");
        assertArrayEquals(new double[]{-10.0, -5.0, 0.0, 5.0, 10.0}, captured[0], 1e-9, "5 evenly-spaced x");
        assertArrayEquals(new double[]{100.0, 25.0, 0.0, 25.0, 100.0}, captured[1], 1e-9, "y = x^2 at each x");
    }

    @Test
    void plotLine_runtimeSampleRate_integerDomain_stillSpacesEvenly() {
        Extensions.install(new PlotExtension());

        double[][] captured = new double[2][];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            captured[0] = GuiShared.doubles(args.get(0));
            captured[1] = GuiShared.doubles(args.get(1));
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderCurve", stub);
        NativeCalls.register("pontif.plot/renderCurve", stub);

        // REGRESSION (the "ghost curve"): a domain of INTEGER bounds ({-2, 2}, as when the user
        // writes `let radius = 2`) makes `hi - lo` a Long; step = (hi-lo)/(n-1) must NOT be integer
        // division (which truncated to 0 for n >= 6, collapsing every sample onto x = lo).
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{Curve2D, plotLine}
                        struct Line()
                        assign trait Line:Curve2D {
                          at(x:Decimal):Decimal -> x
                          domain():[{Decimal,Decimal}] -> {-2, 2}
                        }
                        plotLine(Line(), 10)""", "curve.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "plotLine(f, 10) over an integer domain should run; got " + r.text());
        assertNotNull(captured[0], "renderCurve should have received the xs aggregate");

        assertEquals(10, captured[0].length, "10 samples");
        assertEquals(-2.0, captured[0][0], 1e-9, "first x = lo");
        assertEquals(2.0, captured[0][9], 1e-9, "last x = hi (NOT collapsed onto lo)");
        // The samples must actually span the domain — not all sit on lo.
        assertTrue(captured[0][9] - captured[0][0] > 3.0,
                "x range spans the domain; got [" + captured[0][0] + ", " + captured[0][9] + "]");
    }

    @Test
    void plotCloud_shapesPointsForTheRenderer() {
        Extensions.install(new PlotExtension());

        // Capture the flattened xyz the native renderer would receive — no window opens.
        float[][] captured = new float[1][];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            captured[0] = GuiShared.xyzTriples(args.get(0));
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
            captured[0] = GuiShared.doubles(args.get(0));
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

    @Test
    void scene_composesSurfaceCloudAndText_intoOneLayerList() {
        Extensions.install(new PlotExtension());

        // Capture the {layers} tuple scene(...) hands to renderScene — no window opens.
        Object[] capturedLayers = new Object[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            capturedLayers[0] = args.size() > 1 ? args.get(1) : null;
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderScene", stub);
        NativeCalls.register("pontif.plot/renderScene", stub);

        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{HeightMap3D, Cloud3D, surface, cloud, fade, text3d, scene}
                        struct Bowl()
                        assign trait Bowl:HeightMap3D {
                          at(x:Decimal, y:Decimal):Decimal -> x * x + y * y
                          domain():[{Decimal,Decimal,Decimal,Decimal}] -> {-3.0, 3.0, -3.0, 3.0}
                        }
                        struct Tetra()
                        assign trait Tetra:Cloud3D {
                          points():Stream[[{Decimal,Decimal,Decimal}]] ->
                            { {0.0,0.0,0.0}, {1.0,0.0,0.0}, {0.0,2.0,0.0} }
                        }
                        scene({title = "s"}, {
                          surface(Bowl()),
                          fade(surface(Bowl()), 0.5),
                          cloud(Tetra()),
                          text3d("peak", {0.0, 18.0, 0.0})
                        })""", "scene.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "scene program should run; got " + r.text());
        assertNotNull(capturedLayers[0], "renderScene should have received the {layers} tuple");

        SceneBuilder.SceneBuild build = SceneBuilder.buildSceneLayers(capturedLayers[0]);
        // 3 geometry layers (2 surfaces + 1 cloud) then the text label appended last.
        assertEquals(4, build.layers().size(), "surface + faded surface + cloud + text");
        assertInstanceOf(sibarum.dasum.gui.vis.scene.TriangleLayer.class, build.layers().get(0));
        assertInstanceOf(sibarum.dasum.gui.vis.scene.TriangleLayer.class, build.layers().get(1));
        assertInstanceOf(sibarum.dasum.gui.vis.scene.PointLayer.class, build.layers().get(2));
        assertInstanceOf(sibarum.dasum.gui.vis.scene.TextLayer.class, build.layers().get(3));

        // Solid surface writes depth (OPAQUE → true occlusion); the faded one is translucent.
        assertEquals(BlendMode.OPAQUE, build.layers().get(0).blend(), "solid surface is OPAQUE");
        assertEquals(BlendMode.ALPHA, build.layers().get(1).blend(), "faded surface is ALPHA");
        assertEquals(0.5f, build.layers().get(1).opacity(), 1e-6, "faded surface opacity");

        // Bounds span the surface height [0,18] over [-3,3]^2 (world Y is height).
        assertEquals(-3.0f, build.min().x(), 1e-6);
        assertEquals(18.0f, build.max().y(), 1e-6, "max height 18 (corner of the bowl)");
    }

    @Test
    void chart_overlaysMultipleCurves_withDistinctColors() {
        Extensions.install(new PlotExtension());

        Object[] capturedLayers = new Object[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            capturedLayers[0] = args.size() > 1 ? args.get(1) : null;
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderChart", stub);
        NativeCalls.register("pontif.plot/renderChart", stub);

        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{Curve2D, curve, chart}
                        struct Parabola()
                        assign trait Parabola:Curve2D {
                          at(x:Decimal):Decimal -> x * x
                          domain():[{Decimal,Decimal}] -> {-10.0, 10.0}
                        }
                        struct Line()
                        assign trait Line:Curve2D {
                          at(x:Decimal):Decimal -> 2.0 * x
                          domain():[{Decimal,Decimal}] -> {-10.0, 10.0}
                        }
                        chart({title = "two"}, { curve(Parabola()), curve(Line()) })""", "chart.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "chart program should run; got " + r.text());
        assertNotNull(capturedLayers[0], "renderChart should have received the {layers} tuple");

        var series = ChartBuilder.buildChartSeries(capturedLayers[0]);
        assertEquals(2, series.size(), "two overlaid curves → two series");
    }

    @Test
    void color_setsExplicitCurveColour_othersFallBackToPalette() {
        Extensions.install(new PlotExtension());

        Object[] capturedLayers = new Object[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            capturedLayers[0] = args.size() > 1 ? args.get(1) : null;
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderChart", stub);
        NativeCalls.register("pontif.plot/renderChart", stub);

        // First curve gets an explicit red; second is left auto (palette slot 0 = cyan).
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{Curve2D, curve, color, chart}
                        struct Parabola()
                        assign trait Parabola:Curve2D {
                          at(x:Decimal):Decimal -> x * x
                          domain():[{Decimal,Decimal}] -> {-10.0, 10.0}
                        }
                        struct Line()
                        assign trait Line:Curve2D {
                          at(x:Decimal):Decimal -> 2.0 * x
                          domain():[{Decimal,Decimal}] -> {-10.0, 10.0}
                        }
                        chart({title = "coloured"},
                              { color(curve(Parabola()), {1.0, 0.0, 0.0}), curve(Line()) })""", "chart.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "coloured chart program should run; got " + r.text());
        assertNotNull(capturedLayers[0], "renderChart should have received the {layers} tuple");

        var series = ChartBuilder.buildChartSeries(capturedLayers[0]);
        assertEquals(2, series.size(), "two overlaid curves → two series");

        // Explicit {1,0,0} honoured verbatim.
        assertEquals(1f, series.get(0).color().r(), 1e-6, "explicit red channel");
        assertEquals(0f, series.get(0).color().g(), 1e-6, "explicit green channel");
        assertEquals(0f, series.get(0).color().b(), 1e-6, "explicit blue channel");
        // Auto curve takes the FIRST palette slot (cyan), unshifted by the coloured curve before it.
        assertEquals(0.40f, series.get(1).color().r(), 1e-6, "auto curve → palette slot 0 (cyan)");
        assertEquals(0.80f, series.get(1).color().g(), 1e-6);
        assertEquals(1.00f, series.get(1).color().b(), 1e-6);
    }

    @Test
    void curve_runtimeSampleRate_flowsThroughChart() {
        Extensions.install(new PlotExtension());

        Object[] capturedLayers = new Object[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            capturedLayers[0] = args.size() > 1 ? args.get(1) : null;
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderChart", stub);
        NativeCalls.register("pontif.plot/renderChart", stub);

        // curve(f, 10) — the arity overload — inside a chart: the series carries 10 points.
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{Curve2D, curve, chart}
                        struct Parabola()
                        assign trait Parabola:Curve2D {
                          at(x:Decimal):Decimal -> x * x
                          domain():[{Decimal,Decimal}] -> {-10.0, 10.0}
                        }
                        chart({title = "coarse"}, { curve(Parabola(), 10) })""", "chart.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "chart with curve(f, 10) should run; got " + r.text());
        var series = ChartBuilder.buildChartSeries(capturedLayers[0]);
        assertEquals(1, series.size(), "one curve → one series");
        assertEquals(10, series.get(0).pointCount(), "10 samples, not the default 65");
    }

    @Test
    void axisBox_generatesWireframeTicksAndNiceLabels() {
        // World bounds of a bowl: x,z in [-3,3], height y in [0,18].
        List<Layer> layers = SceneBuilder.axisBoxLayers(new Vec3(-3f, 0f, -3f), new Vec3(3f, 18f, 3f), true);

        long lineLayers = layers.stream().filter(l -> l instanceof LineLayer).count();
        long textLayers = layers.stream().filter(l -> l instanceof TextLayer).count();
        assertTrue(lineLayers >= 2, "box wireframe + tick/grid line layers; got " + lineLayers);
        assertTrue(textLayers >= 3, "several numeric tick labels; got " + textLayers);

        // Nice-number ticks (Heckbert) over [0,18] include a "0" label, billboarded to face the camera.
        boolean zeroLabel = layers.stream().anyMatch(l ->
                l instanceof TextLayer t && t.text().equals("0") && t.billboard());
        assertTrue(zeroLabel, "expected a billboard '0' tick label");

        // Empty/degenerate bounds produce nothing (no crash).
        assertTrue(SceneBuilder.axisBoxLayers(new Vec3(0f, 0f, 0f), new Vec3(0f, 0f, 0f), true).isEmpty());
    }

    @Test
    void colormaps_areMonotoneAndNamed() {
        // Endpoints and a distinct interior — each named map differs from the legacy "cool" ramp.
        assertArrayEquals(new float[]{0f, 0f, 0f}, SceneBuilder.colorFor("grayscale", 0f), 1e-6f);
        assertArrayEquals(new float[]{1f, 1f, 1f}, SceneBuilder.colorFor("grayscale", 1f), 1e-6f);
        assertArrayEquals(new float[]{0f, 0.5f, 1f}, SceneBuilder.colorFor("cool", 0f), 1e-6f);
        assertArrayEquals(new float[]{1f, 0.5f, 0f}, SceneBuilder.colorFor("cool", 1f), 1e-6f);
        // t is clamped; an unknown name falls back to "cool".
        assertArrayEquals(SceneBuilder.colorFor("cool", 1f), SceneBuilder.colorFor("cool", 2f), 1e-6f);
        assertArrayEquals(SceneBuilder.colorFor("cool", 0.5f), SceneBuilder.colorFor("bogus", 0.5f), 1e-6f);
        // viridis/turbo span dark→bright (green-ness rises), distinct from cool.
        float[] vlo = SceneBuilder.colorFor("viridis", 0f), vhi = SceneBuilder.colorFor("viridis", 1f);
        assertTrue(vhi[1] > vlo[1], "viridis brightens (green rises) low→high");
    }

    @Test
    void scene_withSurface_reportsColorbarKey() {
        Extensions.install(new PlotExtension());
        Object[] capturedLayers = new Object[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            capturedLayers[0] = args.size() > 1 ? args.get(1) : null;
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderScene", stub);
        NativeCalls.register("pontif.plot/renderScene", stub);

        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{HeightMap3D, surface, cmap, scene}
                        struct Bowl()
                        assign trait Bowl:HeightMap3D {
                          at(x:Decimal, y:Decimal):Decimal -> x * x + y * y
                          domain():[{Decimal,Decimal,Decimal,Decimal}] -> {-3.0, 3.0, -3.0, 3.0}
                        }
                        scene({title = "s"}, { cmap(surface(Bowl()), "viridis") })""", "cmap.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "cmap scene should run; got " + r.text());
        SceneBuilder.SceneBuild build = SceneBuilder.buildSceneLayers(capturedLayers[0]);
        assertNotNull(build.bar(), "a surface scene has a colorbar key");
        assertEquals("viridis", build.bar().colormap(), "cmap selected viridis");
        assertEquals(0.0, build.bar().lo(), 1e-6, "min height 0");
        assertEquals(18.0, build.bar().hi(), 1e-6, "max height 18");
    }

    @Test
    void wireAndFineResolution_addWireframeAndDenserGrid() {
        Extensions.install(new PlotExtension());
        Object[] capturedLayers = new Object[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            capturedLayers[0] = args.size() > 1 ? args.get(1) : null;
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderScene", stub);
        NativeCalls.register("pontif.plot/renderScene", stub);

        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{HeightMap3D, surfaceFine, wire, scene}
                        struct Bowl()
                        assign trait Bowl:HeightMap3D {
                          at(x:Decimal, y:Decimal):Decimal -> x * x + y * y
                          domain():[{Decimal,Decimal,Decimal,Decimal}] -> {-3.0, 3.0, -3.0, 3.0}
                        }
                        scene({title = "wire"}, { wire(surfaceFine(Bowl())) })""", "wire.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "wire(surfaceFine(...)) should run; got " + r.text());
        SceneBuilder.SceneBuild build = SceneBuilder.buildSceneLayers(capturedLayers[0]);
        // A wired surface yields TWO layers: the triangle mesh + its wireframe line overlay.
        assertEquals(2, build.layers().size(), "surface + wireframe");
        assertInstanceOf(sibarum.dasum.gui.vis.scene.TriangleLayer.class, build.layers().get(0));
        assertInstanceOf(LineLayer.class, build.layers().get(1));

        // surfaceFine samples a 65×65 grid → (65-1)^2 cells × 2 triangles.
        var tri = (sibarum.dasum.gui.vis.scene.TriangleLayer) build.layers().get(0);
        assertEquals(64 * 64 * 2, tri.triangleCount(), "65x65 grid → 8192 triangles");
    }

    @Test
    void volume_colorsByGradientAxis_asRaymarchedVolume() {
        Extensions.install(new PlotExtension());
        Object[] capturedLayers = new Object[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            capturedLayers[0] = args.size() > 1 ? args.get(1) : null;
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderScene", stub);
        NativeCalls.register("pontif.plot/renderScene", stub);

        // f = x  → gradient (1,0,0) everywhere → every voxel lights the RED channel only.
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{Volume3D, volume, scene}
                        struct Ramp()
                        assign trait Ramp:Volume3D {
                          at(x:Decimal, y:Decimal, z:Decimal):Decimal -> x
                          domain():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] ->
                            {-1.0, 1.0, -1.0, 1.0, -1.0, 1.0}
                        }
                        scene({title = "v"}, { volume(Ramp()) })""", "vol.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "volume program should run; got " + r.text());
        SceneBuilder.SceneBuild build = SceneBuilder.buildSceneLayers(capturedLayers[0]);
        assertEquals(1, build.layers().size(), "one volumetric layer");
        Layer l = build.layers().get(0);
        assertInstanceOf(VolumeLayer.class, l);
        assertEquals(BlendMode.ADDITIVE, l.blend(), "volume accumulates emissively (additive)");

        VolumeLayer vol = (VolumeLayer) l;
        assertEquals(24, vol.nx(), "24^3 sampling grid");
        assertEquals(24L * 24 * 24 * 4, vol.rgba().length, "dense RGBA voxels");
        // f=x → gradient (1,0,0): every voxel's colour is red (direction), with positive density.
        float[] g = vol.rgba();
        assertTrue(g[0] > 0f, "red channel = x-gradient direction");
        assertEquals(0f, g[1], 1e-6f, "no y-gradient → green off");
        assertEquals(0f, g[2], 1e-6f, "no z-gradient → blue off");
        assertTrue(g[3] > 0f, "density/alpha lit by the gradient magnitude");
    }

    @Test
    void volumeWithNormals_addsGradientGlyphLineLayer() {
        Extensions.install(new PlotExtension());
        Object[] capturedLayers = new Object[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            capturedLayers[0] = args.size() > 1 ? args.get(1) : null;
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderScene", stub);
        NativeCalls.register("pontif.plot/renderScene", stub);

        // f = x → gradient (1,0,0) everywhere: uniform magnitude, so every strided voxel clears the
        // flat-region threshold and every glyph points along +x.
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.plot.{Volume3D, volume, normals, scene}
                        struct Ramp()
                        assign trait Ramp:Volume3D {
                          at(x:Decimal, y:Decimal, z:Decimal):Decimal -> x
                          domain():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] ->
                            {-1.0, 1.0, -1.0, 1.0, -1.0, 1.0}
                        }
                        scene({title = "n"}, { normals(volume(Ramp()), 3) })""", "normals.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "normals program should run; got " + r.text());
        SceneBuilder.SceneBuild build = SceneBuilder.buildSceneLayers(capturedLayers[0]);
        // TWO layers: the raymarched volume + its gradient-glyph overlay.
        assertEquals(2, build.layers().size(), "volume + gradient glyphs");
        assertInstanceOf(VolumeLayer.class, build.layers().get(0));
        assertInstanceOf(LineLayer.class, build.layers().get(1));

        LineLayer glyphs = (LineLayer) build.layers().get(1);
        // stride 3 over a 24³ grid → 8 samples per axis → 8³ segments (uniform field: none culled).
        assertEquals(8 * 8 * 8, glyphs.segmentCount(), "8^3 lattice of glyphs");

        // Every glyph is axis-aligned along x: its two endpoints share y and z, and differ in x.
        float[] ep = glyphs.endpoints();
        assertEquals(0f, ep[4] - ep[1], 1e-6f, "glyph has no y extent (gradient is (1,0,0))");
        assertEquals(0f, ep[5] - ep[2], 1e-6f, "glyph has no z extent");
        assertTrue(Math.abs(ep[3] - ep[0]) > 1e-6f, "glyph extends along x");
        // Centred on the first voxel at the domain corner (-1,-1,-1): midpoint x ≈ -1.
        assertEquals(-1f, (ep[0] + ep[3]) / 2f, 1e-5f, "glyph centred on its voxel");
    }

    @Test
    void plotExpr_reliableColumns_detectPoleAndBuildSpans() {
        Extensions.install(new PlotExtension());

        // Capture the per-column spans the native would paint — no window opens. This exercises the
        // whole reliable pipeline: evalInterval per column (pontif.algebra), classifyColumn's
        // three-way mapping, the fragment loop, and the Stream[Span] → aggregate boundary crossing.
        Object[] captured = new Object[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            captured[0] = args.get(2);   // the {spans} tuple
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderReliable", stub);
        NativeCalls.register("pontif.plot/renderReliable", stub);

        // 1/x over [-2, 2]: a pole at x = 0. Point sampling would blow up; interval enclosure marks
        // the column straddling 0 as Unbounded and leaves the rest as bounded curve spans.
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.algebra.{AlgExpr, Const, Param, Div}
                        requires pontif.plot.{plotExpr}
                        let e:AlgExpr = Div(Const(1.0), Param("x"))
                        plotExpr(e, -2.0, 2.0)""", "reliable.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "plotExpr should run; got " + r.text());
        assertNotNull(captured[0], "renderReliable should have received the spans aggregate");

        List<ReliableSeries.ReliableSpan> spans = ReliableSeries.parseSpans(captured[0]);
        assertEquals(256, spans.size(), "one span per pixel column");
        assertTrue(spans.stream().anyMatch(s -> s.kind() == 1),
                "1/x over [-2,2] must detect a pole column (Unbounded) near x=0");
        assertTrue(spans.stream().allMatch(s -> s.kind() != 2),
                "1/x is defined everywhere except the pole — no wholly-undefined columns");

        // The spans rasterise to vertical series: poles as full-height blocks, curves as segments,
        // breaks omitted. A near-pole spike must NOT set the scale — the robust range stays modest.
        var series = ReliableSeries.buildReliableSeries(-2.0, 2.0, captured[0]);
        assertFalse(series.isEmpty(), "reliable spans should render to vertical series");
    }

    @Test
    void plotExpr_fromReflectedAlgebraicFunction_runs() {
        // Verifies the "Auto-Plotted Function" welcome sample (auto-plot.ptf): an ordinary Decimal
        // function proven Algebraic is reflected to its AST and handed straight to plotExpr. Guards
        // the reflection → plotExpr path the sample depends on (1/(x^2-1) has poles at ±1).
        Extensions.install(new PlotExtension());
        Object[] captured = new Object[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            captured[0] = args.get(2);
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderReliable", stub);
        NativeCalls.register("pontif.plot/renderReliable", stub);

        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.algebra.{Algebraic}
                        requires pontif.plot.{plotExpr}
                        function f(x:Decimal):Decimal -> 1.0 / (x * x - 1.0)
                        assign proof f:Algebraic
                        plotExpr($f[Decimal].ast)""", "autoplot.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "auto-plot sample should run; got " + r.text());
        List<ReliableSeries.ReliableSpan> spans = ReliableSeries.parseSpans(captured[0]);
        assertEquals(256, spans.size(), "256 columns over the auto-framed domain");
        assertTrue(spans.stream().anyMatch(s -> s.kind() == 1),
                "1/(x^2-1) must produce pole columns (Unbounded) at x = ±1");
    }

    /** Runs a no-domain plotExpr program and returns the auto-framed window [xlo, xhi] the native
     *  received (or null if renderReliable wasn't reached). Exercises the whole end-to-end path:
     *  autoFrame's evalInterval scan → framing → the render boundary. No window opens. */
    private static double[] capturedWindow(String body) {
        Extensions.install(new PlotExtension());
        double[] win = new double[2];
        boolean[] got = {false};
        NativeCalls.NativeCall stub = (args, ctx) -> {
            win[0] = ((java.math.BigDecimal) args.get(0)).doubleValue();
            win[1] = ((java.math.BigDecimal) args.get(1)).doubleValue();
            got[0] = true;
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderReliable", stub);
        NativeCalls.register("pontif.plot/renderReliable", stub);
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt(body, "autoframe.ptf"), PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "auto-framed plotExpr should run; got " + r.text());
        assertTrue(got[0], "renderReliable should have been reached");
        return win;
    }

    @Test
    void plotExpr_autoFramesToBracketThePolynomialsRoots() {
        // (x-2)(x+3) = x^2 + x - 6: sign changes at x = -3 and x = 2, so the numeric auto-window
        // (no domain given) must contain both roots — the "specify f, get a framed plot" path.
        double[] win = capturedWindow("""
                requires pontif.algebra.{Algebraic}
                requires pontif.plot.{plotExpr}
                function p(x:Decimal):Decimal -> x*x + x - 6.0
                assign proof p:Algebraic
                plotExpr($p[Decimal].ast)""");
        assertTrue(win[0] <= -3.0, "window should bracket the root at -3; lo=" + win[0]);
        assertTrue(win[1] >= 2.0, "window should bracket the root at 2; hi=" + win[1]);
    }

    @Test
    void plotExpr_autoFrame_tanWithPolesAtEveryDepth_doesNotOverflowTheStack() {
        // Regression: tan(x) has vertical asymptotes marching across the WHOLE probe range, so the
        // frame-scan meets a feature at every recursion depth. The old hand-written 256-deep scanFrom
        // recursion overflowed the interpreter stack here (heavy min/max work unwinding on a deep
        // stack); the fold-based scan is iterative and must simply produce a finite window.
        double[] win = capturedWindow("""
                requires pontif.algebra.{Algebraic}
                requires pontif.plot.{plotExpr}
                requires pontif.math.{tan}
                function f(x:Decimal):Decimal -> tan(x)
                assign proof f:Algebraic
                plotExpr($f[Decimal].ast)""");
        assertTrue(win[1] > win[0], "tan must auto-frame to a finite, ordered window, not overflow");
    }

    @Test
    void plotExpr_autoFrameFallsBackWhenThereAreNoFeatures() {
        // A nonzero constant has no roots and no poles → the default [-10, 10] window.
        double[] win = capturedWindow("""
                requires pontif.algebra.{AlgExpr, Const}
                requires pontif.plot.{plotExpr}
                plotExpr(Const(5.0))""");
        assertEquals(-10.0, win[0], 1e-9, "fallback lo");
        assertEquals(10.0, win[1], 1e-9, "fallback hi");
    }

    private static RecordValue span(long kind, double lo, double hi) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("lo", BigDecimal.valueOf(lo));
        m.put("hi", BigDecimal.valueOf(hi));
        return new RecordValue("pontif.plot/Span", m);
    }

    private static RecordValue spansTuple(RecordValue... columns) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < columns.length; i++) m.put("_" + i, columns[i]);
        return new RecordValue("_tuple", m);
    }

    @Test
    void reliableSpans_isolatedPole_breaksTheCurveIntoTwoConnectedPolylines() {
        // curve, curve, POLE, curve, curve — the two curve stretches connect into ONE polyline each,
        // and the lone pole is a break between them (not a line across it). So exactly two series.
        var series = ReliableSeries.buildReliableSeries(-2.0, 2.0, spansTuple(
                span(0, 1.0, 1.0), span(0, 1.0, 1.0), span(1, 0.0, 0.0),
                span(0, 1.0, 1.0), span(0, 1.0, 1.0)));
        assertEquals(2, series.size(), "one connected polyline on each side of the broken pole");
    }

    @Test
    void reliableSpans_densePole_fillsAsABlock() {
        // Dense pole columns (kind 3 — unresolvable detail, decided Pontif-side by the subdivision
        // probe) each fill, so all five become full-height series.
        var series = ReliableSeries.buildReliableSeries(-2.0, 2.0, spansTuple(
                span(3, 0.0, 0.0), span(3, 0.0, 0.0), span(3, 0.0, 0.0),
                span(3, 0.0, 0.0), span(3, 0.0, 0.0)));
        assertEquals(5, series.size(), "a dense pole run fills every column");
    }

    @Test
    void reliableSpans_smearedSimplePole_breaksInsteadOfFilling() {
        // Regression (the garbled cluster): interval overestimation at a sign-changing simple pole
        // smears `Unbounded` across several columns, but the subdivision probe marks them isolated
        // (kind 1), NOT dense. A run of kind-1 poles must render as a clean break the curve blows
        // through — NOT a stack of full-height fill bars. Curve on each side → two connected
        // polylines, with the whole smear a single gap between them.
        var series = ReliableSeries.buildReliableSeries(-2.0, 2.0, spansTuple(
                span(0, 1.0, 1.0), span(0, 1.0, 1.0),
                span(1, 0.0, 0.0), span(1, 0.0, 0.0), span(1, 0.0, 0.0), span(1, 0.0, 0.0), span(1, 0.0, 0.0),
                span(0, 1.0, 1.0), span(0, 1.0, 1.0)));
        // No full-height fill bars: every emitted series is a curve/spike polyline, not a {ymin,ymax}
        // block. The two curve stretches each become one polyline (blowing off toward the edge at the
        // pole), so the smear is a break — never five stacked bars.
        assertTrue(series.size() <= 2,
                "a smeared isolated pole breaks the curve; it does NOT fill as bars. got " + series.size());
    }

    private static double maxY(double[] ys) {
        double m = Double.NEGATIVE_INFINITY;
        for (double y : ys) m = Math.max(m, y);
        return m;
    }

    @Test
    void reliableSpans_poleEndedRun_extendsToTheFrameEdge() {
        // curve rising 0.1 → 0.2 → 0.3, then a POLE (proven blow-up): the line keeps going, so it
        // must extend past the last sampled midpoint (0.3) up to the top edge (ymax ≈ 0.31 here).
        var series = ReliableSeries.buildReliableSeries(-1.0, 1.0, spansTuple(
                span(0, 0.1, 0.1), span(0, 0.2, 0.2), span(0, 0.3, 0.3), span(1, 0.0, 0.0)));
        assertEquals(1, series.size());
        assertTrue(maxY(series.get(0).ys()) > 0.305,
                "a pole-ended run extends to the frame edge, past the last midpoint");
    }

    @Test
    void reliableSpans_emptyEndedRun_stopsWithoutExtending() {
        // Same run, but ending at an EMPTY column (Undefined = a domain edge): the curve genuinely
        // stops, so it must NOT be extended — the top stays at the last data point (0.3).
        var series = ReliableSeries.buildReliableSeries(-1.0, 1.0, spansTuple(
                span(0, 0.1, 0.1), span(0, 0.2, 0.2), span(0, 0.3, 0.3), span(2, 0.0, 0.0)));
        assertEquals(1, series.size());
        assertTrue(maxY(series.get(0).ys()) <= 0.30001,
                "an empty-ended (domain edge) run stops at the data, not the frame edge");
    }

    // --- Supplemental expression layers (reliable + zeros/optima/asymptotes/intersections) --------

    /** Capture the {@code chart} layer tuple and decompose it with the native, without a window. */
    private static ChartBuilder.AnnotatedChart runChart(String program) {
        Extensions.install(new PlotExtension());
        Object[] captured = new Object[1];
        NativeCalls.Context[] capturedCtx = new NativeCalls.Context[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            captured[0] = args.size() > 1 ? args.get(1) : null;
            capturedCtx[0] = ctx;   // so the decomposition can re-sample across the shared window
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderChart", stub);
        NativeCalls.register("pontif.plot/renderChart", stub);
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt(program, "layers.ptf"), PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "chart program should run; got " + r.text());
        assertNotNull(captured[0], "renderChart should have received the {layers} tuple");
        return ChartBuilder.buildAnnotatedChart(captured[0], capturedCtx[0]);
    }

    private static MarkSetOf firstMarkSet(ChartBuilder.AnnotatedChart chart, int kind) {
        var set = chart.marks().stream().filter(m -> m.kind() == kind).findFirst();
        assertTrue(set.isPresent(), "expected a mark set of kind " + kind);
        return new MarkSetOf(set.get());
    }

    /** A tiny view over a MarkSet's features, for order-independent proximity assertions. */
    private record MarkSetOf(ChartBuilder.MarkSet set) {
        boolean hasNear(double x, double tol) {
            return set.pts().stream().anyMatch(f -> Math.abs(f.x() - x) < tol);
        }
        boolean hasNear(double x, double y, double tol) {
            return set.pts().stream().anyMatch(f -> Math.abs(f.x() - x) < tol && Math.abs(f.y() - y) < tol);
        }
        int size() { return set.pts().size(); }
    }

    @Test
    void zeros_layer_marksTheRootsOfAPolynomial() {
        // x^2 - 4 has roots at x = ±2. The reliable curve frames the window; the zeros layer scans it
        // for sign changes and drops a marker on each root — composited in ONE chart.
        var chart = runChart("""
                requires pontif.algebra.{AlgExpr, Const, Param, Sub, Mul}
                requires pontif.plot.{expr, zeros, chart}
                let e:AlgExpr = Sub(Mul(Param("x"), Param("x")), Const(4.0))
                chart({title = "zeros"}, { expr(e), zeros(e) })""");
        assertFalse(chart.series().isEmpty(), "the reliable layer contributes drawable series");
        var z = firstMarkSet(chart, 0);
        assertTrue(z.hasNear(-2.0, 0.05), "a zero marker near x = -2");
        assertTrue(z.hasNear(2.0, 0.05), "a zero marker near x = 2");
        assertEquals(2, z.size(), "exactly the two real roots of x^2 - 4");
    }

    @Test
    void multipleAutoPlots_getDistinctColoursBandsAndColourCodedTitles() {
        // Two reliable auto-plots overlaid in one chart: expr(e), expr(r). Each must draw in its own
        // palette colour (not one shared cyan), contribute its own enclosure band, and add a
        // colour-coded title entry — so the plots are distinguishable and the title maps to the lines.
        var chart = runChart("""
                requires pontif.algebra.{AlgExpr, Const, Param, Sub, Add, Mul, Div}
                requires pontif.plot.{expr, chart}
                let e:AlgExpr = Sub(Mul(Param("x"), Param("x")), Const(4.0))
                let r:AlgExpr = Add(Mul(Param("x"), Param("x")), Const(1.0))
                chart({title = "two"}, { expr(e), expr(r) })""");
        assertEquals(2, chart.enclosures().size(), "one enclosure band per reliably-plotted expression");
        assertEquals(2, chart.titles().size(), "one colour-coded title entry per expression");
        // The two titles carry DISTINCT palette colours (so the on-screen / SVG title colour-codes).
        var c0 = chart.titles().get(0).color();
        var c1 = chart.titles().get(1).color();
        assertFalse(c0.r() == c1.r() && c0.g() == c1.g() && c0.b() == c1.b(),
                "the two expressions take different palette colours");
        // And the drawn series are painted in those same two colours — never a single shared colour.
        long distinctSeriesColours = chart.series().stream()
                .map(s -> s.color().r() + "," + s.color().g() + "," + s.color().b())
                .distinct().count();
        assertTrue(distinctSeriesColours >= 2, "overlaid reliable plots draw in distinct colours");
    }

    @Test
    void multipleAutoPlots_fromProvenFunctions_composeTwoReliablePlots() {
        // The end-to-end shape from the sample: two Decimal functions proven Algebraic, their reflected
        // ASTs ($f[Decimal].ast) auto-plotted together with annotation layers over the first. Exercises
        // the exact requested syntax (^ powers, implicit multiplication, integer literals) all the way
        // to the decomposed chart.
        var chart = runChart("""
                requires pontif.algebra.{Algebraic}
                requires pontif.plot.{expr, asymptotes, zeros, chart, optima}

                function f(x:Decimal):Decimal -> (2*x^3+7) / (3*x^2-5*x-4)
                assign proof f:Algebraic

                function g(x:Decimal):Decimal -> (2*x+3)/(x^2+3*x-4)
                assign proof g:Algebraic

                main (
                  let e = $f[Decimal].ast
                  let r = $g[Decimal].ast
                  chart({title = "Rational functions"}, { expr(e), expr(r), asymptotes(e), zeros(e), optima(e) })
                )""");
        assertEquals(2, chart.enclosures().size(), "two reliably-plotted expressions → two enclosure bands");
        assertEquals(2, chart.titles().size(), "two colour-coded title entries");
        long distinctSeriesColours = chart.series().stream()
                .map(s -> s.color().r() + "," + s.color().g() + "," + s.color().b())
                .distinct().count();
        assertTrue(distinctSeriesColours >= 2, "the two auto-plots draw in distinct colours");
    }

    @Test
    void multipleAutoPlots_shareOneWindow_soBothCurvesSpanTheFullFrameWidth() {
        // f and g auto-frame to DIFFERENT windows on their own. Overlaid, they must share ONE x-window
        // (the union) and each be re-sampled across it, so both curves reach the frame edges instead of
        // stopping inside their own slice. The frame x-axis is pinned exactly to that shared window.
        var chart = runChart("""
                requires pontif.algebra.{Algebraic}
                requires pontif.plot.{expr, chart}
                function f(x:Decimal):Decimal -> (2*x^3+7) / (3*x^2-5*x-4)
                assign proof f:Algebraic
                function g(x:Decimal):Decimal -> (2*x+3)/(x^2+3*x-4)
                assign proof g:Algebraic
                main (
                  let e = $f[Decimal].ast
                  let r = $g[Decimal].ast
                  chart({title = "two"}, { expr(e), expr(r) })
                )""");
        var win = chart.exprWindow();
        assertNotNull(win, "a chart with reliable plots records a shared window");
        var frame = ChartBuilder.annotatedFrame(chart);
        assertEquals(win[0], frame.x().min(), 1e-9, "frame x pinned to the shared window low edge");
        assertEquals(win[1], frame.x().max(), 1e-9, "frame x pinned to the shared window high edge");
        // EACH expression (grouped by its palette colour) must reach BOTH edges — not just the union.
        // A per-color check, because f alone spanning the window would mask g stopping at its own xhi
        // (the exact "orange doesn't reach the edge" symptom the aggregate check couldn't catch).
        double dx = (win[1] - win[0]) / 256.0;
        java.util.Map<String, double[]> byColor = new java.util.LinkedHashMap<>();
        for (var s : chart.series()) {
            String c = s.color().r() + "," + s.color().g() + "," + s.color().b();
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (double x : s.xs()) { lo = Math.min(lo, x); hi = Math.max(hi, x); }
            byColor.merge(c, new double[]{lo, hi},
                    (a, b) -> new double[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        }
        assertEquals(2, byColor.size(), "two distinctly-coloured reliable plots");
        byColor.forEach((c, ext) -> {
            assertTrue(ext[0] <= win[0] + dx, "curve " + c + " reaches the left edge; got min x " + ext[0]);
            assertTrue(ext[1] >= win[1] - dx, "curve " + c + " reaches the right edge; got max x " + ext[1]);
        });
    }

    @Test
    void multipleAutoPlots_annotationsColourMatchTheirOwningPlot() {
        // asymptotes(e) and asymptotes(r) must take e's and r's palette colours respectively — the
        // native matches each annotation's source AST to the plot of the same expression, regardless of
        // layer order. f (e) poles at x≈-0.591, 2.257; g (r) poles at x=-4, 1.
        var chart = runChart("""
                requires pontif.algebra.{Algebraic}
                requires pontif.plot.{expr, asymptotes, chart}
                function f(x:Decimal):Decimal -> (2*x^3+7) / (3*x^2-5*x-4)
                assign proof f:Algebraic
                function g(x:Decimal):Decimal -> (2*x+3)/(x^2+3*x-4)
                assign proof g:Algebraic
                main (
                  let e = $f[Decimal].ast
                  let r = $g[Decimal].ast
                  chart({title = "t"}, { expr(e), expr(r), asymptotes(e), asymptotes(r) })
                )""");
        // e = palette slot 0 (cyan), r = palette slot 1 (orange).
        var cyan = new sibarum.dasum.gui.core.render.Color(0.40f, 0.80f, 1.00f, 1f);
        var orange = new sibarum.dasum.gui.core.render.Color(1.00f, 0.55f, 0.35f, 1f);
        for (var v : chart.vlines()) assertNotNull(v.color(), "every asymptote is colour-matched to its plot");
        var atPole1 = chart.vlines().stream().filter(v -> Math.abs(v.x() - 1.0) < 1e-3).findFirst().orElseThrow();
        var atPole2 = chart.vlines().stream().filter(v -> Math.abs(v.x() - 2.257) < 5e-3).findFirst().orElseThrow();
        assertEquals(orange, atPole1.color(), "g's asymptote at x=1 takes g's (orange) colour");
        assertEquals(cyan, atPole2.color(), "f's asymptote at x≈2.257 takes f's (cyan) colour");
    }

    @Test
    void multipleAutoPlots_shareOneYRange_soBothBlowUpToTheSameEdges() {
        // f blows up to a much larger magnitude than g near their poles. On one shared frame each must
        // use the SAME robust y-band, so both curves' pole spikes reach the SAME top/bottom — otherwise
        // f reaches ±20 while g stops at its own ±10, leaving g visibly short of the vertical edges.
        var chart = runChart("""
                requires pontif.algebra.{Algebraic}
                requires pontif.plot.{expr, chart}
                function f(x:Decimal):Decimal -> (2*x^3+7) / (3*x^2-5*x-4)
                assign proof f:Algebraic
                function g(x:Decimal):Decimal -> (2*x+3)/(x^2+3*x-4)
                assign proof g:Algebraic
                main (
                  let e = $f[Decimal].ast
                  let r = $g[Decimal].ast
                  chart({title = "two"}, { expr(e), expr(r) })
                )""");
        java.util.Map<String, double[]> yByColor = new java.util.LinkedHashMap<>();
        for (var s : chart.series()) {
            String c = s.color().r() + "," + s.color().g() + "," + s.color().b();
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (double y : s.ys()) { lo = Math.min(lo, y); hi = Math.max(hi, y); }
            yByColor.merge(c, new double[]{lo, hi},
                    (a, b) -> new double[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        }
        assertEquals(2, yByColor.size(), "two distinctly-coloured reliable plots");
        var ys = yByColor.values().iterator();
        double[] y0 = ys.next(), y1 = ys.next();
        // Both blow up (poles on both) to the SAME shared band edges — top and bottom match tightly.
        assertEquals(y0[1], y1[1], 1e-9, "both curves blow up to the same TOP edge");
        assertEquals(y0[0], y1[0], 1e-9, "both curves blow down to the same BOTTOM edge");
    }

    @Test
    void optima_layer_marksLocalMinimaAndMaxima() {
        // x^3 - 3x has a local MAX at (-1, 2) and a local MIN at (1, -2).
        var chart = runChart("""
                requires pontif.algebra.{AlgExpr, Const, Param, Sub, Mul}
                requires pontif.plot.{expr, optima, chart}
                let e:AlgExpr = Sub(Mul(Mul(Param("x"), Param("x")), Param("x")), Mul(Const(3.0), Param("x")))
                chart({title = "optima"}, { expr(e), optima(e) })""");
        var o = firstMarkSet(chart, 1);
        assertTrue(o.hasNear(-1.0, 2.0, 0.1), "a maximum marker near (-1, 2)");
        assertTrue(o.hasNear(1.0, -2.0, 0.1), "a minimum marker near (1, -2)");
    }

    @Test
    void asymptotes_layer_marksIsolatedVerticalAsymptotes() {
        // 1/(x^2 - 1) has vertical asymptotes at x = ±1 (isolated poles, interval-proven Unbounded).
        var chart = runChart("""
                requires pontif.algebra.{AlgExpr, Const, Param, Sub, Mul, Div}
                requires pontif.plot.{expr, asymptotes, chart}
                let e:AlgExpr = Div(Const(1.0), Sub(Mul(Param("x"), Param("x")), Const(1.0)))
                chart({title = "asymptotes"}, { expr(e), asymptotes(e) })""");
        assertEquals(2, chart.vlines().size(), "two vertical asymptotes, at x = ±1");
        // refinePole bisects the pole column, so the reported x is the true pole to display precision
        // (not the ±w/2 column midpoint) — an integer asymptote must land on the integer and label "1".
        assertTrue(chart.vlines().stream().anyMatch(v -> Math.abs(v.x() + 1.0) < 1e-4), "asymptote at x = -1 (refined)");
        assertTrue(chart.vlines().stream().anyMatch(v -> Math.abs(v.x() - 1.0) < 1e-4), "asymptote at x = 1 (refined)");
        assertTrue(chart.vlines().stream().anyMatch(v -> GuiShared.fmt(v.x()).equals("1")),
                "the label reads the clean integer '1', not '0.997' / '1.002'");
        assertTrue(chart.vlines().stream().anyMatch(v -> GuiShared.fmt(v.x()).equals("-1")),
                "and '-1' for the negative asymptote");
    }

    @Test
    void featureLayers_rejectSpuriousFeaturesStraddlingAnAsymptote() {
        // 1/(x^2 - 1): the only real local optimum is the max at (0, -1), and there are NO real zeros.
        // The finite samples either side of the poles at ±1 fake a slope reversal AND a sign change
        // across the discontinuity; the pole-in-span guard must reject both, so optima = {(0,-1)} and
        // zeros = {} — no markers pinned to an asymptote.
        var chart = runChart("""
                requires pontif.algebra.{AlgExpr, Const, Param, Sub, Mul, Div}
                requires pontif.plot.{expr, optima, zeros, chart}
                let e:AlgExpr = Div(Const(1.0), Sub(Mul(Param("x"), Param("x")), Const(1.0)))
                chart({title = "rat"}, { expr(e), optima(e), zeros(e) })""");
        var o = firstMarkSet(chart, 1);
        assertEquals(1, o.size(), "exactly one true optimum (no spurious ones beside the poles)");
        assertTrue(o.hasNear(0.0, -1.0, 0.05), "the local maximum at (0, -1)");
        var z = firstMarkSet(chart, 0);
        assertEquals(0, z.size(), "a sign flip ACROSS an asymptote is not a zero");
    }

    @Test
    void asymptotes_layer_findsBothPolesOfARationalFunction_evenTheSmearedSimpleOne() {
        // Regression (the "garbled cluster"): (7x^4-5x^3+2x^2-11x+3)/(13x^3-5x^2) has denominator
        // x^2*(13x-5) → poles at x = 0 (double, clean) and x = 5/13 ≈ 0.3846 (simple, sign-changing).
        // The simple pole's denominator terms nearly cancel, so interval overestimation smeared it
        // across several Unbounded columns — the old run-length heuristic misread that as a DENSE
        // block, rendered it as a stack of bars, and skipped its asymptote line. The subdivision
        // probe now resolves it: exactly one asymptote at each true pole, and NO dense-fill columns.
        var chart = runChart("""
                requires pontif.algebra.{Algebraic}
                requires pontif.plot.{expr, asymptotes, chart}
                function f(x:Decimal):Decimal -> (7*x^4 - 5*x^3 + 2*x^2 - 11*x + 3) / (13*x^3 - 5*x^2)
                assign proof f:Algebraic
                chart({title = "rational"}, { expr($f[Decimal].ast), asymptotes($f[Decimal].ast) })""");
        assertTrue(chart.vlines().stream().anyMatch(v -> Math.abs(v.x()) < 1e-3),
                "asymptote at the double pole x = 0; got " + chart.vlines());
        assertTrue(chart.vlines().stream().anyMatch(v -> Math.abs(v.x() - 5.0 / 13.0) < 1e-3),
                "asymptote at the smeared simple pole x = 5/13 ≈ 0.3846; got " + chart.vlines());
        // The smeared simple pole must NOT be misclassified as a dense fill block: no kind-3 columns.
        assertTrue(chart.series().stream().noneMatch(PlotExtensionTest::isFullHeightBar),
                "a simple pole renders as a break, never a stack of full-height fill bars");
    }

    /** A dense-pole fill bar is a 2-point vertical segment spanning the whole frame at one x. */
    private static boolean isFullHeightBar(sibarum.dasum.gui.vis.plot.Series s) {
        return s.pointCount() == 2 && s.xs()[0] == s.xs()[1];
    }

    @Test
    void svgExportExample_compilesAndTypechecks() {
        // Guards examples/svg-export.ptf: the embeddable chartView + a Clickable export button whose
        // onClick calls exportSvg on the same layers. Compiles + links only (a window needs GLFW).
        Extensions.install(new PlotExtension());
        Extensions.install(new GuiExtension());
        var result = new PontifCompiler().compileAlt("""
                requires pontif.algebra.{Algebraic}
                requires pontif.plot.{expr, asymptotes, zeros, chartView, exportSvg}
                requires pontif.gui.{Button, window, Clickable}
                function f(x:Decimal):Decimal -> (7*x^4 - 5*x^3 + 2*x^2 - 11*x + 3) / (13*x^3 - 5*x^2)
                assign proof f:Algebraic
                struct ExportButton:[Button](text:String, data:_)
                assign trait ExportButton:Clickable {
                  onClick():_ -> ( let done = exportSvg(this.data)  this )
                }
                main (
                  let e = $f[Decimal].ast
                  let layers = { expr(e), asymptotes(e), zeros(e) }
                  window({title = "Reliable plot -> SVG", width = 1100, height = 720},
                         { chartView({}, layers), ExportButton("Export SVG...", layers) })
                )""", "svg-export.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, result,
                () -> "svg-export example should link; got "
                        + (result instanceof PontifCompiler.CompileResult.Failed f ? f.error().text() : result));
    }

    @Test
    void titledExportExample_compilesAndTypechecks() {
        // Guards examples/titled-export.ptf: a standalone chart window with a math title and an
        // Export-SVG button (cfg export=true). Compiles + links only (the window needs GLFW).
        Extensions.install(new PlotExtension());
        var result = new PontifCompiler().compileAlt("""
                requires pontif.algebra.{Algebraic}
                requires pontif.plot.{expr, asymptotes, zeros, chart}
                function f(x:Decimal):Decimal -> (2*x + 3) / (x^2 + 3*x - 4)
                assign proof f:Algebraic
                main (
                  let e = $f[Decimal].ast
                  chart({title = "Rational function", width = 1000, height = 720, export = true},
                        { expr(e), asymptotes(e), zeros(e) })
                )""", "titled-export.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, result,
                () -> "titled-export example should link; got "
                        + (result instanceof PontifCompiler.CompileResult.Failed f ? f.error().text() : result));
    }

    @Test
    void calculatorExample_compilesAndTypechecks() throws Exception {
        // Guards examples/calculator.ptf (the single-expression calculator POC): reads the ACTUAL
        // example file off disk and links it, so the on-disk POC can't drift out of a working state.
        // The single-expression pipeline — reflect an Algebraic function's AST, typeset it as the
        // chartView title, auto-plot with labelled zeros/optima/asymptotes, and an Export-SVG button.
        // Compiles + links only (the window itself needs GLFW).
        Extensions.install(new PlotExtension());
        Extensions.install(new GuiExtension());
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("examples/calculator.ptf"));
        var result = new PontifCompiler().compileAlt(source, "calculator.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, result,
                () -> "calculator POC example should link; got "
                        + (result instanceof PontifCompiler.CompileResult.Failed f ? f.error().text() : result));
    }

    @Test
    void exportSvg_buildsSemanticClassedMarkupThroughTheSharedScene() {
        // The full Pontif -> AnnotatedChart -> PlotScene2D (shared IR) -> SVG path (the Save dialog
        // itself needs a window, so it's exercised manually). The rational function contributes a
        // reliable curve (+ enclosure band), asymptotes, and zero markers — each must appear classed.
        var chart = runChart("""
                requires pontif.algebra.{Algebraic}
                requires pontif.plot.{expr, asymptotes, zeros, chart}
                function f(x:Decimal):Decimal -> (7*x^4 - 5*x^3 + 2*x^2 - 11*x + 3) / (13*x^3 - 5*x^2)
                assign proof f:Algebraic
                chart({title = "r"}, { expr($f[Decimal].ast), asymptotes($f[Decimal].ast), zeros($f[Decimal].ast) })""");
        var frame = ChartBuilder.annotatedFrame(chart);
        assertNotNull(frame, "the rational chart frames");
        var scene = ChartBuilder.buildScene(chart, frame);
        String svg = sibarum.dasum.gui.vis.plot.SvgPlotWriter.write(scene, 900, 550);

        assertDoesNotThrow(() -> javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new java.io.ByteArrayInputStream(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            () -> "exported SVG must be well-formed:\n" + svg);
        assertTrue(svg.contains("class=\"pontif-plot\""), "root classed");
        assertTrue(svg.contains("class=\"curve\""), "reliable curve is a classed path");
        assertTrue(svg.contains("class=\"asymptote\""), "asymptotes classed");
        assertTrue(svg.contains("class=\"enclosure-band\"") && svg.contains("display:none"),
            "the reliable enclosure band ships, hidden by default");
        assertTrue(svg.contains("feature zero"), "zero markers classed by kind");
    }

    @Test
    void titledChartSvg_includesTheTypesetTitleAboveThePlot() {
        // Export-with-title: the combined SVG stacks the typeset math title (from the chart's expr AST)
        // above the plot, each a nested <svg> with its own classed markup.
        var chart = runChart("""
                requires pontif.algebra.{Algebraic}
                requires pontif.plot.{expr, asymptotes, zeros, chart}
                function f(x:Decimal):Decimal -> (2*x + 3) / (x^2 + 3*x - 4)
                assign proof f:Algebraic
                chart({title = "r"}, { expr($f[Decimal].ast), asymptotes($f[Decimal].ast), zeros($f[Decimal].ast) })""");
        String svg = PlotSvg.titledChartSvg(chart);
        assertNotNull(svg, "a drawable chart yields an SVG");
        assertDoesNotThrow(() -> javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new java.io.ByteArrayInputStream(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            () -> "combined title+plot SVG must be well-formed:\n" + svg);
        assertTrue(svg.contains("class=\"math\""), "the typeset math title is included");
        assertTrue(svg.contains("class=\"pontif-plot\""), "the plot is included");
        assertTrue(svg.contains("class=\"curve\"") && svg.contains("class=\"asymptote\""),
            "the plot's curve + asymptotes are in the export");
        assertTrue(svg.contains("@font-face") && svg.contains("base64,"),
            "the STIX Two Math subset is embedded, so the title renders anywhere (self-contained)");
    }

    @Test
    void multiPlotExportSvg_colourCodesEachCurve() throws Exception {
        // End-to-end SVG export of two colour-coded plots: each curve must carry its OWN stroke, not a
        // single shared --pontif-curve for every path (which rendered orange as cyan → "orange missing").
        var chart = runChart("""
                requires pontif.algebra.{Algebraic}
                requires pontif.plot.{expr, asymptotes, zeros, chart, optima}
                function f(x:Decimal):Decimal -> (2*x^3+7) / (3*x^2-5*x-4)
                assign proof f:Algebraic
                function g(x:Decimal):Decimal -> (2*x+3)/(x^2+3*x-4)
                assign proof g:Algebraic
                main (
                  let e = $f[Decimal].ast
                  let r = $g[Decimal].ast
                  chart({title = "two"}, { expr(e), expr(r), asymptotes(e), zeros(e), optima(e) })
                )""");
        String svg = PlotSvg.titledChartSvg(chart);
        assertNotNull(svg);
        // A snapshot on disk for eyeballing the actual drawn geometry (the SVG-verification habit).
        String out = System.getenv("PLOT_SVG_OUT");
        if (out != null) java.nio.file.Files.writeString(java.nio.file.Path.of(out), svg);
        // Curves are cyan (f) and orange (g); count distinct inline strokes ON curve paths.
        java.util.Set<String> curveStrokes = new java.util.HashSet<>();
        var m = java.util.regex.Pattern.compile("<path class=\"curve\" style=\"stroke:(#[0-9a-f]+)\"").matcher(svg);
        while (m.find()) curveStrokes.add(m.group(1));
        assertTrue(curveStrokes.contains("#66ccff"), "f's curves are cyan; got " + curveStrokes);
        assertTrue(curveStrokes.contains("#ff8c59"), "g's curves are orange; got " + curveStrokes);
    }

    @Test
    void annotatedLayers_thinOverlappingAsymptoteLabels_butKeepEveryLine() {
        // Regression (tan(1/x)): poles cluster infinitely toward x=0, so a label on every one stacks
        // into an unreadable smear. Every asymptote must still draw its LINE, but overlapping labels
        // are dropped — the well-separated ones (-0.9, 0.9) survive, the tight cluster near 0 collapses
        // to at most a couple of labels.
        double[] vx = {-0.9, 0.0, 0.01, 0.02, 0.03, 0.04, 0.9};
        List<ChartBuilder.VLineMark> vlines = new java.util.ArrayList<>();
        for (double x : vx) vlines.add(new ChartBuilder.VLineMark(x, null));
        var series = List.of(sibarum.dasum.gui.vis.plot.Series.line(
                new double[]{-1.0, 1.0}, new double[]{-1.0, 1.0}, new sibarum.dasum.gui.core.render.Color(1, 1, 1, 1)));
        var chart = new ChartBuilder.AnnotatedChart(series, List.of(), vlines, null, null, null);
        var frame = ChartBuilder.annotatedFrame(chart);
        var layers = ChartBuilder.buildAnnotatedLayers(chart, frame);

        long asymptoteLines = layers.stream()
                .filter(l -> l instanceof LineLayer && l.opacity() == 0.5f).count();
        assertEquals(vlines.size(), asymptoteLines, "every asymptote still draws its line");

        List<String> labels = layers.stream()
                .filter(l -> l instanceof TextLayer t && t.text().startsWith("x="))
                .map(l -> ((TextLayer) l).text()).toList();
        assertTrue(labels.size() < vlines.size(),
                "overlapping labels are thinned; got all " + labels);
        assertTrue(labels.contains("x=-0.9") && labels.contains("x=0.9"),
                "the well-separated asymptotes keep their labels; got " + labels);
    }

    @Test
    void intersections_layer_marksWhereTwoCurvesCross() {
        // x^2 and x + 2 cross where x^2 - x - 2 = 0 → x = -1 and x = 2; the crossing heights are the
        // value of the first curve there (1 and 4).
        var chart = runChart("""
                requires pontif.algebra.{AlgExpr, Const, Param, Add, Mul}
                requires pontif.plot.{expr, intersections, chart}
                let e:AlgExpr = Mul(Param("x"), Param("x"))
                let g:AlgExpr = Add(Param("x"), Const(2.0))
                chart({title = "cross"}, { expr(e), intersections(e, g) })""");
        var pts = firstMarkSet(chart, 2);
        assertTrue(pts.hasNear(-1.0, 1.0, 0.1), "an intersection near (-1, 1)");
        assertTrue(pts.hasNear(2.0, 4.0, 0.1), "an intersection near (2, 4)");
    }

    @Test
    void annotatedChart_singleMarker_framesAndBuildsLayers_withoutSeriesUnderflow() {
        // Regression (welcome sample): the auto-plot sample's optima layer yields ONE marker (the max
        // at (0,-1)). The framing-only series through the markers must not be a 1-point Series (dasum
        // rejects "a series needs >= 2 points") — a lone marker is duplicated into a degenerate frame
        // series. Exercises the frame + layer build that the render path runs (no window).
        var chart = runChart("""
                requires pontif.algebra.{AlgExpr, Const, Param, Sub, Mul, Div}
                requires pontif.plot.{expr, optima, asymptotes, chart}
                let e:AlgExpr = Div(Const(1.0), Sub(Mul(Param("x"), Param("x")), Const(1.0)))
                chart({title = "sample"}, { expr(e), asymptotes(e), optima(e) })""");
        assertEquals(1, firstMarkSet(chart, 1).size(), "one local optimum for 1/(x^2-1)");
        var frame = ChartBuilder.annotatedFrame(chart);
        assertNotNull(frame, "a single marker must still yield a frame, not throw on a 1-point series");
        assertFalse(ChartBuilder.buildAnnotatedLayers(chart, frame).isEmpty(),
                "the annotated layer list (axes + curve + marker + asymptotes) builds");
    }

    @Test
    void failsafe_suppressesAnOverflowingLayer_andLogs() {
        // The "unreasonable quantity of primitives" guard: a MarkLayer with more than FEATURE_CAP
        // markers is dropped rather than cluttering the plot, and a notice is written to StdErr.
        Map<String, Object> pts = new LinkedHashMap<>();
        for (int i = 0; i < ChartBuilder.FEATURE_CAP + 6; i++) pts.put("_" + i, mark(i * 0.1, 0.0));
        RecordValue overflow = new RecordValue("pontif.plot/MarkLayer",
                new LinkedHashMap<>(Map.of("pts", new RecordValue("_tuple", pts), "kind", 1L)));

        java.io.PrintStream realErr = System.err;
        java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(log));
        ChartBuilder.AnnotatedChart chart;
        try { chart = ChartBuilder.buildAnnotatedChart(spansTuple(overflow)); }
        finally { System.setErr(realErr); }

        assertTrue(chart.marks().isEmpty(), "an over-cap annotation layer must be suppressed");
        assertTrue(log.toString().contains("suppressed"), "the failsafe must log the suppression");
    }

    private static RecordValue mark(double x, double y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", BigDecimal.valueOf(x));
        m.put("y", BigDecimal.valueOf(y));
        return new RecordValue("pontif.plot/Mark", m);
    }
}

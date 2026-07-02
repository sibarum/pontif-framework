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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

        DasumBridge.SceneBuild build = DasumBridge.buildSceneLayers(capturedLayers[0]);
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

        var series = DasumBridge.buildChartSeries(capturedLayers[0]);
        assertEquals(2, series.size(), "two overlaid curves → two series");
    }

    @Test
    void axisBox_generatesWireframeTicksAndNiceLabels() {
        // World bounds of a bowl: x,z in [-3,3], height y in [0,18].
        List<Layer> layers = DasumBridge.axisBoxLayers(new Vec3(-3f, 0f, -3f), new Vec3(3f, 18f, 3f), true);

        long lineLayers = layers.stream().filter(l -> l instanceof LineLayer).count();
        long textLayers = layers.stream().filter(l -> l instanceof TextLayer).count();
        assertTrue(lineLayers >= 2, "box wireframe + tick/grid line layers; got " + lineLayers);
        assertTrue(textLayers >= 3, "several numeric tick labels; got " + textLayers);

        // Nice-number ticks (Heckbert) over [0,18] include a "0" label, billboarded to face the camera.
        boolean zeroLabel = layers.stream().anyMatch(l ->
                l instanceof TextLayer t && t.text().equals("0") && t.billboard());
        assertTrue(zeroLabel, "expected a billboard '0' tick label");

        // Empty/degenerate bounds produce nothing (no crash).
        assertTrue(DasumBridge.axisBoxLayers(new Vec3(0f, 0f, 0f), new Vec3(0f, 0f, 0f), true).isEmpty());
    }

    @Test
    void colormaps_areMonotoneAndNamed() {
        // Endpoints and a distinct interior — each named map differs from the legacy "cool" ramp.
        assertArrayEquals(new float[]{0f, 0f, 0f}, DasumBridge.colorFor("grayscale", 0f), 1e-6f);
        assertArrayEquals(new float[]{1f, 1f, 1f}, DasumBridge.colorFor("grayscale", 1f), 1e-6f);
        assertArrayEquals(new float[]{0f, 0.5f, 1f}, DasumBridge.colorFor("cool", 0f), 1e-6f);
        assertArrayEquals(new float[]{1f, 0.5f, 0f}, DasumBridge.colorFor("cool", 1f), 1e-6f);
        // t is clamped; an unknown name falls back to "cool".
        assertArrayEquals(DasumBridge.colorFor("cool", 1f), DasumBridge.colorFor("cool", 2f), 1e-6f);
        assertArrayEquals(DasumBridge.colorFor("cool", 0.5f), DasumBridge.colorFor("bogus", 0.5f), 1e-6f);
        // viridis/turbo span dark→bright (green-ness rises), distinct from cool.
        float[] vlo = DasumBridge.colorFor("viridis", 0f), vhi = DasumBridge.colorFor("viridis", 1f);
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
        DasumBridge.SceneBuild build = DasumBridge.buildSceneLayers(capturedLayers[0]);
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
        DasumBridge.SceneBuild build = DasumBridge.buildSceneLayers(capturedLayers[0]);
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
        DasumBridge.SceneBuild build = DasumBridge.buildSceneLayers(capturedLayers[0]);
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
}

package sibarum.pontif.gui;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;
import sibarum.pontif.shape.ShapeExtension;
import sibarum.dasum.gui.vis.scene.VolumeLayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless verification of the shape extension (docs/shapes.md S1). A {@code Sphere} satisfies the
 * {@code SdfShape} trait; {@code preview} dispatches on the trait and samples its signed distance
 * field <b>in Pontif</b> (range synthesis + map + {@code distance} method dispatch), handing the
 * sampled grid to {@code pontif.plot}'s {@code renderScene}. The test overrides that native with a
 * capturing stub, so it exercises the full pipeline — trait dispatch, the Pontif sampling loop, and
 * the stream→aggregate boundary crossing — <b>without opening a window</b>. Lives in the
 * {@code sibarum.pontif.gui} package to reuse {@link DasumBridge}'s package-private test seam
 * ({@code doubles} / {@code buildSceneLayers}). The actual render is verified manually:
 * {@code mvn -pl pontif-builtin-shape -am exec:exec -Dptf=examples/sphere.ptf}.
 */
class ShapeExtensionTest {

    @Test
    void preview_samplesSphereSdfOverGrid_inPontif() {
        Extensions.install(new PlotExtension());
        Extensions.install(new ShapeExtension());

        // Override the plot native with a capturing stub so NO window opens; capture the {layers}
        // tuple preview() hands to renderScene. Register both the bare and qualified names.
        Object[] capturedLayers = new Object[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            capturedLayers[0] = args.size() > 1 ? args.get(1) : null;
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderScene", stub);
        NativeCalls.register("pontif.plot/renderScene", stub);

        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt("""
                        requires pontif.shape.{Sphere, preview}
                        preview(Sphere(1.0))""", "sphere.ptf"),
                PontifRunner.Engine.INTERPRETER);

        assertFalse(r.isError(), () -> "preview program should run; got " + r.text());
        assertNotNull(capturedLayers[0], "renderScene should have received the {layers} tuple");

        // The single Volume layer carries the raw signed-distance grid the SDF was sampled into.
        RecordValue tuple = (RecordValue) capturedLayers[0];
        RecordValue vol = (RecordValue) tuple.members().values().iterator().next();
        double[] vs = DasumBridge.doubles(vol.members().get("vs"));

        // 24^3 samples over the sphere's bounds [-2,2]^3 (radius 1, box padded to 2r). The grid is
        // clamped to a surface band (±2·dx) so the volumetric render lights the surface shell, not
        // the whole box (an SDF has unit gradient everywhere — see ShapeExtension.preview).
        double dx = 4.0 / 23.0;          // (xhi - xlo) / 23 for bounds [-2, 2]
        double band = 2.0 * dx;
        assertEquals(13824, vs.length, "24^3 SDF sample grid");
        // Corner voxel (-2,-2,-2): raw sdf = sqrt(12)-1 ≈ 2.46, well OUTSIDE the band → clamped to
        // +band. Proves the sign (outside) and the clamp.
        assertEquals(band, vs[0], 1e-9, "corner clamped to +band (outside the surface)");
        // Near-centre voxel (x=y=z index 11 ≈ origin): deep INSIDE → raw sdf ≈ -0.85 < -band →
        // clamped to -band. Proves the sign (inside).
        assertEquals(0.0 - band, vs[11 + 11 * 24 + 11 * 576], 1e-9, "near-centre clamped to -band (inside)");
        // A voxel near the radius-1 surface (x index 17, y=z index 12) has |sdf| < band, so it is
        // NOT clamped and carries the exact distance — proving distance() = sqrt(x²+y²+z²) - r ran
        // through the trait and the grid coordinates were placed correctly.
        double sx = -2.0 + 17 * dx, sy = -2.0 + 12 * dx, sz = -2.0 + 12 * dx;
        double exact = Math.sqrt(sx * sx + sy * sy + sz * sz) - 1.0;
        assertTrue(Math.abs(exact) < band, "test fixture: chosen voxel is within the unclamped band");
        assertEquals(exact, vs[17 + 12 * 24 + 12 * 576], 1e-9, "near-surface voxel carries the exact sdf");

        // The same layers build into one raymarched 24^3 VolumeLayer (the reused plot path).
        DasumBridge.SceneBuild build = DasumBridge.buildSceneLayers(capturedLayers[0]);
        assertEquals(1, build.layers().size(), "one volumetric layer");
        assertInstanceOf(VolumeLayer.class, build.layers().get(0));
        assertEquals(24, ((VolumeLayer) build.layers().get(0)).nx(), "24^3 sampling grid");
    }
}

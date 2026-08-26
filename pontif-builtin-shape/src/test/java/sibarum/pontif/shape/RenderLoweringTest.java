package sibarum.pontif.shape;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless verification of the shape extension (docs/shapes.md S1): what {@code render} and
 * {@code previewGradientField} hand to a renderer.
 *
 * <h2>The renderer is a stub, and that is the point</h2>
 * {@code pontif.shape} needs {@code Volume} and {@code scene} from {@code pontif.plot} — a renderer, any
 * renderer — and the one on the test classpath is {@link StubRenderer}, six lines of Pontif. So what is asserted
 * here is <b>shape's</b> contract at that seam: the sampled grid, the lowered GLSL, and the world box.
 *
 * <p>It used to install {@code pontif-builtin-gui}'s {@code PlotExtension} and then assert that the tuple built
 * into the renderer's own {@code VolumeLayer} / {@code RaymarchLayer} — which is that renderer's contract, asserted
 * through shape, and the only reason this module reached into the GUI stack at all. Those two assertions are
 * gone rather than moved: they were claims about a renderer that is being replaced.
 *
 * <p>Everything below runs <b>in Pontif</b> — trait dispatch, the sampling loop, `distanceAt` recursion, the
 * stream→aggregate crossing — and no window opens. The actual render is verified manually:
 * {@code mvn -pl pontif-builtin-shape -am exec:exec -Dptf=examples/sphere.ptf}.
 */
class RenderLoweringTest {

    /** Install shape over the stub renderer, run {@code src}, and return the layers tuple {@code scene} got. */
    private static RecordValue layersOf(String src, String name) {
        Extensions.install(new ShapeExtension());
        Object[] captured = new Object[1];
        NativeCalls.NativeCall stub = (args, ctx) -> {
            captured[0] = args.size() > 1 ? args.get(1) : null;
            return new IrInterpreter.DriveResult();
        };
        NativeCalls.register("renderScene", stub);
        NativeCalls.register("pontif.plot/renderScene", stub);

        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compile(src, name), PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> name + " should run; got " + r.text());
        assertNotNull(captured[0], "renderScene should have received the {layers} tuple");
        return (RecordValue) captured[0];
    }

    /** The one layer in a single-layer scene. */
    private static RecordValue onlyLayer(RecordValue layers) {
        return (RecordValue) layers.members().values().iterator().next();
    }

    /** A Pontif aggregate of numbers as a {@code double[]}, in member order. */
    private static double[] doubles(Object value) {
        if (!(value instanceof RecordValue rv)) {
            return new double[0];
        }
        double[] out = new double[rv.members().size()];
        int i = 0;
        for (Object member : rv.members().values()) {
            out[i++] = member instanceof BigDecimal d ? d.doubleValue()
                    : member instanceof Long l ? l
                    : member instanceof Integer n ? n
                    : 0.0;
        }
        return out;
    }

    private static double decimal(RecordValue r, String member) {
        return ((BigDecimal) r.members().get(member)).doubleValue();
    }

    @Test
    void previewGradientField_samplesSphereSdfOverGrid_inPontif() {
        RecordValue vol = onlyLayer(layersOf("""
                requires pontif.shape.{Sphere, previewGradientField}
                previewGradientField(Sphere(1.0))""", "sphere.ptf"));
        double[] vs = doubles(vol.members().get("vs"));

        // 24^3 samples over the sphere's bounds [-2,2]^3 (radius 1, box padded to 2r). The grid is
        // clamped to a surface band (±2·dx) so the volumetric render lights the surface shell, not
        // the whole box (an SDF has unit gradient everywhere — see ShapeExtension.previewGradientField).
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

        // And the box it was sampled over travels with it, or the renderer cannot place the grid.
        assertEquals(-2.0, decimal(vol, "xlo"), 1e-9);
        assertEquals(2.0, decimal(vol, "xhi"), 1e-9);
    }

    @Test
    void render_lowersSphereSdfToGlslMap_withItsWorldBox() {
        RecordValue ray = onlyLayer(layersOf("""
                requires pontif.shape.{Sphere, render}
                render(Sphere(1.0))""", "render.ptf"));

        // The general lowerer inlines the SHAPE's OWN distance body (sqrt(x²+y²+z²) - r), so it
        // can't drift from the Pontif formula — not a hand-written `length(p) - r`.
        assertEquals("float map(vec3 p){ return (sqrt((((p.x * p.x) + (p.y * p.y)) + (p.z * p.z))) - 1.0); }",
                ((StringValue) ray.members().get("map")).content(),
                "Sphere SDF lowered from its real distance body");
        // The world AABB from bounds() (radius 1 → box [-2,2]^3 → center 0, half-extent 2).
        assertEquals(0.0, decimal(ray, "cx"), 1e-9, "box center x");
        assertEquals(2.0, decimal(ray, "hx"), 1e-9, "box half-extent x");
    }

    /** The general lowerer inlines each shape's real `distance` IR: composites + transforms. */
    @Test
    void render_lowersCsgAndTransforms() {
        // union(Sphere, translate(Sphere)) → min over the two children, the translate inlined as
        // a back-shifted point. Proves distanceAt recursion + this.<field> literals + math calls.
        String map = renderMap("""
                requires pontif.shape.{Sphere, translate, union, render}
                render(union(Sphere(1.0), translate(Sphere(0.8), {0.9, 0.0, 0.0})))""");
        // union → min; sphere A at p; sphere B at p - (0.9,0,0); radii 1.0 and 0.8 inlined.
        assertTrue(map.startsWith("float map(vec3 p){ return min("), () -> "union → min: " + map);
        assertTrue(map.contains("- 1.0"), () -> "sphere A radius inlined: " + map);
        assertTrue(map.contains("- 0.8"), () -> "sphere B radius inlined: " + map);
        assertTrue(map.contains("0.9"), () -> "translate offset inlined: " + map);
        // No unresolved Pontif residue leaked into the shader.
        assertFalse(map.contains("distanceAt") || map.contains("this."),
                () -> "no Pontif residue in the GLSL: " + map);
    }

    /** The render-csg.ptf example: difference + smoothUnion + rotateY + translate all lower. */
    @Test
    void render_lowersTheFullCsgExample() {
        String map = renderMap("""
                requires pontif.shape.{Sphere, translate, rotateY, difference, smoothUnion, render}
                render(rotateY(
                  smoothUnion(
                    difference(Sphere(1.2), translate(Sphere(0.9), {0.8, 0.4, 0.4})),
                    translate(Sphere(0.6), {0.0, 0.0 - 1.1, 0.0}),
                    0.3),
                  25.0, {0.0, 0.0, 0.0}))""");
        // Every operator/intrinsic present, no unlowered Pontif residue.
        assertTrue(map.contains("max(") && map.contains("mix(") && map.contains("clamp(")
                && map.contains("cos(") && map.contains("sin(") && map.contains("radians("),
                () -> "CSG + transform intrinsics present: " + map);
        assertFalse(map.contains("distanceAt") || map.contains("this.") || map.contains("pontif."),
                () -> "no Pontif residue in the GLSL: " + map);
    }

    /** THE payoff: a user-defined SdfShape lowers to GLSL (no built-in special-casing). */
    @Test
    void render_lowersUserDefinedShape() {
        // A ground plane at height 0: distance = y. Entirely user code, no built-in node.
        String map = renderMap("""
                requires pontif.shape.{SdfShape, render}
                struct Slab(h:Decimal)
                assign trait Slab:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> y - this.h
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] ->
                    {0.0 - 4.0, 4.0, 0.0 - 4.0, 4.0, 0.0 - 4.0, 4.0}
                }
                render(Slab(0.5))""");
        assertEquals("float map(vec3 p){ return (p.y - 0.5); }", map,
                "user shape's own distance body lowered to GLSL");
    }

    /** The library primitives render for free — the lowerer reads their real distance IR. */
    @Test
    void render_lowersNewPrimitivesWithNoJavaChange() {
        // Box: abs + max/min + sqrt, no residue. Torus: nested sqrt. Neither has a hand-written
        // GLSL template — both come straight from the Pontif distance bodies.
        String box = renderMap("requires pontif.shape.{Box, render}\nrender(Box(1.0, 0.5, 2.0))");
        assertTrue(box.contains("abs(p.x)") && box.contains("sqrt(") && box.contains("max("),
                () -> "box lowered from its distance body: " + box);
        assertFalse(box.contains("distanceAt") || box.contains("this.") || box.contains("pontif."),
                () -> "no Pontif residue: " + box);

        String torus = renderMap("requires pontif.shape.{Torus, render}\nrender(Torus(2.0, 0.5))");
        assertTrue(torus.contains("sqrt(") && torus.contains("- 2.0") && torus.contains("- 0.5"),
                () -> "torus major/minor inlined: " + torus);

        // The render-primitives.ptf example: Box+Cylinder+Torus+difference+smoothUnion+rotateX
        // all lower together, no residue.
        String combo = renderMap("""
                requires pontif.shape.{Box, Cylinder, Torus, difference, smoothUnion, rotateX, render}
                render(smoothUnion(
                  difference(Box(1.0, 1.0, 1.0), Cylinder(0.55, 2.0)),
                  rotateX(Torus(1.15, 0.18), 90.0, {0.0, 0.0, 0.0}),
                  0.1))""");
        assertFalse(combo.contains("distanceAt") || combo.contains("this.") || combo.contains("pontif."),
                () -> "render-primitives example lowers with no residue: " + combo);
    }

    /** Smooth boolean modifiers lower too (clamp/mix over the two children). */
    @Test
    void render_lowersSmoothBooleans() {
        String map = renderMap("""
                requires pontif.shape.{Sphere, translate, smoothDifference, render}
                render(smoothDifference(Sphere(1.2), translate(Sphere(0.8), {0.7, 0.0, 0.0}), 0.3))""");
        assertTrue(map.contains("clamp(") && map.contains("mix("),
                () -> "smoothDifference uses clamp/mix: " + map);
        assertFalse(map.contains("distanceAt") || map.contains("this."),
                () -> "no Pontif residue: " + map);
    }

    /** Runs {@code src} with the renderer stubbed and returns the emitted `float map(…)` string. */
    private static String renderMap(String src) {
        RecordValue ray = onlyLayer(layersOf(src, "render.ptf"));
        return ((StringValue) ray.members().get("map")).content();
    }

    /** Pins the README "3D shapes" CSG + transforms snippet (verbatim, minus the comments). */
    @Test
    void readmeSnippet_csgComposePreviews() {
        layersOf("""
                requires pontif.shape.{Sphere, translate, rotateY, difference, render}

                main ( render(rotateY(
                  difference(Sphere(1.2), translate(Sphere(0.8), {0.9, 0.0, 0.0})),
                  30.0, {0.0, 0.0, 0.0})) )""", "readme-csg.ptf");
    }

    /** Pins the README "3D shapes" attribute-field snippet, plus the value its comment claims. */
    @Test
    void readmeSnippet_attributeField() {
        layersOf("""
                requires pontif.shape.{Sphere, ScalarField, attr, shapeOf, attrAt, render}

                struct Height()
                assign trait Height:ScalarField { valueAt(x:Decimal, y:Decimal, z:Decimal):Decimal -> z }

                main ( render(shapeOf(attr(Sphere(1.0), "height", Height()))) )""", "readme-attr.ptf");

        // The README comment claims attrAt(ball, 0.0, 0.0, 0.5) == 0.5 — pin it. No layer here: the
        // program is a comparison, so it never reaches the renderer.
        Extensions.install(new ShapeExtension());
        PontifRunner.RunResult v = new PontifRunner().run(new PontifCompiler().compile("""
                requires pontif.shape.{Sphere, ScalarField, attr, attrAt}
                struct Height()
                assign trait Height:ScalarField { valueAt(x:Decimal, y:Decimal, z:Decimal):Decimal -> z }
                attrAt(attr(Sphere(1.0), "height", Height()), 0.0, 0.0, 0.5) == 0.5""",
                "readme-attr-val.ptf"), PontifRunner.Engine.INTERPRETER);
        assertEquals("true", v.text(), () -> "attrAt should sample the field; got " + v.text());
    }
}

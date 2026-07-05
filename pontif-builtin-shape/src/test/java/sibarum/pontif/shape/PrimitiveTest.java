package sibarum.pontif.shape;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The SDF primitive roster beyond {@code Sphere} (docs/shapes.md): {@code Box}, {@code Torus},
 * {@code Cylinder}, {@code Capsule}, {@code Plane}. Pure SDF-algebra checks via {@code distanceAt}
 * at points chosen so every distance is exact (no irrational lengths → no tolerance). A negative
 * expected value {@code -v} is checked as {@code (d + v) == 0.0} to avoid a negative literal. Each
 * primitive also renders on the GPU for free (the general lowerer reads these same bodies) — see
 * {@code ShapeExtensionTest}.
 */
class PrimitiveTest {

    private static final String IMPORTS =
            "requires pontif.shape.{Box, Torus, Cylinder, Capsule, Plane, distanceAt}\n";

    private static String eval(String expr) {
        Extensions.install(new ShapeExtension());
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt(IMPORTS + expr, "prim.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "program should run; got " + r.text());
        return r.text();
    }

    @Test
    void box_exteriorFaceInteriorAndCenter() {
        // Box half-extents (1,1,1). At (2,0,0): 1 past the +x face → 1. At origin: deepest inside
        // → -1 (nearest face 1 away). Both exact (axis-aligned, no diagonal).
        assertEquals("true", eval("distanceAt(Box(1.0, 1.0, 1.0), 2.0, 0.0, 0.0) == 1.0"));
        assertEquals("true", eval("(distanceAt(Box(1.0, 1.0, 1.0), 0.0, 0.0, 0.0) + 1.0) == 0.0"));
    }

    @Test
    void torus_ringSurfaceAndHole() {
        // Torus major 2, minor 1 (XZ plane). On the ring centre-circle (2,0,0) → -1 (tube centre).
        // On the outer surface (3,0,0) → 0. At the hole centre (origin) → 1.
        assertEquals("true", eval("(distanceAt(Torus(2.0, 1.0), 2.0, 0.0, 0.0) + 1.0) == 0.0"));
        assertEquals("true", eval("distanceAt(Torus(2.0, 1.0), 3.0, 0.0, 0.0) == 0.0"));
        assertEquals("true", eval("distanceAt(Torus(2.0, 1.0), 0.0, 0.0, 0.0) == 1.0"));
    }

    @Test
    void cylinder_sideCapAndCenter() {
        // Cylinder radius 1, half-height 2 (spans y∈[-2,2]) along Y. Beside it (2,0,0) → 1 (radial).
        // Above the cap (0,3,0) → 1 (axial). At the axis centre → -1 (radius the nearer face).
        assertEquals("true", eval("distanceAt(Cylinder(1.0, 2.0), 2.0, 0.0, 0.0) == 1.0"));
        assertEquals("true", eval("distanceAt(Cylinder(1.0, 2.0), 0.0, 3.0, 0.0) == 1.0"));
        assertEquals("true", eval("(distanceAt(Cylinder(1.0, 2.0), 0.0, 0.0, 0.0) + 1.0) == 0.0"));
    }

    @Test
    void capsule_sideEndcapAndCenter() {
        // Capsule radius 1, height 2 (segment y∈[-2,2]) along Y. Beside the segment (2,0,0) → 1.
        // Past the top endcap (0,4,0): 2 beyond the y=2 cap → 1. On the axis → -1.
        assertEquals("true", eval("distanceAt(Capsule(1.0, 2.0), 2.0, 0.0, 0.0) == 1.0"));
        assertEquals("true", eval("distanceAt(Capsule(1.0, 2.0), 0.0, 4.0, 0.0) == 1.0"));
        assertEquals("true", eval("(distanceAt(Capsule(1.0, 2.0), 0.0, 0.0, 0.0) + 1.0) == 0.0"));
    }

    @Test
    void plane_signedDistanceAlongNormal() {
        // The y=0 plane (normal +y, offset 0): signed height. Above → +3; below → -2.
        assertEquals("true", eval("distanceAt(Plane(0.0, 1.0, 0.0, 0.0), 0.0, 3.0, 0.0) == 3.0"));
        assertEquals("true", eval("(distanceAt(Plane(0.0, 1.0, 0.0, 0.0), 5.0, 0.0 - 2.0, 7.0) + 2.0) == 0.0"));
    }
}

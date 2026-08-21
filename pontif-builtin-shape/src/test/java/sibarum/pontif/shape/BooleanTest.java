package sibarum.pontif.shape;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * S3 boolean modifiers / CSG (docs/shapes.md §(4)) — {@code union} / {@code intersect} /
 * {@code difference} / {@code smoothUnion} as min/max over signed distances, each a composable
 * {@code SdfShape}. Pure SDF-algebra checks via {@code distanceAt} (no rendering). Expected-negative
 * distances are checked as {@code (d + k) == 0.0} to avoid a negative literal; every value here is
 * exact (perfect-square radii), so no tolerance is needed.
 */
class BooleanTest {

    private static final String IMPORTS =
            "requires pontif.shape.{Sphere, translate, union, intersect, difference,"
            + " smoothUnion, smoothIntersect, smoothDifference, distanceAt}\n";

    private static String eval(String expr) {
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compile(IMPORTS + expr, "csg.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "program should run; got " + r.text());
        return r.text();
    }

    // Two unit spheres, one at the origin and one at (3,0,0) (disjoint).
    private static final String A = "Sphere(1.0)";
    private static final String B = "translate(Sphere(1.0), {3.0, 0.0, 0.0})";
    // Two overlapping radius-1.5 spheres, at the origin and (2,0,0).
    private static final String C = "Sphere(1.5)";
    private static final String D = "translate(Sphere(1.5), {2.0, 0.0, 0.0})";

    @Test
    void union_takesTheNearerSurface() {
        Extensions.install(new ShapeExtension());
        // Inside A → -1 (A is nearer). In the gap at (1.5,0,0) → 0.5 (outside both).
        assertEquals("true", eval("(distanceAt(union(" + A + ", " + B + "), 0.0, 0.0, 0.0) + 1.0) == 0.0"));
        assertEquals("true", eval("distanceAt(union(" + A + ", " + B + "), 1.5, 0.0, 0.0) == 0.5"));
    }

    @Test
    void intersect_isInsideBoth() {
        Extensions.install(new ShapeExtension());
        // (1,0,0) is inside both radius-1.5 spheres → -0.5. The origin is outside D → 0.5.
        assertEquals("true", eval("(distanceAt(intersect(" + C + ", " + D + "), 1.0, 0.0, 0.0) + 0.5) == 0.0"));
        assertEquals("true", eval("distanceAt(intersect(" + C + ", " + D + "), 0.0, 0.0, 0.0) == 0.5"));
    }

    @Test
    void difference_carvesTheSecondOutOfTheFirst() {
        Extensions.install(new ShapeExtension());
        // Origin is inside C and outside D → kept, -0.5. (1,0,0) is inside D → carved away, 0.5.
        assertEquals("true", eval("(distanceAt(difference(" + C + ", " + D + "), 0.0, 0.0, 0.0) + 0.5) == 0.0"));
        assertEquals("true", eval("distanceAt(difference(" + C + ", " + D + "), 1.0, 0.0, 0.0) == 0.5"));
    }

    @Test
    void smoothUnion_reducesToUnionAwayFromTheSeam() {
        Extensions.install(new ShapeExtension());
        // Deep inside A, far from B, the smin saturates to the plain min → -1 exactly.
        assertEquals("true", eval("(distanceAt(smoothUnion(" + A + ", " + B + ", 0.5), 0.0, 0.0, 0.0) + 1.0) == 0.0"));
    }

    @Test
    void smoothIntersect_reducesToIntersectAwayFromTheSeam() {
        Extensions.install(new ShapeExtension());
        // At the origin (outside D, well past the seam) the smax saturates to the plain max → 0.5.
        assertEquals("true", eval("distanceAt(smoothIntersect(" + C + ", " + D + ", 0.5), 0.0, 0.0, 0.0) == 0.5"));
    }

    @Test
    void smoothDifference_reducesToDifferenceAwayFromTheSeam() {
        Extensions.install(new ShapeExtension());
        // Away from the groove, smoothDifference matches difference: origin kept (-0.5),
        // (1,0,0) carved (0.5).
        assertEquals("true", eval("(distanceAt(smoothDifference(" + C + ", " + D + ", 0.5), 0.0, 0.0, 0.0) + 0.5) == 0.0"));
        assertEquals("true", eval("distanceAt(smoothDifference(" + C + ", " + D + ", 0.5), 1.0, 0.0, 0.0) == 0.5"));
    }
}

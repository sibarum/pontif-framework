package sibarum.pontif.shape;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * S2 transforms (docs/shapes.md) — {@code translate} / {@code scale} / {@code rotate} return
 * composable {@code SdfShape}s. Pure SDF-algebra checks (no rendering, no dasum): evaluate
 * {@code distanceAt} on a transformed shape and confirm the surface moved / scaled / rotated as
 * expected. A comparison yields a {@code Bool}, printed as {@code "true"}/{@code "false"};
 * expected-negative distances are checked as {@code (d + k) == 0.0} to avoid a negative literal in
 * the comparison. Also exercises trait-typed struct fields ({@code inner:[SdfShape]}) and nesting.
 */
class TransformTest {

    private static final String IMPORTS =
            "requires pontif.shape.{Sphere, translate, scale, rotateY, distanceAt}\n"
          + "requires pontif.math.{abs}\n";

    private static String eval(String expr) {
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt(IMPORTS + expr, "xform.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "program should run; got " + r.text());
        return r.text();
    }

    @Test
    void translate_movesTheSphere() {
        Extensions.install(new ShapeExtension());
        // Sphere(1) moved to (2,0,0): at the origin the distance is 2-1 = 1; at the new centre, -1.
        assertEquals("true", eval(
                "distanceAt(translate(Sphere(1.0), {2.0, 0.0, 0.0}), 0.0, 0.0, 0.0) == 1.0"));
        assertEquals("true", eval(
                "(distanceAt(translate(Sphere(1.0), {2.0, 0.0, 0.0}), 2.0, 0.0, 0.0) + 1.0) == 0.0"));
    }

    @Test
    void scale_growsTheSphereAboutTheAnchor() {
        Extensions.install(new ShapeExtension());
        // Sphere(1) scaled x2 about the origin → radius 2: surface at (2,0,0) (distance 0), centre -2.
        assertEquals("true", eval(
                "distanceAt(scale(Sphere(1.0), 2.0, {0.0, 0.0, 0.0}), 2.0, 0.0, 0.0) == 0.0"));
        assertEquals("true", eval(
                "(distanceAt(scale(Sphere(1.0), 2.0, {0.0, 0.0, 0.0}), 0.0, 0.0, 0.0) + 2.0) == 0.0"));
    }

    @Test
    void rotate_isRigid_leavesASymmetricSphereUnchanged() {
        Extensions.install(new ShapeExtension());
        // Rotating a sphere about the origin can't change any distance (it's rotation-invariant);
        // this checks the rotation is a rigid motion (no metric distortion). Tolerance: cos/sin of
        // 45° carry tiny float error.
        assertEquals("true", eval(
                "abs(distanceAt(rotateY(Sphere(1.0), 45.0, {0.0, 0.0, 0.0}), 2.0, 0.0, 0.0) - 1.0) < 0.0001"));
    }

    @Test
    void rotate_actuallyTurns_movesAnOffCentreShape() {
        Extensions.install(new ShapeExtension());
        // Sphere(1) translated to (2,0,0), then rotated 90° about Y about the origin, lands its
        // centre at (0,0,-2). So the distance there is -1 (composition + real rotation).
        assertEquals("true", eval(
                "abs(distanceAt(rotateY(translate(Sphere(1.0), {2.0, 0.0, 0.0}), 90.0, {0.0, 0.0, 0.0}),"
              + " 0.0, 0.0, -2.0) + 1.0) < 0.001"));
    }
}

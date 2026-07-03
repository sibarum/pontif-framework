package sibarum.pontif.shape;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;
import sibarum.pontif.runtime.module.Extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * S4 attribute fields (docs/shapes.md §(2), requirement 2) — "vertex data" attached as a named
 * FIELD over the domain, not a per-vertex array. A field is a {@code ScalarField} (a value defined
 * by a method, like a shape's distance); {@code attr} attaches it by name; {@code attrAt} evaluates
 * it at a point (what topologize will sample onto each generated vertex in S6). Pure SDF-algebra
 * checks (no rendering). The "not per-vertex" property is structural: the API offers only
 * field-attach and point-eval — there is no vertex-index operation to test for.
 */
class AttributeTest {

    // A field whose value is the z coordinate ("height"), plus the shape API.
    private static final String IMPORTS =
            "requires pontif.shape.{Sphere, ScalarField, attr, shapeOf, attrName, attrAt, distanceAt}\n"
          + "struct Height()\n"
          + "assign trait Height:ScalarField { valueAt(x:Decimal, y:Decimal, z:Decimal):Decimal -> z }\n";

    private static String eval(String expr) {
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt(IMPORTS + expr, "attr.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "program should run; got " + r.text());
        return r.text();
    }

    @Test
    void attr_evaluatesTheFieldAtAPoint() {
        Extensions.install(new ShapeExtension());
        // The "height" field attached to a sphere returns z at any queried point — it is a function
        // of position, evaluated on demand, NOT a stored per-vertex value.
        assertEquals("true", eval("attrAt(attr(Sphere(1.0), \"height\", Height()), 0.0, 0.0, 5.0) == 5.0"));
        assertEquals("true", eval("attrAt(attr(Sphere(1.0), \"height\", Height()), 0.0, 0.0, 0.0) == 0.0"));
    }

    @Test
    void attr_remembersTheName() {
        Extensions.install(new ShapeExtension());
        assertEquals("true", eval("attrName(attr(Sphere(1.0), \"height\", Height())) == \"height\""));
    }

    @Test
    void attributedShape_keepsItsGeometry() {
        Extensions.install(new ShapeExtension());
        // Attaching a field doesn't change the geometry: shapeOf gives back the original shape, so
        // it previews / topologizes identically. (radius-1 sphere: centre distance = -1.)
        assertEquals("true", eval("(distanceAt(shapeOf(attr(Sphere(1.0), \"height\", Height())), 0.0, 0.0, 0.0) + 1.0) == 0.0"));
    }
}

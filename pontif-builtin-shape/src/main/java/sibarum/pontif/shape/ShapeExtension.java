package sibarum.pontif.shape;

import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.module.Extension;

import java.util.Map;

/**
 * The shape-composition extension (docs/shapes.md) — SDF shape primitives with two ways to view
 * them. A type becomes a shape by assigning the {@code SdfShape} trait and implementing its
 * signed-distance projection; {@code render} lowers that field to a GLSL {@code float map(vec3 p)}
 * and hands it to Dasum's raymarch layer for a crisp sphere-traced <b>surface</b> (docs/sdf-glsl.md),
 * while {@code previewGradientField} samples the field <b>Pontif-side</b> on a 24³ grid and hands the
 * numbers to {@code pontif.plot}'s volumetric renderer as a {@code Volume} layer — a glowing view of
 * the gradient field, not a solid surface. Primitives, boolean modifiers, and user SDF surfaces all
 * share the one {@code SdfShape} trait (docs/shapes.md §The spine).
 *
 * <p>The only native this extension declares is {@link SdfGlsl#map sdfMap} (the GLSL lowering behind
 * {@code render}); {@code previewGradientField} composes existing {@code pontif.plot} functions and
 * crosses only sampled numbers. Meshing (topologize), attribute fields, and PLY export are later slices.
 *
 * <pre>
 *   requires pontif.shape.{Sphere, render}
 *   main ( render(Sphere(1.0)) )
 * </pre>
 */
public final class ShapeExtension implements Extension {

    @Override
    public String moduleName() {
        return "pontif.shape";
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        // The one native: lower a shape's SDF to a GLSL `float map`. Generated interpreter-side
        // (it needs the shape value); only the resulting string crosses to dasum (docs/sdf-glsl.md).
        return Map.of("sdfMap", SdfGlsl::map);
    }
}

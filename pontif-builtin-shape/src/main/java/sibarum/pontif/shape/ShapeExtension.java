package sibarum.pontif.shape;

import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.module.Extension;

import java.util.Map;

/**
 * The shape-composition extension (docs/shapes.md) — SDF shape primitives with two ways to view
 * them. A type becomes a shape by assigning the {@code SdfShape} trait and implementing its
 * signed-distance projection; {@code raymarch} lowers that field to a GLSL {@code float map(vec3 p)}
 * paired with the shape's world box, everything a renderer needs for a crisp sphere-traced
 * <b>surface</b> (docs/sdf-glsl.md), while {@code gradientField} samples the field <b>Pontif-side</b>
 * on a 24³ grid clamped to a shell — a glowing view of the gradient field, not a solid surface.
 * Primitives, boolean modifiers, and user SDF surfaces all share the one {@code SdfShape} trait
 * (docs/shapes.md §The spine).
 *
 * <p><b>Neither view draws.</b> Each is a value the module returns, so {@code pontif.shape} names no
 * renderer and needs none present to link, run or be tested — the dependency points renderer → shape.
 * It used to point the other way, and the cost was that {@code distanceAt}, which touches no pixels,
 * would not link without a windowing toolkit on the classpath.
 *
 * <p>The only native this extension declares is {@link SdfGlsl#map sdfMap} (the GLSL lowering behind
 * {@code raymarch}); {@code gradientField} is entirely Pontif. Meshing (topologize), attribute
 * fields, and PLY export are later slices.
 *
 * <pre>
 *   requires pontif.shape.{Sphere, raymarch}
 *   main ( raymarch(Sphere(1.0)) )
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
        // (it needs the shape value); only the resulting string crosses to the renderer (docs/sdf-glsl.md).
        return Map.of("sdfMap", SdfGlsl::map);
    }
}

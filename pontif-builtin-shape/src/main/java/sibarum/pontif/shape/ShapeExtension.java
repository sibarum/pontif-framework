package sibarum.pontif.shape;

import sibarum.pontif.runtime.module.Extension;

/**
 * The shape-composition extension (docs/shapes.md), slice <b>S1</b> — SDF shape primitives + a
 * live preview. A type becomes a shape by assigning the {@code SdfShape} trait and implementing
 * its signed-distance projection; {@code preview} samples that field <b>Pontif-side</b> on a 24³
 * grid and hands the numbers to {@code pontif.plot}'s volumetric renderer as a {@code Volume}
 * layer. Primitives, boolean modifiers, and user SDF surfaces will all share the one
 * {@code SdfShape} trait (docs/shapes.md §The spine).
 *
 * <p><b>No new native code.</b> The render path is entirely reused from the plotting extension
 * (docs/shapes.md §Live preview, sampled path (a)): only the sampled distances cross the boundary,
 * so this extension declares no {@link #calls()} of its own — {@code preview} composes existing
 * {@code pontif.plot} functions. Meshing (topologize), attribute fields, and PLY export are later
 * slices.
 *
 * <pre>
 *   requires pontif.shape.{Sphere, preview}
 *   main ( preview(Sphere(1.0)) )
 * </pre>
 */
public final class ShapeExtension implements Extension {

    @Override
    public String moduleName() {
        return "pontif.shape";
    }

    @Override
    public String pontifSource() {
        return """
                requires pontif.core.{Stream}
                requires pontif.math.{sqrt, clamp}
                requires pontif.plot.{Volume, scene}
                exports @.{SdfShape, Sphere, preview}

                # An implicit-surface shape: the SIGNED DISTANCE to its surface at any point
                # (negative inside, zero on the surface, positive outside), plus an axis-aligned
                # sample box {xlo,xhi,ylo,yhi,zlo,zhi}. Assign it to your type and implement the
                # projection; primitives, boolean modifiers and user SDF surfaces all share this
                # one trait (docs/shapes.md §The spine).
                trait SdfShape{
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal,
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}]
                }

                # The first primitive: a sphere of the given radius, centred at the origin. Its SDF
                # is the classic length(p) - r; the sample box pads out to twice the radius.
                struct Sphere(radius:Decimal)
                assign trait Sphere:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> sqrt(x * x + y * y + z * z) - this.radius
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                    let e = 2.0 * this.radius
                    {0.0 - e, e, 0.0 - e, e, 0.0 - e, e}
                  )
                }

                # 24*24*24 grid indices for the sampled preview (x = i%24, y = (i/24)%24, z = i/576).
                let sdfIndices:Stream[Int:0 <= @ < 13824];

                # The trait-method call routed through a top-level function: `s.distance(...)`
                # resolves in an ordinary function body but not inside a stream fragment, so the
                # fragment calls this instead (the same workaround pontif.plot uses for volumeAt).
                function distanceAt(s:[SdfShape], x:Decimal, y:Decimal, z:Decimal):Decimal -> s.distance(x, y, z)

                # Live preview (docs/shapes.md S1, sampled path (a)): sample the signed distance
                # field on a 24^3 grid over the shape's bounds, but CLAMP it to a thin band around
                # the surface before handing it to pontif.plot's volumetric renderer. That renderer
                # lights each voxel by its gradient MAGNITUDE — and a raw SDF has unit gradient
                # everywhere, so it would glow as a solid box. Clamping flattens the field outside
                # |sdf| <= band (gradient 0 → transparent) and leaves it varying only across the
                # surface shell (gradient 1 → lit), so what glows IS the surface, tinted by its
                # normal direction. No meshing (a crisp analytic trace is a later slice); only the
                # sampled numbers cross to the native renderer.
                function preview(s:[SdfShape]):Stream[String] -> (
                  let [{xlo, xhi, ylo, yhi, zlo, zhi}] = s.bounds()
                  let dx = (xhi - xlo) / 23.0
                  let dy = (yhi - ylo) / 23.0
                  let dz = (zhi - zlo) / 23.0
                  let band = 2.0 * dx
                  let vs = &sdfIndices:[ (i:Int) ->
                    clamp(distanceAt(s, xlo + (i % 24) * dx, ylo + ((i / 24) % 24) * dy, zlo + (i / 576) * dz),
                          0.0 - band, band) ]
                  scene({title = "shape"}, {Volume(vs, xlo, xhi, ylo, yhi, zlo, zhi, 0.3, false, 3)})
                )

                0
                """;
    }
}

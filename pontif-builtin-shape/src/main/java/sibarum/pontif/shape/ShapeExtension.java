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
    public String pontifSource() {
        return """
                requires pontif.core.{Stream}
                requires pontif.math.{sqrt, abs, clamp, sin, cos, radians, min, max, mix}
                requires pontif.plot.{Volume, scene}
                exports @.{SdfShape, Sphere, Box, Torus, Cylinder, Capsule, Plane,
                           previewGradientField, render, distanceAt,
                           translate, scale, rotateX, rotateY, rotateZ,
                           union, difference, intersect,
                           smoothUnion, smoothIntersect, smoothDifference,
                           ScalarField, Attributed, attr, shapeOf, attrName, attrAt}

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

                # --- More primitives (docs/shapes.md) --------------------------------------------
                # Each is a struct of SCALAR fields (so `this.<field>` inlines as a GLSL literal),
                # with the analytic SDF written in scalar form (no vec type — Pontif shapes take
                # x,y,z scalars). All render on the GPU for free: the general lowerer reads these
                # very distance bodies (docs/sdf-glsl.md). Centred at the origin; axis-aligned.

                # An axis-aligned box of half-extents (hx,hy,hz): the exact exterior+interior SDF.
                struct Box(hx:Decimal, hy:Decimal, hz:Decimal)
                assign trait Box:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> (
                    let qx = abs(x) - this.hx
                    let qy = abs(y) - this.hy
                    let qz = abs(z) - this.hz
                    let ex = max(qx, 0.0)
                    let ey = max(qy, 0.0)
                    let ez = max(qz, 0.0)
                    sqrt(ex * ex + ey * ey + ez * ez) + min(max(qx, max(qy, qz)), 0.0)
                  )
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                    let ex = 2.0 * this.hx
                    let ey = 2.0 * this.hy
                    let ez = 2.0 * this.hz
                    {0.0 - ex, ex, 0.0 - ey, ey, 0.0 - ez, ez}
                  )
                }

                # A torus in the XZ plane: major radius (ring), minor radius (tube).
                struct Torus(major:Decimal, minor:Decimal)
                assign trait Torus:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> (
                    let q = sqrt(x * x + z * z) - this.major
                    sqrt(q * q + y * y) - this.minor
                  )
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                    let r = this.major + this.minor
                    let e = 2.0 * r
                    {0.0 - e, e, 0.0 - e, e, 0.0 - e, e}
                  )
                }

                # A capped cylinder along Y: `radius` across, `height` = half-height (so it spans
                # [-height, height]).
                struct Cylinder(radius:Decimal, height:Decimal)
                assign trait Cylinder:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> (
                    let dr = sqrt(x * x + z * z) - this.radius
                    let dy = abs(y) - this.height
                    let er = max(dr, 0.0)
                    let ey = max(dy, 0.0)
                    min(max(dr, dy), 0.0) + sqrt(er * er + ey * ey)
                  )
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                    let er = 2.0 * this.radius
                    let ey = 2.0 * this.height
                    {0.0 - er, er, 0.0 - ey, ey, 0.0 - er, er}
                  )
                }

                # A capsule along Y: a segment from -height to +height, inflated by `radius`.
                struct Capsule(radius:Decimal, height:Decimal)
                assign trait Capsule:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> (
                    let cy = y - clamp(y, 0.0 - this.height, this.height)
                    sqrt(x * x + cy * cy + z * z) - this.radius
                  )
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                    let er = 2.0 * this.radius
                    let ey = 2.0 * (this.height + this.radius)
                    {0.0 - er, er, 0.0 - ey, ey, 0.0 - er, er}
                  )
                }

                # A plane with (assumed-unit) normal (nx,ny,nz) at signed `offset` from the origin.
                # Infinite in extent; the sample box is a generous finite slab for preview/render.
                struct Plane(nx:Decimal, ny:Decimal, nz:Decimal, offset:Decimal)
                assign trait Plane:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal ->
                    x * this.nx + y * this.ny + z * this.nz + this.offset
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] ->
                    {0.0 - 4.0, 4.0, 0.0 - 4.0, 4.0, 0.0 - 4.0, 4.0}
                }

                # 24*24*24 grid indices for the sampled preview (x = i%24, y = (i/24)%24, z = i/576).
                let sdfIndices:Stream[Int:0 <= @ < 13824];

                # The trait-method call routed through a top-level function: `s.distance(...)`
                # resolves in an ordinary function body but not inside a stream fragment, so the
                # fragment calls this instead (the same workaround pontif.plot uses for volumeAt).
                function distanceAt(s:[SdfShape], x:Decimal, y:Decimal, z:Decimal):Decimal -> s.distance(x, y, z)

                # Gradient-field preview (docs/shapes.md S1, sampled path (a)): visualize the shape's
                # SDF as a VOLUMETRIC GRADIENT FIELD — sample the signed distance on a 24^3 grid over
                # the shape's bounds, CLAMP it to a thin band around the surface, and hand it to
                # pontif.plot's volumetric renderer. That renderer lights each voxel by its gradient
                # MAGNITUDE and tints it by gradient DIRECTION — and a raw SDF has unit gradient
                # everywhere, so it would glow as a solid box. Clamping flattens the field outside
                # |sdf| <= band (gradient 0 → transparent) and leaves it varying only across the
                # surface shell (gradient 1 → lit), so what glows is the surface shell, tinted by its
                # normal. This is NOT a solid-surface view — use `render` for a crisp sphere-traced
                # surface; this shows the gradient field itself. Only the sampled numbers cross to the
                # native renderer.
                #
                # `opacity` is the shell's glow intensity (the Volume layer's additive alpha); lower
                # is subtler. The one-arg form uses a sensible default; pass a second argument to tune
                # it, e.g. previewGradientField(Sphere(1.0), 0.05).
                function previewGradientField(s:[SdfShape]):Stream[String] -> gradientFieldGlow(s, 0.1)

                function previewGradientField(s:[SdfShape], opacity:Decimal):Stream[String] -> gradientFieldGlow(s, opacity)

                # (internal) shared body: sample + clamp the SDF shell, render the gradient field at `opacity`.
                function gradientFieldGlow(s:[SdfShape], opacity:Decimal):Stream[String] -> (
                  let [{xlo, xhi, ylo, yhi, zlo, zhi}] = s.bounds()
                  let dx = (xhi - xlo) / 23.0
                  let dy = (yhi - ylo) / 23.0
                  let dz = (zhi - zlo) / 23.0
                  let band = 2.0 * dx
                  let vs = &sdfIndices:[ (i:Int) ->
                    clamp(distanceAt(s, xlo + (i % 24) * dx, ylo + ((i / 24) % 24) * dy, zlo + (i / 576) * dz),
                          0.0 - band, band) ]
                  scene({title = "shape"}, {Volume(vs, xlo, xhi, ylo, yhi, zlo, zhi, opacity, false, 3)})
                )

                # --- GPU render (docs/sdf-glsl.md) -------------------------------------------------
                # `render` lowers the shape's signed-distance function to a GLSL `float map(vec3 p)`
                # (native `sdfMap`, generated interpreter-side) and hands it to Dasum's raymarch
                # layer with the shape's world-space bounding box — a real sphere-traced surface,
                # not the sampled glow of `preview`. The GLSL crosses the boundary as inert text.

                # A raymarch layer: a GLSL `map` shader + the world AABB (center ± half-extent).
                struct Raymarch(map:String,
                                cx:Decimal, cy:Decimal, cz:Decimal,
                                hx:Decimal, hy:Decimal, hz:Decimal)

                # (native) Lower a shape to GLSL `float map(vec3 p){…}`. Backed by SdfGlsl::map.
                function sdfMap(s:[SdfShape]):String -> ""

                function render(s:[SdfShape]):Stream[String] -> (
                  let [{xlo, xhi, ylo, yhi, zlo, zhi}] = s.bounds()
                  let cx = (xlo + xhi) / 2.0
                  let cy = (ylo + yhi) / 2.0
                  let cz = (zlo + zhi) / 2.0
                  let hx = (xhi - xlo) / 2.0
                  let hy = (yhi - ylo) / 2.0
                  let hz = (zhi - zlo) / 2.0
                  scene({title = "shape"}, {Raymarch(sdfMap(s), cx, cy, cz, hx, hy, hz)})
                )

                # --- Transforms (docs/shapes.md S2) ------------------------------------------------
                # Each transform returns a new SdfShape (declared return [SdfShape], so the wrapper's
                # trait-satisfaction stays module-internal and callers never name it), which means
                # transforms compose and preview like any shape. The transformed SDF queries the
                # INNER shape at the inverse-transformed point.

                # Inner-shape bounds routed through a top-level function (a trait-method call).
                function boundsOf(s:[SdfShape]):[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> s.bounds()

                # Translate by {dx,dy,dz}: query the inner shape at the back-shifted point (a rigid
                # move leaves distances unchanged); the bounds shift with it.
                struct Translated(inner:[SdfShape], dx:Decimal, dy:Decimal, dz:Decimal)
                assign trait Translated:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal ->
                    distanceAt(this.inner, x - this.dx, y - this.dy, z - this.dz)
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                    let [{xlo, xhi, ylo, yhi, zlo, zhi}] = boundsOf(this.inner)
                    {xlo + this.dx, xhi + this.dx, ylo + this.dy, yhi + this.dy, zlo + this.dz, zhi + this.dz}
                  )
                }
                function translate(s:[SdfShape], by:[{Decimal,Decimal,Decimal}]):[SdfShape] -> (
                  let [{dx, dy, dz}] = by
                  Translated(s, dx, dy, dz)
                )

                # Uniform scale by `factor` about the anchor `a` (the pivot): query the inner shape at
                # a + (p - a)/factor, then multiply the distance by factor — a uniform scale stretches
                # the metric by the same factor, so the SDF stays exact.
                struct Scaled(inner:[SdfShape], factor:Decimal, ax:Decimal, ay:Decimal, az:Decimal)
                assign trait Scaled:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> (
                    let ix = this.ax + (x - this.ax) / this.factor
                    let iy = this.ay + (y - this.ay) / this.factor
                    let iz = this.az + (z - this.az) / this.factor
                    distanceAt(this.inner, ix, iy, iz) * this.factor
                  )
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                    let [{xlo, xhi, ylo, yhi, zlo, zhi}] = boundsOf(this.inner)
                    {this.ax + (xlo - this.ax) * this.factor, this.ax + (xhi - this.ax) * this.factor,
                     this.ay + (ylo - this.ay) * this.factor, this.ay + (yhi - this.ay) * this.factor,
                     this.az + (zlo - this.az) * this.factor, this.az + (zhi - this.az) * this.factor}
                  )
                }
                function scale(s:[SdfShape], factor:Decimal, about:[{Decimal,Decimal,Decimal}]):[SdfShape] -> (
                  let [{ax, ay, az}] = about
                  Scaled(s, factor, ax, ay, az)
                )

                # A conservative axis-aligned box for a shape rotated by ANY angle about the anchor:
                # rotation preserves distance-from-anchor, so a cube of radius (|centre - anchor| +
                # box half-diagonal) around the anchor contains every rotated corner. Shared by all
                # three axis rotations (over-covers a little; exactness isn't needed for the sampled
                # preview, only containment).
                function rotatedBounds(xlo:Decimal, xhi:Decimal, ylo:Decimal, yhi:Decimal, zlo:Decimal, zhi:Decimal,
                                       ax:Decimal, ay:Decimal, az:Decimal):[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                  let cx = (xlo + xhi) / 2.0
                  let cy = (ylo + yhi) / 2.0
                  let cz = (zlo + zhi) / 2.0
                  let hx = (xhi - xlo) / 2.0
                  let hy = (yhi - ylo) / 2.0
                  let hz = (zhi - zlo) / 2.0
                  let centreDist = sqrt((cx - ax) * (cx - ax) + (cy - ay) * (cy - ay) + (cz - az) * (cz - az))
                  let halfDiag = sqrt(hx * hx + hy * hy + hz * hz)
                  let r = centreDist + halfDiag
                  {ax - r, ax + r, ay - r, ay + r, az - r, az + r}
                )

                # Rotate `degrees` about the axis through the anchor `a` (the pivot). A rotation is
                # rigid, so the transformed SDF is the inner SDF at the point rotated by -degrees
                # about the anchor; distances are unchanged. One struct per principal axis (arbitrary
                # axes are a later slice).

                # About the X axis: the (y,z) plane rotates, x is fixed.
                struct RotatedX(inner:[SdfShape], deg:Decimal, ax:Decimal, ay:Decimal, az:Decimal)
                assign trait RotatedX:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> (
                    let t = radians(this.deg)
                    let c = cos(t)
                    let s = sin(t)
                    let dy = y - this.ay
                    let dz = z - this.az
                    distanceAt(this.inner, x, this.ay + c * dy - s * dz, this.az + s * dy + c * dz)
                  )
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                    let [{xlo, xhi, ylo, yhi, zlo, zhi}] = boundsOf(this.inner)
                    rotatedBounds(xlo, xhi, ylo, yhi, zlo, zhi, this.ax, this.ay, this.az)
                  )
                }
                function rotateX(s:[SdfShape], degrees:Decimal, about:[{Decimal,Decimal,Decimal}]):[SdfShape] -> (
                  let [{ax, ay, az}] = about
                  RotatedX(s, degrees, ax, ay, az)
                )

                # About the Y axis: the (x,z) plane rotates, y is fixed.
                struct RotatedY(inner:[SdfShape], deg:Decimal, ax:Decimal, ay:Decimal, az:Decimal)
                assign trait RotatedY:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> (
                    let t = radians(this.deg)
                    let c = cos(t)
                    let s = sin(t)
                    let dx = x - this.ax
                    let dz = z - this.az
                    distanceAt(this.inner, this.ax + c * dx - s * dz, y, this.az + s * dx + c * dz)
                  )
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                    let [{xlo, xhi, ylo, yhi, zlo, zhi}] = boundsOf(this.inner)
                    rotatedBounds(xlo, xhi, ylo, yhi, zlo, zhi, this.ax, this.ay, this.az)
                  )
                }
                function rotateY(s:[SdfShape], degrees:Decimal, about:[{Decimal,Decimal,Decimal}]):[SdfShape] -> (
                  let [{ax, ay, az}] = about
                  RotatedY(s, degrees, ax, ay, az)
                )

                # About the Z axis: the (x,y) plane rotates, z is fixed.
                struct RotatedZ(inner:[SdfShape], deg:Decimal, ax:Decimal, ay:Decimal, az:Decimal)
                assign trait RotatedZ:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> (
                    let t = radians(this.deg)
                    let c = cos(t)
                    let s = sin(t)
                    let dx = x - this.ax
                    let dy = y - this.ay
                    distanceAt(this.inner, this.ax + c * dx - s * dy, this.ay + s * dx + c * dy, z)
                  )
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                    let [{xlo, xhi, ylo, yhi, zlo, zhi}] = boundsOf(this.inner)
                    rotatedBounds(xlo, xhi, ylo, yhi, zlo, zhi, this.ax, this.ay, this.az)
                  )
                }
                function rotateZ(s:[SdfShape], degrees:Decimal, about:[{Decimal,Decimal,Decimal}]):[SdfShape] -> (
                  let [{ax, ay, az}] = about
                  RotatedZ(s, degrees, ax, ay, az)
                )

                # --- Boolean modifiers / CSG (docs/shapes.md S4[sic §(4)]) ------------------------
                # Constructive solid geometry as min/max over the two operands' signed distances.
                # Each combinator is itself an SdfShape holding two inners, so booleans nest and
                # compose with transforms uniformly. min/max discard the losing operand's distance —
                # non-bijective, and honest about it (docs/shapes.md).

                # The combined axis-aligned box of two shapes (for union-like results).
                function unionBounds(axlo:Decimal, axhi:Decimal, aylo:Decimal, ayhi:Decimal, azlo:Decimal, azhi:Decimal,
                                     bxlo:Decimal, bxhi:Decimal, bylo:Decimal, byhi:Decimal, bzlo:Decimal, bzhi:Decimal
                                     ):[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] ->
                  {min(axlo, bxlo), max(axhi, bxhi), min(aylo, bylo), max(ayhi, byhi), min(azlo, bzlo), max(azhi, bzhi)}

                # Union (OR): the nearer surface wins — min of the two distances. Bounds = both boxes.
                struct Union(a:[SdfShape], b:[SdfShape])
                assign trait Union:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal ->
                    min(distanceAt(this.a, x, y, z), distanceAt(this.b, x, y, z))
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                    let [{axlo, axhi, aylo, ayhi, azlo, azhi}] = boundsOf(this.a)
                    let [{bxlo, bxhi, bylo, byhi, bzlo, bzhi}] = boundsOf(this.b)
                    unionBounds(axlo, axhi, aylo, ayhi, azlo, azhi, bxlo, bxhi, bylo, byhi, bzlo, bzhi)
                  )
                }
                function union(a:[SdfShape], b:[SdfShape]):[SdfShape] -> Union(a, b)

                # Intersection (AND): inside both — max of the two distances. Bounds ⊆ a (conservative).
                struct Intersect(a:[SdfShape], b:[SdfShape])
                assign trait Intersect:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal ->
                    max(distanceAt(this.a, x, y, z), distanceAt(this.b, x, y, z))
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> boundsOf(this.a)
                }
                function intersect(a:[SdfShape], b:[SdfShape]):[SdfShape] -> Intersect(a, b)

                # Difference (a minus b): inside a AND outside b — max(da, -db). Bounds ⊆ a.
                struct Difference(a:[SdfShape], b:[SdfShape])
                assign trait Difference:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal ->
                    max(distanceAt(this.a, x, y, z), 0.0 - distanceAt(this.b, x, y, z))
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> boundsOf(this.a)
                }
                function difference(a:[SdfShape], b:[SdfShape]):[SdfShape] -> Difference(a, b)

                # Smooth union: a filleted blend of a and b over radius k (the polynomial smin). Away
                # from the seam it is exactly the union (h saturates to 0/1); near it, the surfaces
                # merge with a k-sized fillet instead of a crease.
                struct SmoothUnion(a:[SdfShape], b:[SdfShape], k:Decimal)
                assign trait SmoothUnion:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> (
                    let da = distanceAt(this.a, x, y, z)
                    let db = distanceAt(this.b, x, y, z)
                    let h = clamp(0.5 + 0.5 * (db - da) / this.k, 0.0, 1.0)
                    mix(db, da, h) - this.k * h * (1.0 - h)
                  )
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> (
                    let [{axlo, axhi, aylo, ayhi, azlo, azhi}] = boundsOf(this.a)
                    let [{bxlo, bxhi, bylo, byhi, bzlo, bzhi}] = boundsOf(this.b)
                    unionBounds(axlo, axhi, aylo, ayhi, azlo, azhi, bxlo, bxhi, bylo, byhi, bzlo, bzhi)
                  )
                }
                function smoothUnion(a:[SdfShape], b:[SdfShape], k:Decimal):[SdfShape] -> SmoothUnion(a, b, k)

                # Smooth intersection: the filleted dual of smoothUnion (the polynomial smax). Away
                # from the seam it is exactly the intersection; near it, a k-sized rounded valley.
                struct SmoothIntersect(a:[SdfShape], b:[SdfShape], k:Decimal)
                assign trait SmoothIntersect:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> (
                    let da = distanceAt(this.a, x, y, z)
                    let db = distanceAt(this.b, x, y, z)
                    let h = clamp(0.5 - 0.5 * (db - da) / this.k, 0.0, 1.0)
                    mix(db, da, h) + this.k * h * (1.0 - h)
                  )
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> boundsOf(this.a)
                }
                function smoothIntersect(a:[SdfShape], b:[SdfShape], k:Decimal):[SdfShape] -> SmoothIntersect(a, b, k)

                # Smooth difference (a minus b): the filleted subtraction — a k-sized rounded groove
                # where b is carved out of a, instead of difference's sharp lip.
                struct SmoothDifference(a:[SdfShape], b:[SdfShape], k:Decimal)
                assign trait SmoothDifference:SdfShape {
                  distance(x:Decimal, y:Decimal, z:Decimal):Decimal -> (
                    let da = distanceAt(this.a, x, y, z)
                    let db = distanceAt(this.b, x, y, z)
                    let h = clamp(0.5 - 0.5 * (da + db) / this.k, 0.0, 1.0)
                    mix(da, 0.0 - db, h) + this.k * h * (1.0 - h)
                  )
                  bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}] -> boundsOf(this.a)
                }
                function smoothDifference(a:[SdfShape], b:[SdfShape], k:Decimal):[SdfShape] -> SmoothDifference(a, b, k)

                # --- Attribute fields (docs/shapes.md S4, requirement 2) --------------------------
                # "Vertex data" that is NOT per-vertex: a named FIELD over the domain (a value at
                # every point in space), not an array indexed by vertex — because no vertices exist
                # until topologize (S6) creates them. A field is defined by a method, exactly like a
                # shape's distance; you attach it to a shape by name, and it rides along (geometry
                # unchanged) until topologize samples it onto the vertices it makes. There is
                # deliberately NO indexed / per-vertex operation here: you cannot address data on
                # points that do not exist yet (the no-lie law, docs/shapes.md §The spine).

                # A scalar field: a value at each point. Assign it to your type and implement valueAt
                # (a weight, a temperature, one colour channel, a uv coordinate, …).
                trait ScalarField{ valueAt(x:Decimal, y:Decimal, z:Decimal):Decimal }

                # An attributed shape: the geometry bundled with one named attribute field. The
                # geometry is untouched — `shapeOf` gives it back (for preview/topologize) and
                # `attrAt` samples the field. (A bundle rather than an SdfShape wrapper so the shape
                # stays a plain [SdfShape] at every call site.)
                struct Attributed(shape:[SdfShape], name:String, field:[ScalarField])

                # Route the field's trait-method call through a top-level function (as distanceAt does).
                function fieldValue(f:[ScalarField], x:Decimal, y:Decimal, z:Decimal):Decimal -> f.valueAt(x, y, z)

                # Attach a named attribute field to a shape, producing the bundle.
                function attr(shape:[SdfShape], name:String, field:[ScalarField]):Attributed ->
                  Attributed(shape, name, field)

                # The bundled geometry (unchanged — preview/topologize it like any shape).
                function shapeOf(a:Attributed):[SdfShape] -> a.shape

                # Query the attached field: its name, and its value at a point (what topologize will
                # sample onto each generated vertex in S6).
                function attrName(a:Attributed):String -> a.name
                function attrAt(a:Attributed, x:Decimal, y:Decimal, z:Decimal):Decimal -> fieldValue(a.field, x, y, z)

                0
                """;
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        // The one native: lower a shape's SDF to a GLSL `float map`. Generated interpreter-side
        // (it needs the shape value); only the resulting string crosses to dasum (docs/sdf-glsl.md).
        return Map.of("sdfMap", SdfGlsl::map);
    }
}

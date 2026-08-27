# Shapes — SDF composition, field-attributes, topologize, export

> **RULED 2026-08-26 — a view is a value, and this module names no renderer.** `render(s)` and
> `previewGradientField(s)` used to *call* `pontif.plot`'s `scene`. They are now `raymarch(s)` and
> `gradientField(s)`, returning a `Raymarch` and a `GradientField`, and `pontif.shape` no longer
> `requires pontif.plot` at all. The dependency points **renderer → shape**.
>
> The reason is not tidiness. Line 3 of the module was `requires pontif.plot.{Volume, scene}`, so
> `distanceAt` — pure SDF algebra, touching no pixels — would not *link* without a windowing
> toolkit on the classpath. That also cost a whole test-scope stub extension standing in for a
> renderer nobody was testing; it is deleted, and `RenderLoweringTest` now reads the program's own
> result. Names below still say `render` / `previewGradientField` in the history sections; read
> them as the functions they became.

Status: **BUILDING (2026-07-03).** Scoped, ratified, **S1–S4 LANDED** (module + `SdfShape` +
`Sphere` + live preview; transforms + adjustable anchor; boolean CSG modifiers; attribute fields;
see Slices). Originally net-new; scoped
against master by reading the extension API, `PlotExtension`/`DasumBridge`, the dasum-vis
engine, and the type substrate. Sibling front to docs/plotting.md — plotting *renders* shapes;
this *authors, meshes, and exports* them. No collisions with existing modules: it lands as a new
leaf module `pontif-builtin-shape`, additive only. This is **not a war** (no breaking change to
core); it is a slice ladder.

Markers: **RULED** (settled with James) · **DERIVED** (follows from ruled material + standing
laws) · **PROPOSED** (Claude's suggestion, awaiting a ruling) · **OPEN** (undecided). At this
status almost everything is PROPOSED/OPEN — the spine is DERIVED from the no-lie law; James
red-pens the rest.

## The feature (James)

Six requirements, in order:
1. A collection of 3D shape **primitives, fully parameterized**.
2. May set **arbitrary "vertex data"** on shapes, *but not "per vertex"* — because vertices
   don't exist yet.
3. **Adjustable anchor point** for transformations.
4. **Boolean modifiers.**
5. **SDF surfaces.**
6. **Topologize**, with vertex data. Then **export to FBX**.

## The spine: one substrate, and the honest lie about vertices

These are not six features. They are **one pipeline over one substrate — the signed distance
field** — and requirement (2) is the whole thing's conscience.

- **DERIVED — primitives, boolean modifiers, and "SDF surfaces" are the same object.** A shape
  is a function `distance(x,y,z) → Decimal` (negative inside, zero on the surface, positive outside).
  A `Sphere(r)` has an analytic SDF; a boolean union is `min(a, b)`; a "SDF surface" is a
  user-supplied distance function. Requirements (1), (4), (5) collapse into **one trait**:
  ```
  trait SdfShape {
    distance(x:Decimal, y:Decimal, z:Decimal):Decimal
    bounds():[{Decimal,Decimal,Decimal,Decimal,Decimal,Decimal}]   # {xlo,xhi,ylo,yhi,zlo,zhi}
  }
  ```
  (Shipped form, S1. `distance` takes three scalars rather than a `{x,y,z}` point because tuple
  components aren't value-accessible — only destructurable — the same reason `pontif.plot` samples
  with scalar args; a point-tuple wrapper and a named `Aabb` for the bounds are later ergonomics.)
  This is the same top-down model as docs/plotting.md's `Volume3D`/`ScalarField2D` — the user (or
  the library, for primitives) implements a projection method and the machinery consumes it. No
  function-passing: every `SdfShape` subject is a **named type** (the proven G6 / plotting path).

- **DERIVED — "vertex data, but not per-vertex" is the no-lie law stated in geometry**
  ([[project_pontif_no_lie]]). A parametric / SDF shape has **no vertices to address**, so you
  cannot honestly attach per-vertex values to it — that would fabricate information about points
  that do not exist. Instead, attribute data is attached as a **field over the shape's domain** —
  a function `p → value`, i.e. a `Stream`/method, not an array. You are setting *what the value
  will be, wherever a vertex lands*, not *the value at vertex 37*. The impossibility of the
  per-vertex spelling before topology **is the design working**, not a limitation to route around.

- **DERIVED — topologize is where vertices come into being, and it is declared-lossy.** Meshing
  the SDF (marching cubes / dual contouring) produces the first honest vertices — an *indexed*
  mesh. At that instant, and only then, each attached field is **sampled at each generated
  vertex** → genuine per-vertex data. Two informations are lost here and both must be *declared*,
  never silent ([[project_conservation_receipts]], the conservation coin): the continuous surface
  → a finite triangle set, and each continuous field → point samples. The mesh is **not
  reversible** to the SDF (topologize is a `DataConservativeExcept`, not a bijection — so no
  `inverse[topologize]`, honestly; cf. [[project_inverse_synthesis]]). Boolean `min`/`max`
  likewise discard the losing operand's distance — non-bijective, and honest about it.

So the pipeline, end to end:

```
parameterized primitives           (1)  SdfShape structs
   → attach attribute fields        (2)  fields over the domain, NOT per-vertex
   → transform about an anchor       (3)  inverse-transform the query point about a pivot
   → boolean-combine (CSG)           (4)  min / max / smooth-min over distances
   → [any user SDF surface]          (5)  same SdfShape trait
   → topologize                      (6a) marching cubes → indexed mesh; fields → per-vertex
   → export                          (6b) serialize mesh + attributes  (emit / effect membrane)
```

## The model, requirement by requirement

### (1) Primitives — parameterized structs, each an `SdfShape`

Each primitive is a `struct` carrying its parameters as refined fields; the library assigns
`SdfShape` and writes the analytic distance in the method body.

```
struct Sphere(radius:[Decimal:@>0.0])
struct Box(half:{Decimal,Decimal,Decimal})
struct Cylinder(radius:[Decimal:@>0.0], height:[Decimal:@>0.0])
struct Torus(major:[Decimal:@>0.0], minor:[Decimal:@>0.0])
struct Capsule(radius:[Decimal:@>0.0], height:[Decimal:@>0.0])
struct Plane(normal:{Decimal,Decimal,Decimal}, offset:Decimal)
struct Cone(radius:[Decimal:@>0.0], height:[Decimal:@>0.0])

assign trait Sphere:SdfShape {                           # shipped in S1 (radius unrefined for now)
  distance(x, y, z) -> sqrt(x * x + y * y + z * z) - this.radius
  bounds()          -> ( let e = 2.0 * this.radius   {0.0 - e, e, 0.0 - e, e, 0.0 - e, e} )
}
```
(SHIPPED 2026-07-05: `Sphere`, `Box(hx,hy,hz)`, `Torus(major,minor)`, `Cylinder(radius,height)`,
`Capsule(radius,height)`, `Plane(nx,ny,nz,offset)` — each an `assign trait X:SdfShape` with a
scalar analytic distance body, exercised by `PrimitiveTest`, and each renders on the GPU for free
via the SDF→GLSL lowerer (docs/sdf-glsl.md — the lowerer reads these very bodies). Fields are still
plain `Decimal` (the refined `[Decimal:@>0.0]` construction-gate params remain follow-on); `Cone`
is deferred — its exact SDF is fiddly and not worth shipping approximate.)
Refined param sorts (`[Decimal:@>0.0]`) are the construction gate keeping a degenerate
primitive uninstantiable ([[project_construction_gate]]). The starter set is PROPOSED; the exact
roster is James's call.

### (2) Attribute fields — the "vertex data" that isn't per-vertex yet  *(LANDED, S4)*

A shape carries **named fields**, each a value at every point in space — a *function*, not an
array indexed by vertex. You attach one; nothing is indexed.

**Shipped (S4):** a field is a `ScalarField` — a value defined by a method, exactly like a shape's
distance. `attr` bundles a shape with a named field; `shapeOf` gives the geometry back (for
preview/topologize) and `attrAt` samples the field.
```
trait ScalarField{ valueAt(x:Decimal, y:Decimal, z:Decimal):Decimal }
struct Height() ; assign trait Height:ScalarField { valueAt(x,y,z) -> z }

let a = attr(Sphere(1.0), "height", Height())   # bundle: geometry + one named field
raymarch(shapeOf(a))                             # geometry, unchanged
attrAt(a, 0.0, 0.0, 5.0)                         # 5.0 — the field, evaluated on demand
```
A field is defined by a method (a `ScalarField`), not a first-class function value, to stay on the
proven trait / trait-typed-field mechanics (a `[Method(…):V]` field-value form is a later
ergonomic). It's a **bundle**, not an `SdfShape` wrapper, because the call gate rejects a concrete
wrapper used *directly* as `[SdfShape]` (a `Sphere` widens fine, a same-shaped wrapper does not —
noted as a gate inconsistency); `shapeOf` returns `[SdfShape]` via a return-widening, which works.

**The no-lie point.** The API offers only *attach a field* and *evaluate at a point* — there is no
vertex-index operation, because no vertices exist until `topologize` (S6). "Not per-vertex" isn't a
restriction to work around; it's structural. `topologize` samples each field at the vertices it
makes — that's when, and only when, per-vertex data becomes honest ([[project_pontif_no_lie]]).

**Color (James's example: "setting the color of the object sets the vertex colors on its
surfaces").** Exactly this model — color is a field over the object, sampled onto the vertices
`topologize` creates. Concretely, color is **multi-channel** (red/green/blue), which is three named
scalar channels — the same form PLY stores per-vertex (`property … red/green/blue`). S4 ships the
single-channel `ScalarField`; the multi-channel/color form (and multiple named fields per shape)
lands with its consumers — S6 (sample onto vertices) and S7 (write PLY channels). **OPEN:** color as
a dedicated `ColorField` (`colorAt → {r,g,b}`) vs. three named scalar channels (PLY-aligned) —
leaning the latter. PROPOSED too: surface-parameterized (uv) fields, and a `[Method(…):V]`
field-value form so a field can be written inline instead of as a named trait impl.

### (3) Anchor — the pivot transforms compose about  *(LANDED, S2)*

An **anchor** is a point; a transform (translate / rotate / scale) is applied *about* it.
- **DERIVED** — transforming an `SdfShape` is **inverse-transforming the query point** about the
  anchor: `distance_T(p) = distance(T⁻¹ · (p − anchor) + anchor) · uniformScale`. This keeps the
  SDF metric honest (a uniform scale multiplies the distance by the factor; non-uniform scale would
  distort it and is deliberately not offered).
- **Shipped (S2):** each transform is a function returning a new `SdfShape`, so they compose and
  preview like any shape; the anchor is an **explicit pivot argument** (which *is* the adjustable
  anchor point):
```
translate(s, {dx,dy,dz})            # rigid shift; distances unchanged
scale(s, factor, {ax,ay,az})        # uniform scale about the anchor; distance × factor
rotateX(s, degrees, {ax,ay,az})     # rigid rotation about the axis through the anchor
rotateY(s, degrees, {ax,ay,az})
rotateZ(s, degrees, {ax,ay,az})
# compose freely, inside-out:
translate(rotateY(scale(Sphere(1.0), 1.5, {0.0,0.0,0.0}), 45.0, {0.0,0.0,0.0}), {2.0,0.0,0.0})
```
  Internally each is a small wrapper struct holding `inner:[SdfShape]` + params; the function's
  declared return `[SdfShape]` keeps the wrapper's trait-satisfaction module-internal (callers never
  name it). Translate/scale bounds are exact; rotation bounds are a conservative anchor-centred cube
  (rotation preserves distance-from-anchor) — containment is all the sampled preview needs.
- **DEFERRED:** the stateful `anchor(shape, p); rotate(Y, deg)` sugar (a settable anchor property
  carried on the shape), a default anchor = the primitive's natural centre, and arbitrary-axis
  rotation. The explicit-pivot form above delivers the requirement without them.
- Math note: dasum ships `Vec3`/`Vec4`/`CameraMath` but **no `Mat4`**; S2 does the rotation with
  `sin`/`cos`/`radians` from `pontif.math` directly (no matrix type needed for principal axes).

### (4) Boolean modifiers — CSG over distances  *(LANDED, S3)*

```
union(a, b)          -> min(da, db)          # OR
intersect(a, b)      -> max(da, db)          # AND
difference(a, b)     -> max(da, 0.0 - db)    # a minus b
smoothUnion(a, b, k) -> polynomial smin      # k-radius filleted blend
```
Each combinator is itself an `SdfShape` holding two inners (returned as `[SdfShape]`, like the S2
transforms), so booleans nest arbitrarily and compose with transforms uniformly — the CSG *tree*.
Bounds: union/smoothUnion take the combined box; intersect/difference take `a`'s box (the result
is ⊆ `a`, conservative). **DERIVED** — `min`/`max` are not bijective (they forget the losing
branch), so a boolean is not `Reversible`. Away from the seam `smoothUnion` is exactly `union` (the
blend factor saturates); the fillet only appears within `k` of the crossing. (Note: a `max`-based
result is a *bound*, not an exact SDF, in the carved region — fine near the surface, which is all the
sampled preview and, later, marching cubes read.)

Witness: `BooleanTest` (pure SDF-algebra) checks union takes the nearer surface, intersect is inside
both, difference carves the second out of the first, and `smoothUnion` reduces to `union` away from
the seam. This is also the first *non-symmetric* preview — `examples/csg.ptf` renders a sphere with
a bite. **DEFERRED to the attribute slices:** the attribute fields of both operands surviving as the
combined shape's fields (name-clash rule) waits for S4.

### (5) SDF surfaces — the escape hatch, same trait

Any user type that implements `SdfShape.distance` *is* an SDF surface — no separate mechanism.
This is where non-primitive, non-boolean forms live (a gyroid, a metaball field, a noise-displaced
sphere). It falls out of (1) for free.

### (6a) Topologize — vertices are born, fields become per-vertex

```
let mesh = topologize(shape, {resolution = 64, iso = 0.0})
```
Produces the first **indexed mesh** in the codebase — net-new; today only flat, non-indexed
triangle-soup `float[]` exists (`DasumBridge.SurfaceMesh`, height-grid only). Proposed value:
```
struct Mesh(
  positions : Stream[{Decimal,Decimal,Decimal}],   # the vertices, now real
  triangles : Stream[{Int,Int,Int}],               # index buffer
  attrs     : Stream[NamedAttr])                    # each field, SAMPLED per vertex
```
- **Algorithm — PROPOSED: marching cubes first, dual contouring later.** Marching cubes is simple
  and robust (no sharp features); dual contouring recovers sharp edges and *needs surface normals /
  hermite data*, which the SDF supplies for free via central differences — and `DasumBridge`
  **already computes CPU-side central-difference gradients** for the volume glyph layer, so the
  gradient primitive is proven. Start MC; DC is a clean later slice.
- **Where the numeric loop runs — the one real architectural tension.** The SDF is *defined in
  Pontif*, meshing is a heavy grid loop that wants *Java*, and the house rule is **no
  function-passing across the native boundary** ([[project_extension_api]], the `PlotExtension`
  contract: only primitive arrays cross). Two resolutions:
  - **(A) sample-grid — PROPOSED for slice 1.** Evaluate the SDF Pontif-side into a dense voxel
    grid (exactly `Volume3D`'s pattern — `PlotExtension` samples a 24³ field), sample each
    attribute field onto the same grid, hand the flat arrays to `ShapeBridge`, march in Java.
    Stays strictly inside "only arrays cross." Resolution-capped and attributes are grid-then-
    interpolated, but simple and consistent.
  - **(B) serialized CSG tree — later.** The composed shape crosses as a `RecordValue` expression
    tree; Java evaluates it analytically per grid point. This is essentially what dasum's
    `CsgField`/`CsgBox` already are. Exact and fast; more marshalling. Evolve here when precision
    or perf demands.

### (6b) Export — PLY (RULED 2026-07-02)

**RULED: the export target is PLY**, superseding the "FBX" in the original ask (§The feature).
James's rationale: *"PLY is the atom of 3D geometry. Material assignments and scene graphs would be
proprietary; PLY abstracts exactly the part that is portable and standard, with enough flexibility
to go off-script when necessary."*

This is the no-lie law choosing the format. PLY's schema is **self-describing in the file** — the
header declares named, typed properties per element — so arbitrary per-vertex attributes are the
format's *native* design, not a hack or a fixed slot, and conformant readers round-trip them
generically. It is **indexed** (a vertex list + a face-index list), so it preserves the topology
`topologize` just created (unlike STL's triangle soup). And it carries *only* geometry + attributes
— no materials, textures, scene graph, or animation, every one of which is per-tool proprietary and
would force a lie about portability. The "off-script" escape is a first-class custom property
(`property float32 <anyName>`), the exact home for an attribute field of any value sort.

So **PLY is essentially the on-disk image of the `Mesh` struct** — `positions` → vertex `x/y/z`,
`triangles` → the face index list, each `attr` → a named vertex property. Export is close to a
direct serialization: a header string plus flat arrays, the smallest honest writer of any
candidate.

Serialization is a **side-effect**, so it rides the extension membrane as an `emit`-driven effect,
not a return value ([[project_event_substrate]]): `emit ExportMesh(mesh, "out.ply")`. No
serialization of any kind exists today; the writer is net-new but small (no library needed; ASCII
first, binary-little-endian a trivial later add).

**Deferred / off-script:** glTF or FBX for tool-specific pipelines that need
materials/scene/animation — out of scope. PLY is the portable standard core; a richer format is a
later, separate concern only if a consumer demands one.

Format comparison that led here (for the record): PLY carries arbitrary named per-vertex data
natively + is indexed; glTF does too but adds a rendering/PBR ecosystem; FBX can via
`LayerElementUserData` but importers often silently drop it; OBJ carries only position/normal/uv
(would drop attributes = a lie); STL carries position only and de-indexes (drops both attributes
*and* topology). PLY is the honest minimum that loses nothing the pipeline produced.

## What exists vs. net-new (scoped against master)

**Reuse:**
- **Extension API** is the exact channel; **`PlotExtension` + `DasumBridge`** in
  `pontif-builtin-gui` are a near-line-for-line precedent (shape traits → Pontif-side sampling →
  flat float arrays → native). Copy the `doubles()`/`xyzTriples()` marshalling helpers.
- Type substrate is all present: `struct` primitives, refined sorts for params, the `Stream` trait
  for fields ([[project_stream_substrate]]), traits + dispatch for the `SdfShape` contract,
  aggregates as the mesh/attr containers ([[project_aggregate_unification]]).
- **dasum already ships an SDF engine** — `ScalarField`, `CsgField`, `CsgBox`, `SurfaceSampler`,
  a `SdfLayer` sphere-tracer with a NORMALS mode, plus CPU-side gradients. This gives a
  **live preview for free** (S1 renders the SDF before any meshing exists) and hands dual
  contouring its gradients.
- `Vec3`/`Vec4`/`CameraMath` for the transform math.

**Net-new (nothing exists today):**
- The **indexed `Mesh`** structure (codebase has only flat triangle soup).
- **Meshing** — no marching cubes / dual contouring / isosurface extraction anywhere.
- **The entire export path** — no serialization of any kind; no FBX/glTF/OBJ writer; no FBX lib.
- The Pontif-facing front-end: `SdfShape` trait + primitive roster, the attribute-field system,
  the transform/anchor value, the CSG combinators.

**Caveat:** dasum's SDF engine is a **binary** dependency (`sibarum.dasum.gui`, 1.0-SNAPSHOT) — it
can *render* an SDF but cannot be *extended* from this repo. Meshing + export live in the new
in-repo module `pontif-builtin-shape`.

## Module & architecture

New leaf module **`pontif-builtin-shape`** (`pontif.shape`), sibling to `-gui`/`-net`, added to
the root reactor. `ShapeExtension implements Extension` (moduleName `pontif.shape`, a `.ptf`
source declaring the traits/primitives/combinators with placeholder `-> {}` bodies for natives,
`calls()` = `{topologize, exportMesh, …}`, `effects()` = `{ExportMesh}`), plus `ShapeBridge` for
the Java glue (grid sampling → MC → indexed mesh → serialize). Registered via a `ShapeLauncher`
(like `GuiLauncher`) so the CLI stays lean; it pulls no heavy dep beyond the JDK for MC + a text
writer, so it *could* install from `BuiltinModules` — PROPOSED: launcher, decide at S1.

**OPEN — module home:** dedicated `pontif.shape` vs folding into `pontif.plot`. Leaning dedicated,
per [[feedback_pontif_module_granularity]] ("one class in a module harms nothing"). Preview (live
SDF render, S1) does depend on dasum, so S1 may sit in `-gui` or `-shape` depends-on-`-gui`.

## Live preview — the cheapest first slice (S1)

An SDF needs no triangles to be drawn: march a ray per pixel until `distance` reports a hit, then
shade. So the whole authoring front end (primitive struct → `SdfShape` → `distance` runs → render)
can be witnessed **before any meshing or export exists**. dasum already ships the machinery — a
`VolumeLayer` dense-voxel raymarcher (already wired on master, what `volume.ptf` renders) and a
currently-*dark* `SdfLayer`/`CsgField`/`CsgBox` analytic sphere-tracer.

Two flavors — and they are the A-vs-B crossing decision (§6a) showing up early:

- **(a) Sampled preview — the baseline (S1).** Evaluate the SDF *Pontif-side* into a dense voxel
  grid (the `Volume3D` 24³ pattern `PlotExtension` already uses), hand the flat grid across, render
  via the **existing** `VolumeLayer`. **Zero new native wiring**; rides a proven-green path.
  *One subtlety, found in build:* that renderer lights each voxel by its gradient **magnitude**, and
  a raw SDF has unit gradient everywhere — so feeding it raw distance glows as a solid box, not the
  shape. The fix is to **clamp the field to a thin band around the surface** (`|sdf| ≤ 2·dx`): flat
  (gradient 0 → transparent) away from the surface, varying (gradient 1 → lit) only across the
  shell — so what glows *is* the surface, tinted by its normal direction. Voxelized / soft-edged, not
  crisp. This is resolution **A**.
- **(b) Analytic preview — a later crispness upgrade.** Ship the shape as a serialized CSG tree to
  dasum's `CsgField`/`CsgBox` so the GPU marches the *exact* field (crisp surfaces, NORMALS shading
  for free). Requires lighting up the dark SDF classes. This is resolution **B**.

**DERIVED honesty constraint:** arbitrary user SDF surfaces (§(5), requirement 5) can be previewed
**only** via (a). Their `distance` is Pontif code, and the no-function-passing boundary rule
([[project_extension_api]]) forbids shipping it to a GPU shader — so a user's hand-written field
can only be *sampled*, while built-in primitives/booleans *can* also go analytic (b). Hence **(a)
is the S1 baseline** — it covers *every* `SdfShape` uniformly — and (b) is a built-in-set-only
upgrade, not a universal path.

Beyond being cheap, preview earns its place twice more: it is the **interactive authoring surface**
(each transform/boolean is seen immediately as S2/S3 land — the tweak-relaunch loop), and it is the
**ground truth `topologize` is validated against** in S5 (a meshed shape that disagrees with the
previewed field is a caught lie).

## Slices — additive, green throughout

- **S0** — this doc ratified.
- **S1 — LANDED 2026-07-02.** New module `pontif-builtin-shape` (`pontif.shape`): `SdfShape` trait
  + `Sphere` primitive + `previewGradientField` via the sampled path (a) — samples the SDF on a 24³ grid
  Pontif-side, **clamps it to a surface band** so the reused `pontif.plot` volumetric renderer lights
  the surface shell (not the whole box — see §Live preview (a)), and hands it over as a `Volume`
  layer. **No new native code**; `ShapeExtension` declares no `calls()` of its own (composes existing
  `pontif.plot` functions). Witness: `RenderLoweringTest.gradientField_samplesSphereSdfOverGrid_inPontif`
  runs `gradientField(Sphere(1.0))` (no renderer at all, since it returns a value) and asserts the clamped 24³
  grid — corner clamped to `+band` (outside), near-centre to `−band` (inside), and a near-surface
  voxel carrying the exact `√(x²+y²+z²)−r`, plus the box it was sampled over. Example:
  `pontif-builtin-shape/examples/sphere.ptf`. (Build with `-am`; the render path lives in
  `pontif-builtin-gui`.)
- **S2 — LANDED 2026-07-03.** Transforms + adjustable **anchor**: `translate`, `scale`, and
  `rotateX/Y/Z`, each a composable wrapper returning `[SdfShape]` with the anchor as an explicit
  pivot argument (query-point inverse-transform; see §(3)). Confirmed trait-typed struct fields
  (`inner:[SdfShape]`) work. Witness: `TransformTest` (pure SDF-algebra, no rendering) — translate
  moves the centre, scale grows about the anchor (distance × factor), rotation is rigid on a sphere,
  and `rotateY(translate(Sphere,{2,0,0}),90,origin)` lands the centre at `(0,0,-2)` (real rotation +
  composition). Stateful `anchor()` sugar + arbitrary-axis rotation deferred.
- **S3 — LANDED 2026-07-03.** **Boolean modifiers** `union`/`intersect`/`difference`/`smoothUnion`
  as min/max over signed distances, each a composable wrapper returning `[SdfShape]` and nesting
  into a CSG tree (see §(4)). Witness: `BooleanTest` (field values at known points for all four) +
  the first non-symmetric preview `examples/csg.ptf` (a sphere with a bite).
- **S4 — LANDED 2026-07-03.** Attribute fields (requirement 2): a field is a `ScalarField` (value
  by method); `attr(shape, name, field)` bundles them, `shapeOf` returns the geometry, `attrAt`
  samples the field (see §(2)). Proves data-as-field-not-per-vertex — the API has no vertex-index
  op. Witness: `AttributeTest` — the field evaluates at query points, the name is kept, the geometry
  is unchanged. Deferred to consumers (S6/S7): multiple named fields per shape, multi-channel
  **color** (red/green/blue), carrying fields through transforms/booleans (clash rule), and an
  inline `[Method(…):V]` field-value form.
- **S5** — **topologize** → indexed `Mesh` via marching cubes (sample-grid, geometry only, no
  attributes). Witness: sphere meshes to a closed manifold; triangle/vertex counts + a spot-check
  position.
- **S6** — attribute fields **sampled onto topologized vertices** → genuine per-vertex data; the
  declared-lossy discretization recorded on the conservation ledger. The conservation payoff.
  Witness: a color field yields per-vertex colors matching the field at each vertex position.
- **S7** — **PLY export** (ASCII first) through the `emit` membrane. Witness: the written `.ply`
  re-parses to matching vertex/triangle counts and per-vertex attribute values.
- Later — binary-little-endian PLY; dual contouring (sharp features, using SDF gradients);
  serialized-CSG-tree crossing (analytic precision, resolution B); surface-parameterized (uv)
  attribute fields; glTF/FBX only if a tool pipeline needs materials/scene/animation.

## Blast radius / risk

Additive; **no breaking change to core** (unlike the stream/brace wars). Risk is *size*, not
coupling: **meshing (S5) is now the bulk** — marching cubes from scratch. Export (S7) shrank to a
small direct serialization of `Mesh` once PLY was ruled (no library, no proprietary node graph).
S1–S4 are comfortable on the `PlotExtension` precedent. Dynamic resolution `N` inherits plotting.md's known
limit — sample counts synthesize from *static* index streams, so a fully-dynamic grid size is
blocked on the infinite/lazy-stream work ([[project_infinite_streams]]); `topologize` takes a
fixed-preset `resolution` until then.

## Open questions (for red-pen)
1. **Export format** — RESOLVED 2026-07-02: **PLY** (James: *"the atom of 3D geometry"*). glTF/FBX
   deferred as off-script, only if a tool pipeline demands materials/scene/animation.
2. **SDF crossing** — sample-grid (A, slice 1) vs serialized CSG tree (B). *(Leaning A first.)*
3. **Meshing algorithm** — marching cubes first, dual contouring later? *(Leaning yes.)*
4. **Module home** — dedicated `pontif.shape` vs fold into `pontif.plot`. *(Leaning dedicated.)*
5. **Primitive roster** — the starter set (§1) — which primitives ship in S1?
6. **Attribute name-clash rule** under boolean union — last-wins+warn, or require rename?
7. **Anchor default** — local `{0,0,0}` vs primitive natural center.

## Naming
- **`SdfShape`** — PROPOSED. Alternatives: `Field`, `Solid`, `Implicit`. (`ScalarField` is dasum's;
  avoid the collision.) [[feedback_pontif_naming]]: descriptive, offer candidates.
- **attribute field** vs the request's **"vertex data"** — the pre-topologize value is a field over
  the domain; "per-vertex" only becomes truthful after `topologize`. Keeping "vertex data" as the
  user-facing *intent* word risks the very lie the design avoids ([[feedback_no_fabricated_etymology]]
  is about origins, but the honesty instinct applies): PROPOSED surface term **`attr` / attribute
  field**, with "becomes vertex data on topologize" as the documented promise. James's call.
- **`topologize`** — RULED (James's word); apt (it adds topology/connectivity to a connectivity-less
  field). Kept.
- **`Mesh`**, **`Aabb`** (axis-aligned bounds) — PROPOSED.

# Graphics — GUI, plotting, 3D shapes, and GPU kernels

*Part of the [Pontif guide](../../README.md). This page covers the visual /
compute extensions that ride on Pontif's trait-and-event machinery: the native GUI,
the plotting library, SDF shapes, and `on Gpu` compute kernels. For the one-page
overview, see the root [README](../../README.md).*

> **Heads-up:** the `pontif.gui` / `pontif.plot` / `pontif.shape` / `pontif.gpu`
> modules live in the `pontif-builtin-*` and `pontif-gpu` packages, outside the core
> `pontif-runtime` — so the snippets on this page open a real window or need a GPU on
> the classpath, and are illustrative rather than pinned by the snippet test the way
> the rest of the guide is.

## Contents

- [The GUI framework](#the-gui-framework)
- [Plotting](#plotting)
- [3D shapes — SDF composition](#3d-shapes--sdf-composition)
- [GPU compute kernels (`on Gpu`)](#gpu-compute-kernels-on-gpu)

## The GUI framework

`pontif.gui` drives a native window through that same trait-and-event machinery.
Elements are plain structs — `Label`, `Button`, `Column` — and `window(cfg, tree)` is
the effect that renders them. Interactivity rides the event substrate: subtype the
library `Button` into a type *you* own (the orphan rule forbids adding a method to a
type you don't), give it the `Clickable` trait, and have `onClick` **emit** — so a
click becomes an event your `action`s react to.

```pontif
requires pontif.gui.{Label, Button, Column, window, Clickable}
requires pontif.events.{StdOut}

struct PushButton:[Button](text:String)
assign trait PushButton:Clickable {
  onClick():_ -> emit StdOut("button clicked!")  this
}

main (
  let lbl = Label("Press the Button")
  let btn = PushButton("Press me")
  window({title = "Hello"}, {
    Column("center", "middle", {lbl, btn})
  })
)
```

The backing toolkit is the author's own flexbox / OpenGL library; `pontif.gui` is the
only module that depends on it, and it is installed by the GUI launcher rather than the
bare runtime.

## Plotting

`pontif.plot` turns *any type that describes a shape* into a chart: you implement a
tiny trait and the library samples it and opens an orbitable window. A 2D curve is a
`Curve2D` (`at(x)` plus a `domain()`); a 3D surface is a `HeightMap3D` (`at(x, y)` plus
a rectangular domain); a point set is a `Cloud3D`. The projection body is ordinary
Pontif — free to call the math library above (`at(x) -> sin(x)`, say).

```pontif
requires pontif.plot.{HeightMap3D, plotSurface}

struct Bowl()
assign trait Bowl:HeightMap3D {
  at(x:Decimal, y:Decimal):Decimal -> x * x + y * y
  domain():[{Decimal,Decimal,Decimal,Decimal}] -> {-3.0, 3.0, -3.0, 3.0}
}

main ( plotSurface(Bowl()) )   # samples a 33×33 grid, meshes it, orbits on drag
```

The 2D and point-cloud siblings are symmetric — implement `Curve2D` / `Cloud3D` and
hand the value to `plotLine` / `plotCloud`:

```pontif
requires pontif.plot.{Curve2D, plotLine}

struct Parabola()
assign trait Parabola:Curve2D {
  at(x:Decimal):Decimal -> x * x
  domain():[{Decimal,Decimal}] -> {-10.0, 10.0}
}

main ( plotLine(Parabola()) )
```

## 3D shapes — SDF composition

`pontif.shape` composes 3D geometry as **signed distance fields**: a shape is a function
giving the signed distance to its surface (negative inside, zero on it, positive outside).
Primitives, boolean modifiers, and transforms are all one `SdfShape`, so they nest freely,
and `render` ray-marches whatever you build into a window as a crisp solid surface. Start
with a primitive:

```pontif
requires pontif.shape.{Sphere, render}

main ( render(Sphere(1.0)) )   # ray-marches the sphere's distance field into a window
```

Because every operator returns another `SdfShape`, **constructive solid geometry** and
**transforms compose inside-out** — here a sphere with a smaller sphere bitten out of it
(`difference` is `max(dₐ, −d_b)` over the distances), tilted 30° about the Y axis through
the origin (the third argument is the anchor — the adjustable pivot):

```pontif
requires pontif.shape.{Sphere, translate, rotateY, difference, render}

main ( render(rotateY(
  difference(Sphere(1.2), translate(Sphere(0.8), {0.9, 0.0, 0.0})),
  30.0, {0.0, 0.0, 0.0})) )
```

The full set: `translate` / `scale` / `rotateX`/`rotateY`/`rotateZ` (each about an anchor),
`union` / `intersect` / `difference` / `smoothUnion` (a filleted blend), and `distanceAt`
to query the field at any point. Two ways to view a shape: `render` (a crisp GPU
sphere-traced surface) and `previewGradientField` (the SDF sampled into a glowing
volumetric view of its gradient field).

You can also attach **arbitrary data** to a shape — but as a *field* (a value defined at
every point), not a per-vertex array, because no vertices exist yet: they're only born
when the shape is meshed (*topologized*), at which point each field is sampled onto them.
"You can't address data on points that don't exist" is the design working, not a
limitation. A field is a `ScalarField` — a value defined by a method, just like a shape's
distance — and `attr` bundles it with a shape by name:

```pontif
requires pontif.shape.{Sphere, ScalarField, attr, shapeOf, attrAt, render}

# a "height" field: defined at every point (here, the z coordinate), NOT stored per vertex
struct Height()
assign trait Height:ScalarField { valueAt(x:Decimal, y:Decimal, z:Decimal):Decimal -> z }

# attach the field to the sphere; shapeOf hands the geometry back to render, and attrAt
# samples the field on demand — attrAt(attr(Sphere(1.0), "height", Height()), 0.0, 0.0, 0.5) is 0.5
main ( render(shapeOf(attr(Sphere(1.0), "height", Height()))) )
```

Setting an object's *color* works the same way — a colour field over the object becomes
per-vertex colours on its surfaces once meshed (and, ultimately, `red`/`green`/`blue`
columns in an exported PLY mesh — the geometry-and-attributes format `pontif.shape` targets).
The full design, and the incremental slices, live in [docs/shapes.md](../shapes.md).

## GPU compute kernels (`on Gpu`)

A data-parallel iteration can be run as a **compute kernel on the GPU** by marking it with the
**`on Gpu`** directive. The iteration lowers to SuperVast's target-neutral `core` IR →
**SPIR-V** → **Vulkan**; the result is identical to running it on the CPU — `on Gpu` changes
*where* it runs, never *what* it computes (proven by SuperVast's CPU-vs-GPU differential oracle).

`on Gpu` produces a **`Stream[Int]`**, and *how you consume it* picks synchronous or asynchronous —
the ordinary **stream/effect duality**, nothing GPU-specific. A stream is **eager**: the `let` binding
dispatches the kernel immediately (non-blocking), and a **spread `f(&r)` synchronizes** it — awaits the
batch, then iterates. Here the GPU adds two vectors and `log` (spread over the result) prints each:

```pontif
requires pontif.core.{Stream}
requires pontif.events.{StdOut}

function log(i:Int):Int -> emit StdOut("" + i + " ")  i

let a:Stream[Int] = {1, 2, 3, 4}
let b:Stream[Int] = {10, 20, 30, 40}

main (
  let r:Stream[Int] = (&a, &b):[ (x:Int, y:Int) -> x + y ] on Gpu   # dispatched eagerly
  log(&r)                                                            # the spread synchronizes → "11 22 33 44 "
)
```

Because binds are eager, **concurrency needs no new syntax** — bind two kernels (both in flight), then
spread each to join it:

```pontif
main (
  let sum:Stream[Int]  = (&a, &b):[ (x:Int, y:Int) -> x + y ] on Gpu
  let prod:Stream[Int] = (&a, &c):[ (x:Int, y:Int) -> x * y ] on Gpu
  let s1 = log(&sum)  log(&prod)
)
```

The **asynchronous** alternative is the `emit`/`action` substrate — the kernel function weaves an
`emit` of an event you define, and once the batch resolves it's replayed on the host per element for an
`action` to react to (fire-and-forward, forward-only, no `await`). The woven `emit` is optional — it's
just the async delivery leg.

The map/zip fragment is compiled to the canonical `out[gid] = f(in0[gid], …)` kernel. **Lowering
is the eligibility check** (the guiding law): a shape with no data-parallel form — a fold/scan,
a fork, a guarded/`Break` body — is a source-located compile error, never a silently-wrong
kernel. `on Gpu` is a **materialization boundary**: inputs must be finite (a GPU batch is
uploaded whole), so infinite/lazy streams are honestly ineligible. v1 is `Int` (honest `int64`
columns — values past 2³² survive) and `Decimal` (lowered to IEEE f32 — a lossy cast, since `Decimal`
is the generic real type); a tuple return (`… -> {x+y, x*y}`) is a multi-output kernel yielding a
`Stream[{…}]`, and eagerly-bound kernels dispatch concurrently (across the device's compute queues) and
synchronize at their spreads. `vec3`/`mat4` (the shader on-ramp) build on this.

GPU support is **opt-in**: `pontif.gpu` (and `pontif-supirvast`, which owns the Vulkan/SuperVast
dependencies) live outside the core build and are discovered only when on the classpath — so
`on Gpu` lights up where GPU support is present and is an honest "not loaded" error where it
isn't.

---

**Full design notes:** [plotting](../plotting.md) · [reliable-plotting](../reliable-plotting.md) ·
[shapes](../shapes.md) · [sdf-glsl](../sdf-glsl.md) · [gradient-normals](../gradient-normals.md) ·
[gpu-kernels](../gpu-kernels.md) · [reactive-gui](../reactive-gui.md)

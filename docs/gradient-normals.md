# Gradient normals — a glyph layer over the volumetric renderer

Status: **LANDED (2026-07-02).** Built on the CPU→`LineLayer` route below; `normals(volume(v), k)`
in module `pontif.plot`, `PlotExtensionTest.volumeWithNormals_addsGradientGlyphLineLayer` green,
example `pontif-playground/examples/volumenormals.ptf`. No dasum changes. Adds a second scene layer alongside
the existing `VolumeLayer` (docs/plotting.md volume path, examples/volume.ptf): a *periodic
lattice of short line segments* showing the direction of the scalar field's gradient at each
sampled voxel. The raymarch shows *where the mass is*; this shows *which way it changes*.

## The ask

> "Periodic little lines representing the direction of the gradient at that point in the volume."

A **glyph field** (a "hedgehog"): sample the volume on a regular lattice, and at each lattice
point draw a short segment pointing along ∇f. The discrete-glyph companion to the continuous
raymarch we already ship.

## The finding that set the route

The route was initially framed as *"GPU-side, reuse the raymarcher's existing lit-gradient
math."* Investigation showed that math is not where it was assumed to be:

- **The shipping `volume.frag` computes no gradient.** It is a pure emissive accumulator
  (`sum += texel.rgb * texel.a` along the ray — `dasum-vis/.../shaders/volume.frag:52-58`). No
  lighting, no normals. The "gradient-lit" appearance is produced **CPU-side** in
  `DasumBridge.volumeLayer` (`pontif-builtin-gui`), which computes per-voxel central differences
  (`gx,gy,gz,mag`), bakes the **unit gradient direction into RGB** and **magnitude into A**, and
  uploads that as the `GL_RGBA32F` 3D texture. The shader only displays what the CPU encoded.
- The only **GPU** gradient-from-a-field estimator in dasum is `normalAt()` in `sdf.frag`
  — but that is the SDF sphere-tracer, differentiating an *analytic* SDF, not the 3D texture.
  Reusing it on the volume means *porting* it to sample `u_volume` (replace `sdf(p+off)` with
  `texture(u_volume, uvw+texel).a`). It is not sitting ready in the volume path.

So "reuse the existing GPU lit-gradient" is not available as written.

## Why the chosen form pulls toward CPU geometry

The ask is **little lines** — discrete glyphs on a lattice. That is *geometry*, and dasum
already has the exact primitive:

- **`LineLayer`** (`dasum-vis/.../scene/LineLayer.java`) — a `float[]` of xyz endpoint pairs
  drawn as `GL_LINES` via `FlatMaterial` + `flat.vert/frag`. Its own Javadoc names **"vector
  fields"** as a use case. Handled end-to-end today by `SceneRenderer` and `SceneGlBuffers`.
- **`DasumBridge.buildSceneLayers` already appends multiple layers per record** (the `Surface` +
  wireframe pattern) — adding a glyph layer next to the volume is a solved shape.
- The gradient to draw is **already computed** in `volumeLayer` — `gx,gy,gz` per voxel.

The catch on a *true GPU glyph* path: turning a GPU-computed gradient into line segments needs
either instanced arrows (a template segment oriented per-instance in a shader) or
transform-feedback to read gradients back into a VBO. **Neither instancing nor geometry shaders
exist anywhere in dasum** (verified — zero hits for `glDrawArraysInstanced` /
`glVertexAttribDivisor` / `GL_GEOMETRY`). The GPU route for glyphs specifically means building
instancing infrastructure from scratch, to draw lines the CPU can already produce from data it
already holds.

GPU genuinely wins for a **continuous** normal visualization (per-pixel raymarched normal
shading) — but that is a shaded field, not "little lines," and is a separate feature.

| | **CPU gradient → LineLayer glyphs** | **GPU instanced glyphs** | **GPU continuous normal shading** |
|---|---|---|---|
| Matches "little lines" | ✅ exactly | ✅ exactly | ❌ shaded field, not glyphs |
| Reuses existing gradient | ✅ already computed CPU-side | ⚠️ must port `normalAt` to texture | ⚠️ must port `normalAt` to texture |
| New dasum GL infra | none (`LineLayer` exists) | instancing / transform-feedback | new Material + shader mode |
| New shader | none | yes | yes |
| Effort | small | large | medium |

## Recommendation

Build it as **CPU gradient → `LineLayer` glyphs**. It draws the exact lines asked for, reuses the
gradient already computed, and adds **zero new GL code**. Reserve the GPU path for a future
continuous normal-shaded volume mode (its own slice), where GPU actually pays off.

Glyphs are a **neutral-colored overlay** whose **length encodes normalized magnitude** and whose
orientation encodes direction (see ratified choices below).

## File-level plan (CPU→LineLayer route)

**dasum side — no changes.** `LineLayer` + `FlatMaterial` + `flat.vert/frag` already draw
`GL_LINES`. One real limit: lines are **1px, non-instanced** — fine for a moderate lattice;
thick arrows would later need CPU quad-expansion into a `TriangleLayer`.

**Pontif side (`pontif-builtin-gui`) — four seams, mirroring how `opacity`/`wire` are threaded:**

1. **`PlotExtension.pontifSource()`** — add the author-facing control. A builder modeled on
   `fade`:
   ```
   function normals(v:Volume, stride:Int):Volume -> …
   ```
   that flags a volume to also emit a glyph layer (`stride` = "every k-th voxel"). Add `normals`
   to the `exports` list. Add the flag + stride fields to the `Volume` struct with off-defaults,
   set wherever `Volume(...)` is constructed.
2. **`DasumBridge.volumeLayer(...)`** — where `gx,gy,gz,mag,magMax` are already computed, add a
   second pass that walks the grid on `stride`, skips sub-threshold voxels (reusing the volume's
   threshold), and per surviving voxel emits two endpoints **centred on the voxel**:
   `P ± ½·(mag/magMax)·(GLYPH_FILL·stride·cellₘᵢₙ)·ĝ` (centred, so half-length each way keeps the
   steepest glyph well inside its cell). Gradient is recomputed **signed** here (`gradVec`) —
   `volumeLayer`'s pass-1 gradient is absolute-valued for the axis coloring and can't carry
   direction. Build a `float[] endpoints` + a `filledColor` array of the neutral glyph color.
3. **`DasumBridge.buildSceneLayers`** — when the record carries the normals flag, append
   `new LineLayer(endpoints, neutralColor, blend, opacity)` right after the `VolumeLayer`, exactly
   like the Surface+wireframe append.
4. **Read the new fields** — in `volumeLayer`, read `stride`/flag via the existing
   `rv.members()` + `memberD`/bool helpers.

Author experience:
```
main ( scene({title = "…"}, { normals(fade(volume(Radial()), 0.02), 3) }) )
```

## Design choices (RATIFIED 2026-07-02)

1. **Length ∝ normalized magnitude.** Not fixed-length. Normalize each voxel's gradient
   magnitude by `magMax` (already tracked in `volumeLayer` for the alpha log-compression), giving
   `[0,1]`, then scale by ≤1 lattice cell: `length = (mag/magMax) · (cellSpacing · k)`, `k ≤ 1`.
   The steepest voxel draws one cell long; everything shorter — so **glyphs physically cannot
   cross into neighbors**. Length is *relative within one volume*, not an absolute physical
   quantity (two volumes aren't length-comparable) — honest as a hedgehog plot, not dressed up as
   an absolute scale.
2. **Single neutral color.** All glyphs one fixed color (e.g. white/light gray) — a distinct
   overlay on the colored glow, clear figure/ground. Direction is carried by segment orientation;
   magnitude by length. No direction→RGB tint (would be redundant with orientation) and no
   color-by-magnitude (redundant with length).
3. **Skip sub-threshold voxels.** Reuse the volume's existing near-zero-gradient threshold — no
   glyph where the gradient is below it. Glyphs appear exactly where the glow does; no dusting of
   noise-oriented micro-stubs in flat space.
4. **Stride = every k-th voxel, default 3.** The grid is fixed at 24³ (`volumeIndices`), so
   `stride` sub-samples it (does not resample the field). Default `3` → an 8³ ≈ 512-glyph lattice
   (fewer after culling); author-overridable via the `normals(v, stride)` param.

## Deferred: the GPU alternative

If a *continuous* normal-shaded volume mode is wanted later: add a new `Layer` record + `Kind` +
Material/shader pair (model on `SdfLayer`/`SdfMaterial`, which already carry
`u_lightDir`/`u_viewMode` and include a NORMALS view mode), port `sdf.frag`'s `normalAt()`
to sample `u_volume` at one-texel offsets, and thread new uniforms through all four Pontif seams
above. Separate slice; does not block the glyph layer.

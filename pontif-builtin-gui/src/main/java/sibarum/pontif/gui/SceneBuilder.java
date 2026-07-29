package sibarum.pontif.gui;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.ui.Ui;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.vis.math.CameraRig;
import sibarum.dasum.gui.vis.math.CameraSpec;
import sibarum.dasum.gui.vis.math.Vec3;
import sibarum.dasum.gui.vis.plot.Axis;
import sibarum.dasum.gui.vis.plot.Ticks;
import sibarum.dasum.gui.vis.scene.BlendMode;
import sibarum.dasum.gui.vis.scene.InteractionSpec;
import sibarum.dasum.gui.vis.scene.Layer;
import sibarum.dasum.gui.vis.scene.LineLayer;
import sibarum.dasum.gui.vis.scene.PointLayer;
import sibarum.dasum.gui.vis.scene.RaymarchLayer;
import sibarum.dasum.gui.vis.scene.SceneSnapshot;
import sibarum.dasum.gui.vis.scene.SceneStates;
import sibarum.dasum.gui.vis.scene.TextLayer;
import sibarum.dasum.gui.vis.scene.TriangleLayer;
import sibarum.dasum.gui.vis.scene.VolumeLayer;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;

import java.util.ArrayList;
import java.util.List;

import static sibarum.pontif.gui.GuiShared.*;

/**
 * 3D scene construction (docs/plotting.md): turns sampled Pontif {@code Surface}/{@code Cloud}/
 * {@code Volume}/{@code Raymarch}/{@code Text3D} layer records into dasum {@link Layer}s, frames the
 * camera, and composes the window-filling {@link Component.SceneView} — plus the standalone cloud /
 * surface views, box-aspect normalization, 3D graduations, and colormaps. Split out of the former
 * god-class {@code DasumBridge}; the surrounding natives live in {@link DasumBridge}.
 */
final class SceneBuilder {
    private SceneBuilder() {}

    /**
     * A 3D point cloud: a {@link Component.SceneView} carrying a single {@link PointLayer},
     * a perspective camera, and orbit/zoom interaction (drag orbits, scroll zooms).
     */
    static Component buildCloudView(float[] xyz) {
        Component.SceneView view =                       // null size → fills the window
                plotSceneView();
        SceneStates.publish(view, SceneSnapshot.of(new PointLayer(xyz, null)));
        SceneStates.setCamera(view, CameraSpec.defaultPerspective());
        SceneStates.setInteraction(view, InteractionSpec.defaults());  // ORBIT_3D
        return view;
    }

    /**
     * A 3D surface from a row-major height grid {@code zs} (length {@code N*N}) over the
     * {@code [xlo,xhi] x [ylo,yhi]} domain: each grid cell becomes two triangles
     * ({@link TriangleLayer}), height is the up (y) axis, colour ramps blue→red by height. The
     * camera is framed to the surface bounds; drag orbits, scroll zooms.
     */
    static Component buildSurfaceView(double[] zs, double xlo, double xhi, double ylo, double yhi) {
        SurfaceMesh mesh = meshSurface(zs, xlo, xhi, ylo, yhi, "cool");
        if (mesh == null) {
            return errorLabel("surface needs an N*N grid (N>=2); got " + zs.length + " heights");
        }
        Component.SceneView view =                       // null size → fills the window
                plotSceneView();
        // OPAQUE (not the TriangleLayer 2-arg default of ALPHA): the surface is solid, so it must
        // WRITE the depth buffer. An ALPHA layer has depth writes disabled in SceneRenderer, which
        // leaves the surface rendering in submission order — far triangles bleed through near ones.
        SceneStates.publish(view,
                SceneSnapshot.of(new TriangleLayer(mesh.verts(), mesh.cols()).withBlend(BlendMode.OPAQUE)));
        SceneStates.setCamera(view, CameraRig.fitToBounds(CameraSpec.defaultPerspective(),
                new Vec3((float) xlo, (float) mesh.zmin(), (float) ylo),
                new Vec3((float) xhi, (float) mesh.zmax(), (float) yhi)));
        SceneStates.setInteraction(view, InteractionSpec.defaults());  // ORBIT_3D
        return view;
    }

    /** A triangulated height grid: interleaved xyz {@code verts}, per-vertex RGB {@code cols}, and
     *  the height extent {@code [zmin, zmax]} (world Y). */
    private record SurfaceMesh(float[] verts, float[] cols, double zmin, double zmax) {}

    /**
     * Meshes a row-major height grid {@code zs} (length {@code N*N}) over {@code [xlo,xhi]x[ylo,yhi]}
     * into two triangles per cell — height is the world Y (up) axis, colour ramps blue→red by height.
     * Shared by {@link #buildSurfaceView} and the composed-scene path ({@link #surfaceLayer}). Returns
     * {@code null} when the grid isn't a usable N*N (N&gt;=2).
     */
    private static SurfaceMesh meshSurface(double[] zs, double xlo, double xhi, double ylo, double yhi,
                                           String colormap) {
        int n = (int) Math.round(Math.sqrt(zs.length));
        if (n < 2 || n * n != zs.length) return null;

        double zmin = Double.POSITIVE_INFINITY, zmax = Double.NEGATIVE_INFINITY;
        for (double z : zs) { zmin = Math.min(zmin, z); zmax = Math.max(zmax, z); }
        double zspan = zmax - zmin == 0 ? 1 : zmax - zmin;
        double sx = (xhi - xlo) / (n - 1), sy = (yhi - ylo) / (n - 1);

        int cells = (n - 1) * (n - 1);
        float[] verts = new float[cells * 2 * 9];   // 2 triangles/cell * 3 vertices * 3 floats
        float[] cols = new float[verts.length];
        int[] o = {0};
        for (int r = 0; r < n - 1; r++) {
            for (int c = 0; c < n - 1; c++) {
                int i00 = r * n + c, i01 = i00 + 1, i10 = i00 + n, i11 = i10 + 1;
                emitSurfaceVerts(verts, cols, o, n, xlo, ylo, sx, sy, zs, zmin, zspan, colormap, i00, i10, i11);
                emitSurfaceVerts(verts, cols, o, n, xlo, ylo, sx, sy, zs, zmin, zspan, colormap, i00, i11, i01);
            }
        }
        return new SurfaceMesh(verts, cols, zmin, zmax);
    }

    /** Appends the given grid vertices (by index) as world (x, height, y) positions + colormap colour. */
    private static void emitSurfaceVerts(float[] verts, float[] cols, int[] o, int n,
            double xlo, double ylo, double sx, double sy, double[] zs, double zmin, double zspan,
            String colormap, int... indices) {
        for (int idx : indices) {
            double x = xlo + (idx % n) * sx, z = ylo + (idx / n) * sy, h = zs[idx];
            verts[o[0]] = (float) x;
            verts[o[0] + 1] = (float) h;     // height is the up axis
            verts[o[0] + 2] = (float) z;
            float[] rgb = colorFor(colormap, (float) ((h - zmin) / zspan));
            cols[o[0]] = rgb[0];
            cols[o[0] + 1] = rgb[1];
            cols[o[0] + 2] = rgb[2];
            o[0] += 3;
        }
    }

    // --- Composed scenes: many layers, one window (docs/plotting.md) --------------------------

    /** The layers of a composed scene, the world-space bounds to frame the camera to, and (when the
     *  scene has a surface) the colorbar key for the first surface's colormap + height range. */
    record SceneBuild(List<Layer> layers, Vec3 min, Vec3 max, Bar bar) {}

    /** A colorbar key: the colormap name and the value range {@code [lo, hi]} it spans. */
    record Bar(String colormap, double lo, double hi) {}

    /** Accumulates a world-space axis-aligned bounding box over the layers of a scene. */
    private static final class Bounds {
        private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        private boolean any = false;

        void add(double x, double y, double z) {
            minX = Math.min(minX, (float) x); maxX = Math.max(maxX, (float) x);
            minY = Math.min(minY, (float) y); maxY = Math.max(maxY, (float) y);
            minZ = Math.min(minZ, (float) z); maxZ = Math.max(maxZ, (float) z);
            any = true;
        }

        Vec3 min() { return any ? new Vec3(minX, minY, minZ) : new Vec3(-1f, -1f, -1f); }
        Vec3 max() { return any ? new Vec3(maxX, maxY, maxZ) : new Vec3(1f, 1f, 1f); }

        /** A representative world span, used to size text relative to the scene (>=1 unit). */
        float span() {
            if (!any) return 2f;
            float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
            return Math.max(1e-3f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
    }

    /**
     * Turns a Pontif {@code {layers}} tuple (each member a {@code Surface}/{@code Cloud}/{@code Text3D}
     * record, sampled Pontif-side) into dasum {@link Layer}s, in input order, and computes the scene
     * bounds. Geometry layers come first; text layers are appended last (so they draw over the
     * geometry) and sized relative to the geometry's bounds. Package-visible: the headless test seam
     * (asserts layer count/kind without opening a window), the analog of {@link #buildSurfaceView}.
     */
    static SceneBuild buildSceneLayers(Object layersValue) {
        List<Layer> geometry = new ArrayList<>();
        List<RecordValue> texts = new ArrayList<>();
        Bounds b = new Bounds();
        Bar bar = null;
        if (layersValue instanceof RecordValue tuple) {
            for (Object member : tuple.members().values()) {
                if (!(member instanceof RecordValue rv)) continue;
                switch (bareType(rv.typeName())) {
                    case "Surface" -> {
                        Layer l = surfaceLayer(rv, b);
                        if (l != null) {
                            geometry.add(l);
                            if (bar == null) bar = surfaceBar(rv);   // colorbar keys off the first surface
                            if (rv.members().get("wire") instanceof Boolean w && w) {
                                Layer wf = wireframeLayer(rv);
                                if (wf != null) geometry.add(wf);
                            }
                        }
                    }
                    case "Cloud" -> geometry.add(cloudLayer(rv, b));
                    case "Volume" -> {
                        Layer l = volumeLayer(rv, b);
                        if (l != null) {
                            geometry.add(l);
                            if (rv.members().get("normals") instanceof Boolean nrm && nrm) {
                                Layer g = gradientGlyphLayer(rv);   // overlay gradient-direction glyphs
                                if (g != null) geometry.add(g);
                            }
                        }
                    }
                    case "Raymarch" -> {
                        Layer l = raymarchLayer(rv, b);
                        if (l != null) geometry.add(l);
                    }
                    case "Text3D" -> { texts.add(rv); addText3dBounds(rv, b); }
                    default -> { /* skip unknown layer kinds rather than fail the whole scene */ }
                }
            }
        }
        List<Layer> layers = new ArrayList<>(geometry);
        float textHeight = 0.06f * b.span();   // legible relative to the scene, not a fixed world size
        for (RecordValue rv : texts) layers.add(text3dLayer(rv, textHeight));
        return new SceneBuild(layers, b.min(), b.max(), bar);
    }

    /**
     * A caller-supplied SDF shader (pontif.shape {@code render}): a GLSL {@code float map(vec3 p)}
     * plus a world-space AABB (center ± half-extent). The shape's signed-distance function is
     * lowered to GLSL interpreter-side (docs/sdf-glsl.md); only the inert {@code map} string
     * crosses the boundary. Rendered by Dasum's {@link RaymarchLayer} sphere-tracer, which
     * depth-composes with the rest of the scene.
     */
    private static Layer raymarchLayer(RecordValue rv, Bounds b) {
        if (!(rv.members().get("map") instanceof StringValue map) || map.content().isBlank()) return null;
        double cx = memberD(rv, "cx"), cy = memberD(rv, "cy"), cz = memberD(rv, "cz");
        double hx = memberD(rv, "hx"), hy = memberD(rv, "hy"), hz = memberD(rv, "hz");
        b.add(cx - hx, cy - hy, cz - hz);
        b.add(cx + hx, cy + hy, cz + hz);
        Vec3 center = new Vec3((float) cx, (float) cy, (float) cz);
        Vec3 half = new Vec3((float) hx, (float) hy, (float) hz);
        return RaymarchLayer.standard(map.content(), center, half, Color.rgb(0.62f, 0.71f, 0.92f));
    }

    /** The colorbar key for a {@code Surface} record: its colormap name over its height range. */
    private static Bar surfaceBar(RecordValue rv) {
        double[] zs = doubles(rv.members().get("zs"));
        double zmin = Double.POSITIVE_INFINITY, zmax = Double.NEGATIVE_INFINITY;
        for (double z : zs) { zmin = Math.min(zmin, z); zmax = Math.max(zmax, z); }
        String map = rv.members().get("colormap") instanceof StringValue s ? s.content() : "cool";
        return zs.length == 0 ? null : new Bar(map, zmin, zmax);
    }

    /** A {@code Surface} layer record → an OPAQUE (solid) or ALPHA (faded) triangle mesh. */
    private static Layer surfaceLayer(RecordValue rv, Bounds b) {
        double[] zs = doubles(rv.members().get("zs"));
        double xlo = memberD(rv, "xlo"), xhi = memberD(rv, "xhi"),
               ylo = memberD(rv, "ylo"), yhi = memberD(rv, "yhi");
        String colormap = rv.members().get("colormap") instanceof StringValue s ? s.content() : "cool";
        SurfaceMesh mesh = meshSurface(zs, xlo, xhi, ylo, yhi, colormap);
        if (mesh == null) return null;
        b.add(xlo, mesh.zmin(), ylo);
        b.add(xhi, mesh.zmax(), yhi);
        double opacity = rv.members().containsKey("opacity") ? memberD(rv, "opacity") : 1.0;
        TriangleLayer tri = new TriangleLayer(mesh.verts(), mesh.cols());
        // Solid (opacity>=1) writes depth (OPAQUE → true occlusion); faded is translucent so
        // layers behind show through (the "stack on top" case), reading depth but not writing it.
        return opacity >= 1.0
                ? tri.withBlend(BlendMode.OPAQUE)
                : tri.withBlend(BlendMode.ALPHA).withOpacity((float) opacity);
    }

    private static final Color WIRE_COLOR = new Color(0.10f, 0.12f, 0.16f, 1f);

    /** A {@code Surface} record → a {@link LineLayer} tracing its N×N sample grid, lifted a hair
     *  toward the viewer so it doesn't z-fight the surface it overlays. Returns null for a bad grid. */
    private static Layer wireframeLayer(RecordValue rv) {
        double[] zs = doubles(rv.members().get("zs"));
        int n = (int) Math.round(Math.sqrt(zs.length));
        if (n < 2 || n * n != zs.length) return null;
        double xlo = memberD(rv, "xlo"), xhi = memberD(rv, "xhi"),
               ylo = memberD(rv, "ylo"), yhi = memberD(rv, "yhi");
        double sx = (xhi - xlo) / (n - 1), sy = (yhi - ylo) / (n - 1);
        double zmin = Double.POSITIVE_INFINITY, zmax = Double.NEGATIVE_INFINITY;
        for (double z : zs) { zmin = Math.min(zmin, z); zmax = Math.max(zmax, z); }
        float lift = (float) (0.004 * (zmax - zmin == 0 ? 1 : zmax - zmin));   // avoid z-fighting

        // Row edges (n rows × n-1) + column edges (n cols × n-1) = 2n(n-1) segments.
        float[] ep = new float[2 * n * (n - 1) * 6];
        int[] o = {0};
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n - 1; c++) {
                putEdge(ep, o, xlo + c * sx, zs[r * n + c] + lift, ylo + r * sy,
                        xlo + (c + 1) * sx, zs[r * n + c + 1] + lift, ylo + r * sy);
            }
        }
        for (int c = 0; c < n; c++) {
            for (int r = 0; r < n - 1; r++) {
                putEdge(ep, o, xlo + c * sx, zs[r * n + c] + lift, ylo + r * sy,
                        xlo + c * sx, zs[(r + 1) * n + c] + lift, ylo + (r + 1) * sy);
            }
        }
        return new LineLayer(ep, filledColor(ep.length, WIRE_COLOR));
    }

    /** Writes one line segment (two xyz endpoints) into {@code ep} at cursor {@code o[0]}. */
    private static void putEdge(float[] ep, int[] o,
            double ax, double ay, double az, double bx, double by, double bz) {
        ep[o[0]] = (float) ax; ep[o[0] + 1] = (float) ay; ep[o[0] + 2] = (float) az;
        ep[o[0] + 3] = (float) bx; ep[o[0] + 4] = (float) by; ep[o[0] + 5] = (float) bz;
        o[0] += 6;
    }

    /** Fraction of the peak gradient below which a voxel is dropped (flat space contributes nothing). */
    private static final float VOLUME_THRESHOLD = 0.05f;
    /** Emission gain on voxel density. HDR: pushed above 1 so bright cores overflow and bloom picks
     *  them up (the ACES tone-map in the composite brings the range back). Tune by eye. */
    private static final float VOLUME_EXPOSURE = 6.0f;
    /** Per-layer alpha for the additive voxels — the "how bright does the glow accumulate" knob. */
    private static final float VOLUME_OPACITY = 0.3f;

    /**
     * A {@code Volume} record → a raymarched {@link VolumeLayer} coloured by GRADIENT DIRECTION: at
     * each grid voxel the field's gradient is estimated by central differences; its DIRECTION
     * ({@code |∂x|,|∂y|,|∂z|} normalized) is the voxel's RGB (so the axis of fastest change lights
     * its channel) and a LOG of its magnitude is the voxel's density/alpha (so steep and gentle
     * boundaries both read). The dense RGBA grid uploads to a 3D texture and the shader accumulates
     * it emissively along each ray — continuous (trilinear-filtered), crisper than points.
     * (docs/plotting.md)
     */
    private static Layer volumeLayer(RecordValue rv, Bounds b) {
        double[] vs = doubles(rv.members().get("vs"));
        int n = (int) Math.round(Math.cbrt(vs.length));
        if (n < 2 || (long) n * n * n != vs.length) return null;
        double xlo = memberD(rv, "xlo"), xhi = memberD(rv, "xhi"),
               ylo = memberD(rv, "ylo"), yhi = memberD(rv, "yhi"),
               zlo = memberD(rv, "zlo"), zhi = memberD(rv, "zhi");
        b.add(xlo, ylo, zlo);
        b.add(xhi, yhi, zhi);
        double sx = (xhi - xlo) / (n - 1), sy = (yhi - ylo) / (n - 1), sz = (zhi - zlo) / (n - 1);
        int nn = n * n;

        // Pass 1: per-voxel abs gradient components (central differences, one-sided at edges) and
        // gradient magnitude; track the peak magnitude for the log-brightness normalization.
        float[] gx = new float[vs.length], gy = new float[vs.length], gz = new float[vs.length];
        float[] mag = new float[vs.length];
        float magMax = 1e-12f;
        for (int iz = 0; iz < n; iz++) for (int iy = 0; iy < n; iy++) for (int ix = 0; ix < n; ix++) {
            int idx = ix + iy * n + iz * nn;
            int xm = Math.max(0, ix - 1), xp = Math.min(n - 1, ix + 1);
            int ym = Math.max(0, iy - 1), yp = Math.min(n - 1, iy + 1);
            int zm = Math.max(0, iz - 1), zp = Math.min(n - 1, iz + 1);
            gx[idx] = sx > 0 ? (float) Math.abs((vs[xp + iy*n + iz*nn] - vs[xm + iy*n + iz*nn]) / ((xp - xm) * sx)) : 0f;
            gy[idx] = sy > 0 ? (float) Math.abs((vs[ix + yp*n + iz*nn] - vs[ix + ym*n + iz*nn]) / ((yp - ym) * sy)) : 0f;
            gz[idx] = sz > 0 ? (float) Math.abs((vs[ix + iy*n + zp*nn] - vs[ix + iy*n + zm*nn]) / ((zp - zm) * sz)) : 0f;
            mag[idx] = (float) Math.sqrt((double) gx[idx]*gx[idx] + (double) gy[idx]*gy[idx] + (double) gz[idx]*gz[idx]);
            magMax = Math.max(magMax, mag[idx]);
        }

        // Pass 2: fill a dense RGBA grid — rgb = gradient DIRECTION (which axis it changes along),
        // a = LOG of the gradient magnitude × exposure (log compresses steep-vs-gentle so both
        // read). Flat voxels below a small threshold stay transparent (0).
        float[] rgba = new float[vs.length * 4];
        double logDen = Math.log1p(Math.E - 1);   // = 1; normalizes the log curve to [0,1]
        for (int idx = 0; idx < vs.length; idx++) {
            float t = mag[idx] / magMax;                       // linear steepness in [0,1]
            if (t < VOLUME_THRESHOLD) continue;                // leave this voxel transparent (0)
            float bright = (float) (Math.log1p(t * (Math.E - 1)) / logDen) * VOLUME_EXPOSURE;
            float inv = 1f / mag[idx];                          // signed unit gradient direction
            rgba[idx*4    ] = gx[idx] * inv;
            rgba[idx*4 + 1] = gy[idx] * inv;
            rgba[idx*4 + 2] = gz[idx] * inv;
            rgba[idx*4 + 3] = bright;                           // density/alpha
        }
        float opacity = rv.members().containsKey("opacity")
                ? Math.max(0f, Math.min(1f, (float) memberD(rv, "opacity"))) : VOLUME_OPACITY;
        Vec3 center = new Vec3((float) ((xlo + xhi) / 2), (float) ((ylo + yhi) / 2), (float) ((zlo + zhi) / 2));
        Vec3 half = new Vec3((float) ((xhi - xlo) / 2), (float) ((yhi - ylo) / 2), (float) ((zhi - zlo) / 2));
        return new VolumeLayer(rgba, n, n, n, center, half, 128, BlendMode.ADDITIVE, opacity);
    }

    /** Longest glyph spans this fraction of the inter-glyph spacing (<=1 ⇒ no glyph reaches a neighbour). */
    private static final float GLYPH_FILL = 0.9f;
    /** Neutral overlay colour for the gradient-direction glyphs — a distinct annotation over the glow. */
    private static final Color GLYPH_COLOR = new Color(0.85f, 0.87f, 0.92f, 1f);

    /**
     * A {@code Volume} record with {@code normals} enabled → a {@link LineLayer} of short segments on a
     * {@code stride}-spaced lattice, each centred on a voxel and oriented along the field's SIGNED
     * gradient there. Segment length ∝ the gradient magnitude normalized by the volume's peak, scaled
     * so the steepest glyph spans {@link #GLYPH_FILL} of the inter-glyph gap — so none reach into a
     * neighbour. Near-flat voxels below {@link #VOLUME_THRESHOLD} are skipped, matching {@link
     * #volumeLayer}. Length is relative within one volume (normalized by its own peak), not an absolute
     * magnitude. Recomputes the gradient independently, as {@link #wireframeLayer} does for its surface.
     */
    private static Layer gradientGlyphLayer(RecordValue rv) {
        double[] vs = doubles(rv.members().get("vs"));
        int n = (int) Math.round(Math.cbrt(vs.length));
        if (n < 2 || (long) n * n * n != vs.length) return null;
        int stride = rv.members().containsKey("stride")
                ? Math.max(1, (int) Math.round(memberD(rv, "stride"))) : 3;
        double xlo = memberD(rv, "xlo"), xhi = memberD(rv, "xhi"),
               ylo = memberD(rv, "ylo"), yhi = memberD(rv, "yhi"),
               zlo = memberD(rv, "zlo"), zhi = memberD(rv, "zhi");
        double sx = (xhi - xlo) / (n - 1), sy = (yhi - ylo) / (n - 1), sz = (zhi - zlo) / (n - 1);
        int nn = n * n;

        // Peak gradient magnitude over the whole grid — the SAME normalization the volume brightness
        // uses (volumeLayer pass 1), so glyph length tracks the glow's steepness.
        double magMax = 1e-12;
        for (int iz = 0; iz < n; iz++) for (int iy = 0; iy < n; iy++) for (int ix = 0; ix < n; ix++) {
            double[] g = gradVec(vs, n, nn, ix, iy, iz, sx, sy, sz);
            magMax = Math.max(magMax, Math.sqrt(g[0]*g[0] + g[1]*g[1] + g[2]*g[2]));
        }

        // Steepest glyph = GLYPH_FILL of the inter-glyph spacing (stride cells along the tightest axis)
        // so it can't reach its neighbour; glyphs are centred on the voxel (half the length each way).
        double maxLen = GLYPH_FILL * stride * Math.min(sx, Math.min(sy, sz));

        // One segment per surviving strided voxel: two xyz endpoints (6 floats) each.
        List<Float> ep = new ArrayList<>();
        for (int iz = 0; iz < n; iz += stride) for (int iy = 0; iy < n; iy += stride) for (int ix = 0; ix < n; ix += stride) {
            double[] g = gradVec(vs, n, nn, ix, iy, iz, sx, sy, sz);   // signed ∂x,∂y,∂z
            double m = Math.sqrt(g[0]*g[0] + g[1]*g[1] + g[2]*g[2]);
            double t = m / magMax;                                     // normalized steepness in [0,1]
            if (t < VOLUME_THRESHOLD) continue;                        // skip near-flat voxels, as the volume does
            double half = 0.5 * t * maxLen / m;                        // (half length) / |g|, to unit-scale g below
            double hx = g[0]*half, hy = g[1]*half, hz = g[2]*half;
            double px = xlo + ix * sx, py = ylo + iy * sy, pz = zlo + iz * sz;
            ep.add((float)(px - hx)); ep.add((float)(py - hy)); ep.add((float)(pz - hz));
            ep.add((float)(px + hx)); ep.add((float)(py + hy)); ep.add((float)(pz + hz));
        }
        if (ep.isEmpty()) return null;
        float[] arr = new float[ep.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ep.get(i);
        return new LineLayer(arr, filledColor(arr.length, GLYPH_COLOR));
    }

    /** Signed central-difference gradient {∂x,∂y,∂z} of the scalar grid at voxel (ix,iy,iz), one-sided
     *  at edges — the signed sibling of {@link #volumeLayer}'s (abs) pass-1 gradient. */
    private static double[] gradVec(double[] vs, int n, int nn, int ix, int iy, int iz,
            double sx, double sy, double sz) {
        int xm = Math.max(0, ix - 1), xp = Math.min(n - 1, ix + 1);
        int ym = Math.max(0, iy - 1), yp = Math.min(n - 1, iy + 1);
        int zm = Math.max(0, iz - 1), zp = Math.min(n - 1, iz + 1);
        double dx = sx > 0 ? (vs[xp + iy*n + iz*nn] - vs[xm + iy*n + iz*nn]) / ((xp - xm) * sx) : 0;
        double dy = sy > 0 ? (vs[ix + yp*n + iz*nn] - vs[ix + ym*n + iz*nn]) / ((yp - ym) * sy) : 0;
        double dz = sz > 0 ? (vs[ix + iy*n + zp*nn] - vs[ix + iy*n + zm*nn]) / ((zp - zm) * sz) : 0;
        return new double[]{dx, dy, dz};
    }

    /** A {@code Cloud} layer record → an OPAQUE point layer (so it occludes with surfaces). */
    private static Layer cloudLayer(RecordValue rv, Bounds b) {
        float[] xyz = xyzTriples(rv.members().get("points"));
        for (int i = 0; i + 2 < xyz.length; i += 3) b.add(xyz[i], xyz[i + 1], xyz[i + 2]);
        double opacity = rv.members().containsKey("opacity") ? memberD(rv, "opacity") : 1.0;
        PointLayer pts = new PointLayer(xyz, null).withBlend(BlendMode.OPAQUE);
        return opacity >= 1.0 ? pts : pts.withBlend(BlendMode.ALPHA).withOpacity((float) opacity);
    }

    /** A {@code Text3D} layer record → a billboarded world-space label of the given world height. */
    private static Layer text3dLayer(RecordValue rv, float heightWorld) {
        Vec3 at = new Vec3((float) memberD(rv, "x"), (float) memberD(rv, "y"), (float) memberD(rv, "z"));
        return new TextLayer(str(rv, "text"), at, heightWorld, TEXT).withBillboard(true);
    }

    private static void addText3dBounds(RecordValue rv, Bounds b) {
        b.add(memberD(rv, "x"), memberD(rv, "y"), memberD(rv, "z"));
    }

    /** Target side length of the display cube for box-aspect normalization. */
    private static final float CUBE = 10f;

    /**
     * The scene component: one window-filling {@link Component.SceneView} carrying all layers (plus
     * the axis box when {@code axes}). Geometry is built in DATA space and then mapped into a display
     * cube so any data range reads well (box aspect); {@code equalAspect} keeps true proportions
     * instead. A colorbar sidebar is added when the scene has a surface.
     */
    static Component sceneComponent(SceneBuild build, boolean axes, boolean grid, boolean equalAspect) {
        List<Layer> raw = new ArrayList<>(build.layers());
        if (axes) raw.addAll(axisBoxLayers(build.min(), build.max(), grid));

        Transform t = equalAspect ? Transform.IDENTITY : boxTransform(build.min(), build.max());
        float textScale = t.gmean();
        List<Layer> shown = new ArrayList<>(raw.size());
        for (Layer l : raw) shown.add(scaleLayer(l, t, textScale));

        Component.SceneView view =                       // null width/height → fills the window
                plotSceneView();
        SceneStates.publish(view, new SceneSnapshot(shown));
        SceneStates.setCamera(view, CameraRig.fitToBounds(CameraSpec.defaultPerspective(),
                t.apply(build.min()), t.apply(build.max())));
        SceneStates.setInteraction(view, InteractionSpec.defaults());  // ORBIT_3D
        if (build.bar() == null) return view;
        // Colorbar key beside the scene: the view flex-grows to fill, the bar takes its own width.
        return Ui.row().fill().grow(1).padding(Em.of(0.6f)).gap(Em.of(0.8f))
                .justify(JustifyContent.START).align(AlignItems.STRETCH)
                .add(view).add(colorbar(build.bar())).build();
    }

    // --- Box-aspect normalization: map data-space coordinates into a display cube ---------------

    /** A per-axis affine map (center + scale) from data space into the display cube. Tick labels
     *  keep their data values; only positions are transformed. */
    private record Transform(float cx, float cy, float cz, float sx, float sy, float sz) {
        static final Transform IDENTITY = new Transform(0, 0, 0, 1, 1, 1);
        float ax(double v) { return (float) ((v - cx) * sx); }
        float ay(double v) { return (float) ((v - cy) * sy); }
        float az(double v) { return (float) ((v - cz) * sz); }
        Vec3 apply(Vec3 p) { return new Vec3(ax(p.x()), ay(p.y()), az(p.z())); }
        /** Uniform factor for scaling text height (geometric mean of the axis scales). */
        float gmean() { return (float) Math.cbrt(Math.abs((double) sx * sy * sz)); }
    }

    /** Build the box transform mapping {@code [lo, hi]} onto a {@code CUBE}-sided cube centred at origin. */
    private static Transform boxTransform(Vec3 lo, Vec3 hi) {
        float cx = (lo.x() + hi.x()) / 2f, cy = (lo.y() + hi.y()) / 2f, cz = (lo.z() + hi.z()) / 2f;
        return new Transform(cx, cy, cz,
                CUBE / Math.max(1e-4f, hi.x() - lo.x()),
                CUBE / Math.max(1e-4f, hi.y() - lo.y()),
                CUBE / Math.max(1e-4f, hi.z() - lo.z()));
    }

    /** Rebuild a layer with its coordinates mapped through {@code t} (text height by {@code textScale}). */
    private static Layer scaleLayer(Layer l, Transform t, float textScale) {
        if (t == Transform.IDENTITY) return l;
        return switch (l) {
            case TriangleLayer tr -> new TriangleLayer(scaleXYZ(tr.vertices(), t), tr.colors(), tr.blend(), tr.opacity());
            case PointLayer p -> {
                // World-sized points scale their diameter with the box transform (like text);
                // screen-pixel points keep their fixed size.
                float size = p.perspectiveSize() ? p.defaultSizePx() * textScale : p.defaultSizePx();
                yield new PointLayer(scaleXYZ(p.positions(), t), p.colors(), p.sizes(),
                        size, p.perspectiveSize(), p.blend(), p.opacity());
            }
            case LineLayer ln -> new LineLayer(scaleXYZ(ln.endpoints(), t), ln.colors(), ln.blend(), ln.opacity());
            case TextLayer tx -> new TextLayer(tx.text(), tx.fontGroup(), t.apply(tx.anchor()),
                    tx.heightWorld() * textScale, tx.color(), tx.align(), tx.billboard(), tx.blend(), tx.opacity());
            case VolumeLayer vol -> {
                // The box maps into the display cube: centre transforms like a point, the per-axis
                // half-extent scales (no centre offset). Grid data is unchanged.
                Vec3 h = new Vec3(vol.halfExtent().x() * t.sx(),
                        vol.halfExtent().y() * t.sy(), vol.halfExtent().z() * t.sz());
                yield new VolumeLayer(vol.rgba(), vol.nx(), vol.ny(), vol.nz(),
                        t.apply(vol.center()), h, vol.maxSteps(), vol.blend(), vol.opacity());
            }
            default -> l;
        };
    }

    /** Map every interleaved xyz triple in {@code a} through {@code t}, returning a new array. */
    private static float[] scaleXYZ(float[] a, Transform t) {
        float[] o = new float[a.length];
        for (int i = 0; i + 2 < a.length; i += 3) {
            o[i] = t.ax(a[i]); o[i + 1] = t.ay(a[i + 1]); o[i + 2] = t.az(a[i + 2]);
        }
        return o;
    }

    /** A vertical colorbar strip (high at top) for {@code bar}'s colormap, with min/max labels.
     *  Fixed width (an explicit flex basis): a null-width flex child resolves to intrinsic 0 and,
     *  with no grow weight, would be allocated 0px and overflow its content off-screen. */
    private static Component colorbar(Bar bar) {
        int steps = 24;
        List<Component> col = new ArrayList<>();
        col.add(Ui.text(fmtNum(bar.hi())).size(Em.of(0.85f)).color(TEXT).build());
        for (int i = steps - 1; i >= 0; i--) {           // top row = highest value
            float[] c = colorFor(bar.colormap(), i / (float) (steps - 1));
            // A fixed-size colored swatch = a Box (fixed width+height, background, no children).
            col.add(Ui.box().size(Em.of(2.4f), Em.of(0.32f))
                    .background(new Color(c[0], c[1], c[2], 1f)).build());
        }
        col.add(Ui.text(fmtNum(bar.lo())).size(Em.of(0.85f)).color(TEXT).build());
        // Fixed width (explicit basis); height fits content and the parent row's STRETCH align fills
        // it vertically beside the scene.
        return Ui.column().width(Em.of(4f)).padding(Em.of(0.4f)).gap(Em.of(0.15f))
                .justify(JustifyContent.CENTER).align(AlignItems.CENTER).addAll(col).build();
    }

    // --- 3D graduations: a labeled, tick-marked bounding box (docs/plotting.md) ---------------

    private static final Color AXIS_COLOR = new Color(0.55f, 0.60f, 0.70f, 1f);
    private static final Color GRID_COLOR = new Color(0.26f, 0.29f, 0.36f, 1f);

    /**
     * Builds the 3D graduation layers for a scene's world bounds {@code [lo, hi]}: a wireframe
     * bounding box, per-axis tick marks + billboard numeric labels (nice-number positions from
     * dasum's {@link Ticks}/{@link Axis}, reused from the 2D chart stack), and — when {@code grid} —
     * a floor grid on the {@code y = lo.y} plane. World axes are X (right), Y (up/height), Z (depth);
     * because plot geometry is placed at world = data coordinates, ticks sit at data values directly.
     * Package-visible: the headless test seam.
     */
    static List<Layer> axisBoxLayers(Vec3 lo, Vec3 hi, boolean grid) {
        List<Layer> out = new ArrayList<>();
        float span = dist(lo, hi);
        if (span <= 0f) return out;                    // degenerate / empty scene
        float tickLen = 0.02f * span;
        float labelH = 0.035f * span;
        float gap = 0.03f * span;

        float[] box = boxEdges(lo, hi);
        out.add(new LineLayer(box, filledColor(box.length, AXIS_COLOR)));

        addAxisTicks(out, Axis3.X, lo, hi, tickLen, labelH, gap);
        addAxisTicks(out, Axis3.Y, lo, hi, tickLen, labelH, gap);
        addAxisTicks(out, Axis3.Z, lo, hi, tickLen, labelH, gap);

        if (grid) {
            float[] floor = floorGrid(lo, hi);
            if (floor.length > 0) out.add(new LineLayer(floor, filledColor(floor.length, GRID_COLOR)));
        }
        return out;
    }

    private enum Axis3 { X, Y, Z }

    /** Adds one tick-mark {@link LineLayer} plus a billboard label {@link TextLayer} per nice tick,
     *  along the {@code axis} edge meeting at the {@code lo} corner. */
    private static void addAxisTicks(List<Layer> out, Axis3 axis, Vec3 lo, Vec3 hi,
                                     float tickLen, float labelH, float gap) {
        double min = switch (axis) { case X -> lo.x(); case Y -> lo.y(); case Z -> lo.z(); };
        double max = switch (axis) { case X -> hi.x(); case Y -> hi.y(); case Z -> hi.z(); };
        if (max <= min) return;
        Ticks.TickSet ts = Ticks.forAxis(Axis.linear(min, max), 5);

        List<float[]> segs = new ArrayList<>();
        for (int i = 0; i < ts.count(); i++) {
            double v = ts.values()[i];
            if (v < min - 1e-9 || v > max + 1e-9) continue;   // drop loose ticks outside the box
            float fv = (float) v;
            // Tick position on the lo-corner edge, a short mark outward, and a label just beyond.
            float[] a, b, label;
            switch (axis) {
                case X -> { a = new float[]{fv, lo.y(), lo.z()}; b = new float[]{fv, lo.y(), lo.z() - tickLen};
                            label = new float[]{fv, lo.y(), lo.z() - tickLen - gap}; }
                case Y -> { a = new float[]{lo.x(), fv, lo.z()}; b = new float[]{lo.x() - tickLen, fv, lo.z()};
                            label = new float[]{lo.x() - tickLen - gap, fv, lo.z()}; }
                default -> { a = new float[]{lo.x(), lo.y(), fv}; b = new float[]{lo.x() - tickLen, lo.y(), fv};
                            label = new float[]{lo.x() - tickLen - gap, lo.y(), fv}; }
            }
            segs.add(new float[]{a[0], a[1], a[2], b[0], b[1], b[2]});
            out.add(new TextLayer(ts.labels()[i], new Vec3(label[0], label[1], label[2]), labelH, AXIS_COLOR)
                    .withBillboard(true));
        }
        if (!segs.isEmpty()) {
            float[] marks = new float[segs.size() * 6];
            for (int i = 0; i < segs.size(); i++) System.arraycopy(segs.get(i), 0, marks, i * 6, 6);
            out.add(new LineLayer(marks, filledColor(marks.length, AXIS_COLOR)));
        }
    }

    /** The 12 edges of the axis-aligned box {@code [lo, hi]} as line-segment endpoints (72 floats). */
    private static float[] boxEdges(Vec3 lo, Vec3 hi) {
        float x0 = lo.x(), y0 = lo.y(), z0 = lo.z(), x1 = hi.x(), y1 = hi.y(), z1 = hi.z();
        float[][] e = {
                // bottom rectangle (y0)
                {x0,y0,z0, x1,y0,z0}, {x1,y0,z0, x1,y0,z1}, {x1,y0,z1, x0,y0,z1}, {x0,y0,z1, x0,y0,z0},
                // top rectangle (y1)
                {x0,y1,z0, x1,y1,z0}, {x1,y1,z0, x1,y1,z1}, {x1,y1,z1, x0,y1,z1}, {x0,y1,z1, x0,y1,z0},
                // verticals
                {x0,y0,z0, x0,y1,z0}, {x1,y0,z0, x1,y1,z0}, {x1,y0,z1, x1,y1,z1}, {x0,y0,z1, x0,y1,z1},
        };
        float[] out = new float[e.length * 6];
        for (int i = 0; i < e.length; i++) System.arraycopy(e[i], 0, out, i * 6, 6);
        return out;
    }

    /** A floor grid on the {@code y = lo.y} plane at the X and Z nice-tick positions. */
    private static float[] floorGrid(Vec3 lo, Vec3 hi) {
        Ticks.TickSet xs = Ticks.forAxis(Axis.linear(lo.x(), hi.x()), 5);
        Ticks.TickSet zs = Ticks.forAxis(Axis.linear(lo.z(), hi.z()), 5);
        List<float[]> segs = new ArrayList<>();
        float y = lo.y();
        for (int i = 0; i < xs.count(); i++) {
            float x = (float) xs.values()[i];
            if (x < lo.x() - 1e-6 || x > hi.x() + 1e-6) continue;
            segs.add(new float[]{x, y, lo.z(), x, y, hi.z()});
        }
        for (int i = 0; i < zs.count(); i++) {
            float z = (float) zs.values()[i];
            if (z < lo.z() - 1e-6 || z > hi.z() + 1e-6) continue;
            segs.add(new float[]{lo.x(), y, z, hi.x(), y, z});
        }
        float[] out = new float[segs.size() * 6];
        for (int i = 0; i < segs.size(); i++) System.arraycopy(segs.get(i), 0, out, i * 6, 6);
        return out;
    }

    // --- Colormaps: t in [0,1] -> RGB (docs/plotting.md) --------------------------------------
    // "cool" is the legacy blue->red ramp; viridis/turbo are stop-table approximations of the
    // perceptually-uniform Matplotlib/Google maps (linearly interpolated — honest, not the exact
    // polynomial, but monotone and close).

    private static final float[][] VIRIDIS = {
            {0.267f, 0.005f, 0.329f}, {0.283f, 0.141f, 0.458f}, {0.254f, 0.265f, 0.530f},
            {0.207f, 0.372f, 0.553f}, {0.164f, 0.471f, 0.558f}, {0.128f, 0.567f, 0.551f},
            {0.135f, 0.659f, 0.518f}, {0.267f, 0.749f, 0.441f}, {0.478f, 0.821f, 0.318f},
            {0.741f, 0.873f, 0.150f}, {0.993f, 0.906f, 0.144f},
    };
    private static final float[][] TURBO = {
            {0.190f, 0.072f, 0.232f}, {0.275f, 0.408f, 0.859f}, {0.180f, 0.718f, 0.926f},
            {0.153f, 0.921f, 0.640f}, {0.451f, 0.995f, 0.318f}, {0.780f, 0.940f, 0.223f},
            {0.968f, 0.760f, 0.224f}, {0.977f, 0.469f, 0.130f}, {0.851f, 0.211f, 0.044f},
            {0.600f, 0.061f, 0.010f}, {0.480f, 0.016f, 0.011f},
    };

    /** Map a normalized height {@code t} to an RGB triple by named colormap. */
    static float[] colorFor(String colormap, float t) {
        float u = Math.max(0f, Math.min(1f, t));
        return switch (colormap == null ? "cool" : colormap) {
            case "grayscale", "gray" -> new float[]{u, u, u};
            case "viridis" -> lerpStops(VIRIDIS, u);
            case "turbo" -> lerpStops(TURBO, u);
            default -> new float[]{u, 0.5f, 1f - u};   // "cool" — legacy blue->red ramp
        };
    }

    /** Linearly interpolate an RGB stop table at {@code u} in [0,1]. */
    private static float[] lerpStops(float[][] stops, float u) {
        float pos = u * (stops.length - 1);
        int i = (int) Math.floor(pos);
        if (i >= stops.length - 1) return stops[stops.length - 1].clone();
        float f = pos - i;
        float[] a = stops[i], b = stops[i + 1];
        return new float[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
    }

    /** A per-vertex colour array of {@code len} floats filled with {@code c}'s RGB (3 per vertex). */
    private static float[] filledColor(int len, Color c) {
        float[] cols = new float[len];
        for (int i = 0; i + 2 < len; i += 3) { cols[i] = c.r(); cols[i + 1] = c.g(); cols[i + 2] = c.b(); }
        return cols;
    }

    private static float dist(Vec3 a, Vec3 b) {
        float dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}

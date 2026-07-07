package sibarum.pontif.playground;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The curated set of example programs shown on the Welcome page (the Info tab).
 *
 * <p>Each {@link Sample}'s source is a bundled classpath resource under
 * {@code /welcome/samples/<id>.ptf} and its screenshot a resource under
 * {@code /welcome/shots/<id>.png}. Both are derived from the {@code id}, so
 * adding a sample is one row here plus the two files (the screenshot is
 * optional — the card falls back to a placeholder until it's supplied).
 *
 * <p>Only <em>self-contained</em> examples belong here: ones that
 * {@code requires} only builtin modules ({@code pontif.gui}, {@code pontif.plot},
 * {@code pontif.shape}, {@code pontif.events}, {@code pontif.math}) so they
 * compile and run from a fresh untitled buffer. Examples that pull in sibling
 * user modules (e.g. the {@code cott.traction} plots) are deliberately excluded.
 */
final class Samples {

    /** One showcase entry. {@code id} names both bundled resources. */
    record Sample(String id, String title, String description) {
        String sourceResource() { return "/welcome/samples/" + id + ".ptf"; }
        String shotResource()   { return "/welcome/shots/" + id + ".png"; }
    }

    /** Display order: GUI, 2D charts, 3D surfaces, cloud, composed scene, volume, SDF solids. */
    static final List<Sample> ALL = List.of(
        new Sample("interactive-window", "Interactive Window",
            "A window with a label and a button that emits an event when clicked."),
        new Sample("line-chart", "Line Chart",
            "A quick y = x² line plot built from two data streams."),
        new Sample("curves-2d", "2D Curves",
            "Two curves overlaid in one chart with auto axes, gridlines, and tick labels."),
        new Sample("surface-3d", "3D Surface",
            "A paraboloid bowl z = x² + y² as an orbitable 3D surface."),
        new Sample("colormap-surface", "Colormapped Surface",
            "A surface shaded with the viridis colormap next to a labeled colorbar."),
        new Sample("wireframe", "Wireframe Surface",
            "A finely sampled z = x*y saddle with a turbo colormap and a wireframe overlay."),
        new Sample("axes-saddle", "Axes & Grid",
            "A saddle inside a labeled, tick-marked bounding box with a floor grid."),
        new Sample("point-cloud", "Point Cloud",
            "Four points rendered as an orbitable 3D point cloud."),
        new Sample("layered-scene", "Layered Scene",
            "A bowl, a translucent cutting plane, marker points, and a 3D label in one scene."),
        new Sample("volume", "Volume Render",
            "A scalar field rendered volumetrically, glowing by gradient direction."),
        new Sample("volume-normals", "Volume + Normals",
            "The gradient-lit volume with a lattice of gradient-direction glyphs."),
        new Sample("sdf-sphere", "SDF Sphere",
            "A signed-distance-field sphere, ray-marched live as a real surface."),
        new Sample("sdf-csg", "CSG Shape",
            "A sphere with a smaller sphere carved out of it via CSG difference.")
    );

    /**
     * Reads a sample's bundled source. Returns a short placeholder comment (rather
     * than throwing) if the resource is somehow missing, so a click never dead-ends.
     */
    static String source(Sample s) {
        String resource = s.sourceResource();
        try (InputStream in = Samples.class.getResourceAsStream(resource)) {
            if (in == null) {
                return "# Sample source not found: " + resource + "\n";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "# Failed to read sample source: " + resource + "\n";
        }
    }

    private Samples() {}
}

package sibarum.pontif.playground;

import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.render.PngDecoder;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.dasum.gui.core.ui.Diagnostic;
import sibarum.dasum.gui.core.ui.FlexBuilder;
import sibarum.dasum.gui.core.ui.Ui;
import sibarum.dasum.gui.vis.math.CameraRig;
import sibarum.dasum.gui.vis.math.CameraSpec;
import sibarum.dasum.gui.vis.math.Vec3;
import sibarum.dasum.gui.vis.scene.ImageLayer;
import sibarum.dasum.gui.vis.scene.InteractionSpec;
import sibarum.dasum.gui.vis.scene.SceneSnapshot;
import sibarum.dasum.gui.vis.scene.SceneStates;

/**
 * The Welcome page shown on the Info tab: a scrollable list of {@link Samples}, one card per
 * sample — a screenshot thumbnail alongside its title, description, and an "Open in editor"
 * button that loads it as a fresh untitled buffer.
 *
 * <p>Built with the dasum {@link Ui} builder so every container declares an explicit sizing
 * intent ({@code .fit()} = size to content, {@code .fill()} = grow into the parent, or a fixed
 * {@code .size(...)}). Hand-rolling {@code Component.Flex} with {@code null} sizes is what
 * collapsed the earlier version — every card measured to zero and stacked on the origin.
 * {@link Ui#check} validates the finished tree and any diagnostics are logged.
 *
 * <p>Thumbnails are raster images shown through a non-interactive dasum-vis
 * {@link Component#SceneView} + {@link ImageLayer} (dasum-core cannot blit images); this needs
 * {@code DasumVis.init()} to have registered the renderer, which {@link App} does at startup. A
 * sample with no bundled screenshot falls back to a placeholder tile.
 */
final class WelcomePage {

    private static final Color CARD_BG        = new Color(0.11f, 0.13f, 0.17f, 1f);
    private static final Color THUMB_BG       = new Color(0.04f, 0.05f, 0.07f, 1f);
    private static final Color HEADING_FG     = new Color(0.94f, 0.96f, 0.99f, 1f);
    private static final Color TITLE_FG       = new Color(0.86f, 0.90f, 0.96f, 1f);
    private static final Color DESC_FG        = new Color(0.66f, 0.71f, 0.79f, 1f);
    private static final Color PLACEHOLDER_FG = new Color(0.45f, 0.50f, 0.58f, 1f);

    private static final Em THUMB_W = Em.of(17.6f);
    private static final Em THUMB_H = Em.of(11f);   // ~16:10
    private static final Em TEXT_W  = Em.of(26f);

    /**
     * Builds the gallery. {@code onOpen} is invoked with the chosen sample when a card's
     * "Open in editor" button is pressed (wired by {@link App} to load it into the editor).
     */
    static Component build(Consumer<Samples.Sample> onOpen) {
        FlexBuilder page = Ui.column().fit().padding(Em.of(1.2f)).gap(Em.of(0.9f)).align(AlignItems.START)
                .add(Ui.text("Welcome to the Pontif editor").size(Em.of(1.5f)).color(HEADING_FG))
                .add(Ui.text("Pick a sample to load it into the editor, then press Run or Window to see it render.")
                        .size(Em.of(0.95f)).color(DESC_FG).wrap(Em.of(48f)));

        for (Samples.Sample s : Samples.ALL) page.add(card(s, onOpen));

        Component root = page.build();

        // Validate the layout turned out as intended; surface any problems rather than shipping
        // a silently-broken page (the whole point of the Ui builder's sizing-intent checks).
        List<Diagnostic> issues = Ui.check(root);
        for (Diagnostic d : issues) System.err.println("[Welcome UI] " + d);

        return root;
    }

    /** One sample card: the thumbnail on the left, title/description/button stacked on the right. */
    private static Component card(Samples.Sample s, Consumer<Samples.Sample> onOpen) {
        Component details = Ui.column().fit().width(TEXT_W).gap(Em.of(0.5f)).align(AlignItems.START)
                .add(Ui.text(s.title()).size(Em.of(1.1f)).color(TITLE_FG))
                .add(Ui.text(s.description()).size(Em.of(0.9f)).color(DESC_FG).wrap(TEXT_W))
                .add(Ui.button("Open in editor").width(Em.of(14f)).variant(Variant.PRIMARY)
                        .onClick(() -> onOpen.accept(s)))
                .build();

        return Ui.row().fit().padding(Em.of(0.8f)).gap(Em.of(1f)).background(CARD_BG)
                .align(AlignItems.CENTER)
                .add(thumbnail(s))
                .add(details)
                .build();
    }

    /**
     * A screenshot tile: a locked, flat-framed SceneView showing the sample's PNG, or a
     * fixed-size "screenshot pending" placeholder when the resource is absent or fails to decode.
     */
    private static Component thumbnail(Samples.Sample s) {
        try (InputStream in = WelcomePage.class.getResourceAsStream(s.shotResource())) {
            if (in == null) return placeholder("screenshot pending");
            PngDecoder.DecodedImage img = PngDecoder.decode(in);
            float w = img.width();
            float h = img.height();

            Component.SceneView view = new Component.SceneView(THUMB_W, THUMB_H, Em.ZERO, THUMB_BG, false, 0);
            ImageLayer layer = ImageLayer.rect(0f, 0f, w, h, 0f, img.width(), img.height(), img.rgba())
                    .withSmooth(true);
            SceneStates.publish(view, SceneSnapshot.of(layer));
            // Flat, straight-on ortho view fitted to the image rect; not interactive.
            CameraSpec cam = CameraRig.front(
                    CameraRig.fitToBounds(CameraSpec.defaultOrtho(), new Vec3(0f, 0f, 0f), new Vec3(w, h, 0f)));
            SceneStates.setCamera(view, cam);
            SceneStates.setInteraction(view, InteractionSpec.locked());
            return view;
        } catch (Exception e) {
            return placeholder("screenshot unavailable");
        }
    }

    private static Component placeholder(String label) {
        return Ui.row().size(THUMB_W, THUMB_H).background(THUMB_BG)
                .justify(JustifyContent.CENTER).align(AlignItems.CENTER)
                .add(Ui.text(label).size(Em.of(0.85f)).color(PLACEHOLDER_FG))
                .build();
    }

    private WelcomePage() {}
}

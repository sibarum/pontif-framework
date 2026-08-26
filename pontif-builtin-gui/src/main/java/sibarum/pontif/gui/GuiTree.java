package sibarum.pontif.gui;

import sibarum.dasum.gui.core.GlfwContext;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.em.EmContext;
import sibarum.dasum.gui.core.event.EventLoop;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.input.FocusState;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.HoverState;
import sibarum.dasum.gui.core.input.InputState;
import sibarum.dasum.gui.core.input.TextInputController;
import sibarum.dasum.gui.core.layout.HitTest;
import sibarum.dasum.gui.core.layout.LatestLayout;
import sibarum.dasum.gui.core.layout.Layout;
import sibarum.dasum.gui.core.layout.LayoutResult;
import sibarum.dasum.gui.core.layout.PixelRect;
import sibarum.dasum.gui.core.layout.Render;
import sibarum.dasum.gui.core.render.Batcher;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.render.Projection;
import sibarum.dasum.gui.core.render.Texture;
import sibarum.dasum.gui.core.text.AtlasData;
import sibarum.dasum.gui.core.text.FontGroup;
import sibarum.dasum.gui.core.text.FontGroups;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.dasum.gui.core.ui.Ui;
import sibarum.dasum.gui.core.window.Window;
import sibarum.dasum.gui.natives.gl.Gl;
import sibarum.dasum.gui.natives.glfw.Glfw;
import sibarum.dasum.gui.natives.glfw.GlfwCallbacks;
import sibarum.dasum.gui.vis.DasumVis;
import sibarum.dasum.gui.vis.pointcloud.SceneViewController;
import sibarum.dasum.gui.vis.scene.InteractionSpec;
import sibarum.dasum.gui.vis.scene.SceneStates;
import sibarum.dasum.gui.vis.render.BloomPass;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static sibarum.pontif.gui.ChartBuilder.*;
import static sibarum.pontif.gui.GuiShared.*;

/**
 * The plot window (docs/plotting.md): opens a window, builds its root once after GL + font setup,
 * renders it on the root thread until closed ({@link #openWindowWithRoot}), and wires the minimal
 * cursor/click/keyboard/scene input pipeline ({@link #wireInput}).
 *
 * <p>The declarative UI this also used to host is gone: {@code pontif.gui} is now Anybox, on
 * VexelRay (docs/anybox.md). What is left here serves {@code pontif.plot} alone, which is the only
 * reason this module still exists — {@code pontif.shape} does not link without some {@code
 * pontif.plot} (docs/plotting.md, §The renderer seam).
 */
final class GuiTree {
    private GuiTree() {}

    /** The component captured on mouse-down, to confirm the release lands on the same one. */
    private static Component pressTarget;

    /**
     * Wires cursor hover + per-component click dispatch (the minimal dasum input pipeline), plus
     * the dasum-vis {@link SceneViewController} so a plot/scene viewport pans on drag and zooms on
     * scroll. The scene controller is consulted first on press: if it claims the gesture (a drag on
     * a viewport), no button activation is armed.
     */
    static void wireInput() {
        GlfwCallbacks.setCursorPosListener((w, x, y) -> {
            InputState.updateMousePos(x, y);
            LayoutResult lr = LatestLayout.result();
            Component r = LatestLayout.root();
            if (lr != null && r != null) {
                HoverState.update(HitTest.test(r, lr, (float) x, (float) y));
            }
            SceneViewController.onCursorMove(x, y);  // orbit/pan while dragging a viewport
        });
        GlfwCallbacks.setMouseButtonListener((w, button, action, mods) -> {
            if (button != Glfw.GLFW_MOUSE_BUTTON_LEFT) return;
            if (action == Glfw.GLFW_PRESS) {
                // Hit-test the LIVE tree at the cursor — do NOT trust the hover cache. A reactive
                // re-render (a Draw repaint) swaps the whole Component tree, but HoverState only
                // refreshes on cursor MOVE; a second click at the same spot would otherwise arm a
                // stale (dead) component that can never match the release's fresh hit-test, so the
                // click silently no-ops until the mouse moves. Probing the current layout here (as
                // the release branch does) makes a click register regardless of movement or repaint;
                // we refresh HoverState from the same probe so the cache stays consistent.
                LayoutResult lr = LatestLayout.result();
                Component r = LatestLayout.root();
                Component hit = (lr != null && r != null)
                        ? HitTest.test(r, lr, (float) InputState.mouseX(), (float) InputState.mouseY())
                        : null;
                HoverState.update(hit);
                // THE FOCUS GAP (docs/reactive-gui.md §7): onMouseDown places the caret but does NOT
                // set focus, and every char/key handler early-returns unless FocusState.focused() is
                // an editable Text — so keystrokes are silently swallowed until focus is set. Set it
                // here when the press lands on an editable Text. (The counter never needed this.)
                if (hit instanceof Component.Text t && t.editable()) FocusState.set(hit);
                // Focus an editable Text under the cursor (caret placement / selection start).
                TextInputController.onMouseDown(hit, InputState.mouseX(),
                        InputState.mouseY(), (mods & Glfw.GLFW_MOD_SHIFT) != 0);
                // Let a viewport claim the press first; if it does, don't also arm a button click.
                boolean scene = SceneViewController.onMouseDown(
                        hit, InputState.mouseX(), InputState.mouseY());
                pressTarget = scene ? null : hit;
            } else if (action == Glfw.GLFW_RELEASE) {
                SceneViewController.onMouseUp();
                LayoutResult lr = LatestLayout.result();
                Component r = LatestLayout.root();
                Component released = (lr != null && r != null)
                        ? HitTest.test(r, lr, (float) InputState.mouseX(), (float) InputState.mouseY())
                        : null;
                if (pressTarget != null && released == pressTarget) {
                    Handlers.activate(pressTarget, r);  // fires the button's Runnable
                }
                pressTarget = null;
            }
        });
        // Scroll zooms the viewport under the cursor; onScroll self-guards to interactive SceneViews.
        GlfwCallbacks.setScrollListener((w, xo, yo) -> SceneViewController.onScroll(HoverState.hovered(), yo));
        // Keyboard → the focused editable Text: typed characters, and edit keys (backspace, arrows,
        // Ctrl+A, …) on press/repeat. This is what makes an input field actually accept input.
        GlfwCallbacks.setCharListener((w, cp) -> TextInputController.onCharInput(cp));
        GlfwCallbacks.setKeyListener((w, key, sc, action, mods) -> {
            if (action != Glfw.GLFW_PRESS && action != Glfw.GLFW_REPEAT) return;
            boolean shift = (mods & Glfw.GLFW_MOD_SHIFT) != 0;
            boolean ctrl = (mods & Glfw.GLFW_MOD_CONTROL) != 0;
            // Editing keys and clipboard are SEPARATE entry points from onKey (which only does
            // caret navigation), so dispatch them explicitly — first match wins. Without this an
            // editable Text accepts typed chars but not backspace/delete/enter.
            MemorySegment win = MemorySegment.ofAddress(w);       // clipboard calls take the GLFW handle
            // GLFW letter keycodes match ASCII uppercase ('A'..'Z' = 65..90).
            if (ctrl && key == 'A' && TextInputController.onSelectAll()) return;
            if (ctrl && key == 'C' && TextInputController.onCopy(win)) return;
            if (ctrl && key == 'X' && TextInputController.onCut(win)) return;
            if (ctrl && key == 'V' && TextInputController.onPaste(win)) return;
            if (key == Glfw.GLFW_KEY_BACKSPACE && TextInputController.onBackspace(ctrl)) return;
            if (key == Glfw.GLFW_KEY_DELETE && TextInputController.onDelete(ctrl)) return;
            if (key == Glfw.GLFW_KEY_ENTER && TextInputController.onEnter()) return;
            if (key == Glfw.GLFW_KEY_TAB && TextInputController.onTab()) return;
            TextInputController.onKey(key, shift, ctrl);           // arrows / Home / End
        });
    }

    /**
     * Opens a window on the calling (root) thread, builds its root via {@code rootFactory}
     * <b>after</b> GL + font setup (so styled / plotted components resolve correctly), then renders
     * in the loop until the window closes. Shared by every GUI/plot window. Returns the inert
     * for-effect result.
     */
    static Object openWindowWithRoot(String title, boolean enableBloom,
            java.util.function.Supplier<Component> rootFactory) {
        return openWindowWithRoot(title, WIDTH, HEIGHT, enableBloom, rootFactory);
    }

    /** As {@link #openWindowWithRoot(String, boolean, java.util.function.Supplier)}, at an explicit
     *  window size — the {@code width}/{@code height} cfg keys flow here; the cfg-less render natives
     *  use the {@link GuiShared#WIDTH}×{@link GuiShared#HEIGHT} default overload. */
    static Object openWindowWithRoot(String title, int width, int height, boolean enableBloom,
            java.util.function.Supplier<Component> rootFactory) {
        return openWindowCore(title, width, height, enableBloom, rootFactory);
    }

    /**
     * The shared window core. Builds the root ONCE via {@code rootFactory} after GL + font setup,
     * then renders that retained root in the loop until the window closes (the loop re-renders only
     * on a dirty frame; targeted updates flip the dirty bit via {@code Invalidator}). Shared by the
     * reactive GUI and the build-once plot/scene windows alike.
     */
    private static Object openWindowCore(String title, int width, int height, boolean enableBloom,
            java.util.function.Supplier<Component> rootFactory) {
        try (GlfwContext glfw = GlfwContext.init();
             Window win = Window.create(width, height, title);
             Batcher batcher = new Batcher()) {
            // Gl.load() before any GL call (texture upload); see the texture-ordering bugfix.
            Gl.load();
            batcher.init();
            // Register the dasum-vis renderer for Component.SceneView (plots/scenes). Idempotent;
            // needs Gl.load() first. After this, Render.render dispatches SceneViews automatically.
            DasumVis.init();
            EmContext.setDpiScale(win.contentScaleX());

            Texture fontTexture = Texture.fromPngResource("/dasum/atlas/primary.png");
            AtlasData atlas = AtlasData.loadFromResource("/dasum/atlas/primary.json");
            FontGroups.register(FontGroup.of(FontGroups.DEFAULT, atlas, fontTexture));

            // The "math" font group: STIX Two Math (OFL) — italic math alphanumerics, Greek,
            // blackboard, operators, radical, delimiters. The math typesetter selects it via
            // TextLayer.withFontGroup("math") and picks glyphs by their Unicode math codepoints.
            Texture mathTexture = Texture.fromPngResource("/dasum/atlas/math.png");
            AtlasData mathAtlas = AtlasData.loadFromResource("/dasum/atlas/math.json");
            FontGroups.register(FontGroup.of("math", mathAtlas, mathTexture));

            // Build the retained root once after font + Em setup, so styled widgets resolve
            // correctly. It is never rebuilt — updates mutate its widgets in place (see setText).
            Component root = rootFactory.get();
            // Layout guardrail (docs/plotting.md): lint the built tree BEFORE rendering. Fonts are
            // registered and Em is set, so the geometry pass can lay it out — a collapsed plot/scene
            // (the plot-in-a-column trap) throws here with a fix hint instead of rendering blank.
            Ui.lint(root);
            wireInput();

            // 3D plot windows opt into HDR + bloom: the frame renders into an offscreen HDR target,
            // then bright-pass/blur/tonemap composites to the screen (the emissive glow blooms).
            BloomPass bloom = enableBloom ? new BloomPass() : null;
            if (bloom != null) bloom.init();

            EventLoop loop = new EventLoop(win, () -> {
                int fbW = win.framebufferWidth();
                int fbH = win.framebufferHeight();
                float[] projection = Projection.orthoTopLeft(fbW, fbH);
                if (bloom != null) bloom.begin(fbW, fbH);   // bind HDR target; frame renders into it
                Gl.glViewport(0, 0, fbW, fbH);
                Gl.glClearColor(BACKGROUND.r(), BACKGROUND.g(), BACKGROUND.b(), BACKGROUND.a());
                Gl.glClear(Gl.GL_COLOR_BUFFER_BIT);
                LayoutResult layout = Layout.compute(root, new PixelRect(0f, 0f, fbW, fbH));
                LatestLayout.store(root, layout);  // required so hit-testing has coordinates
                batcher.beginFrame(fbH);
                Render.render(root, layout, batcher, projection);
                batcher.endFrame(projection);
                if (bloom != null) bloom.end(fbW, fbH);     // bloom + composite to the screen
            });
            loop.run();  // blocks on this (root) thread until the window is closed
            if (bloom != null) bloom.close();               // free FBOs while the GL context is alive
        }
        return new IrInterpreter.DriveResult();
    }
}

package sibarum.pontif.vulkan;

import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.runtime.module.Extension;

import java.util.Map;

/**
 * The {@code pontif.vulkan} language extension — the surface for opening a Vulkan window that
 * presents a shader authored in SupirVast's {@code core} IR (compiled to SPIR-V), as opposed to the
 * OpenGL {@code pontif.gui} toolkit.
 *
 * <p><b>Phase 0/3 spike.</b> One native, {@code vulkanWindow}, opens the window and presents a fixed
 * fullscreen constant-color fragment shader (authored in {@code core} by {@code Fullscreen}). This
 * proves the editor → {@code .ptf} → Vulkan-window path end to end; a Pontif-<em>authored</em>
 * fragment shader (lowering a {@code distance} method to SPIR-V) is the next phase.
 *
 * <p>Discovered via ServiceLoader ({@code META-INF/services}); the windowed loop must own the root
 * thread, so a {@code vulkanWindow} program is routed to the main-thread {@code GuiLauncher} by the
 * editor (its {@code requires pontif.vulkan} marks it windowed).
 */
public final class VulkanExtension implements Extension {

    @Override
    public String moduleName() {
        return "pontif.vulkan";
    }

    @Override
    public Map<String, NativeCalls.NativeCall> calls() {
        return Map.of(
                "vulkanWindow", VulkanBridge::openVulkanWindow,
                "renderSdf", VulkanBridge::renderSdf);
    }
}

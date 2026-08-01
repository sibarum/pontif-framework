package sibarum.pontif.vulkan;

import dev.supirvast.vastir.preview.WindowedVulkanContext;
import dev.supirvast.vastir.tools.Fullscreen;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;

import java.math.BigDecimal;
import java.util.List;

/**
 * The native façade of the {@code pontif.vulkan} extension. Parses the primitives-only arguments and
 * delegates to SupirVast's reusable windowed host ({@link WindowedVulkanContext}) with fullscreen
 * shaders from {@link Fullscreen} — the boundary carries only Pontif primitives, never Java handles.
 */
public final class VulkanBridge {

    private VulkanBridge() {
    }

    private static final int DEFAULT_WIDTH = 960;
    private static final int DEFAULT_HEIGHT = 600;

    /**
     * {@code vulkanWindow(title, width, height)}: open a Vulkan window presenting a fullscreen
     * constant-color fragment shader authored in the {@code core} IR, and block until it closes.
     * Returns the inert for-effect result.
     *
     * <p>Testing hook: if {@code PONTIF_VULKAN_MAX_FRAMES} is a positive integer, the window presents
     * that many frames and exits — a hands-off smoke test of the whole path on real hardware.
     */
    public static Object openVulkanWindow(List<Object> args, NativeCalls.Context ctx) {
        String title = !args.isEmpty() && args.get(0) instanceof StringValue s ? s.content() : "Pontif — Vulkan";
        int width = intArg(args, 1, DEFAULT_WIDTH);
        int height = intArg(args, 2, DEFAULT_HEIGHT);
        int maxFrames = envInt("PONTIF_VULKAN_MAX_FRAMES", 0);

        byte[] vertexSpirv = Fullscreen.triangleVertexSpirv();
        byte[] fragmentSpirv = Fullscreen.constantColorFragmentSpirv(0.11, 0.22, 0.44, 1.0);
        try (WindowedVulkanContext window = new WindowedVulkanContext(title, width, height,
                vertexSpirv, Fullscreen.ENTRY_POINT, fragmentSpirv, Fullscreen.ENTRY_POINT)) {
            window.run(maxFrames);
        }
        return new IrInterpreter.DriveResult();
    }

    /** Reads a positive integer arg (an {@code Int} arrives boxed as {@code Long}, or a decimal). */
    private static int intArg(List<Object> args, int i, int def) {
        if (i >= args.size()) {
            return def;
        }
        Object v = args.get(i);
        if (v instanceof Long l && l > 0) {
            return Math.toIntExact(l);
        }
        if (v instanceof BigDecimal d && d.signum() > 0) {
            return d.intValue();
        }
        return def;
    }

    private static int envInt(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}

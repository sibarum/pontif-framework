package sibarum.pontif.vulkan;

import dev.supirvast.vastir.preview.WindowedVulkanContext;
import dev.supirvast.vastir.tools.Fullscreen;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.NativeCalls;
import sibarum.pontif.supirvast.ScalarFieldFragment;

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
     * {@code present(Pipeline)}: the declarative graphics surface — a {@code Pipeline(config, passes)} value
     * describing a window ({@code {title, width, height}}) and its passes. This first slice presents the pipeline's
     * {@code FullscreenPass}: it reflects the pass's Pontif shader function, lowers the body to a SPIR-V fragment
     * ({@link ScalarFieldFragment}), and renders it fullscreen. Multi-pass compositing arrives with render targets.
     */
    public static Object present(List<Object> args, NativeCalls.Context ctx) {
        if (args.isEmpty() || !(args.get(0) instanceof RecordValue pipeline)) {
            throw new IllegalArgumentException("present expects a Pipeline(config, passes) value");
        }
        RecordValue config = pipeline.members().get("config") instanceof RecordValue c ? c : null;
        Object fragment = firstFullscreenFragment(pipeline.members().get("passes"));
        if (fragment == null) {
            throw new IllegalArgumentException("the Pipeline declares no FullscreenPass to present");
        }
        NativeCalls.ReflectedFunction fn = reflect(fragment, ctx);
        return presentShader(fn,
                memberStr(config, "title", "Pontif — Vulkan"),
                memberInt(config, "width", DEFAULT_WIDTH),
                memberInt(config, "height", DEFAULT_HEIGHT));
    }

    /**
     * {@code renderSdf(shade)}: the direct spike surface — reflect a Pontif shader function and present it
     * fullscreen (the pre-{@code present} entry point, kept for the simple case). See {@link #present} for the
     * declarative pipeline form.
     */
    public static Object renderSdf(List<Object> args, NativeCalls.Context ctx) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("renderSdf expects a shader function of screen uv");
        }
        return presentShader(reflect(args.get(0), ctx), "Pontif — SDF", DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * {@code vulkanWindow(title, width, height)}: open a Vulkan window presenting a fixed constant-color
     * fragment shader authored in the {@code core} IR (the Phase 0 spike). Returns the inert for-effect result.
     */
    public static Object openVulkanWindow(List<Object> args, NativeCalls.Context ctx) {
        String title = !args.isEmpty() && args.get(0) instanceof StringValue s ? s.content() : "Pontif — Vulkan";
        int width = intArg(args, 1, DEFAULT_WIDTH);
        int height = intArg(args, 2, DEFAULT_HEIGHT);
        byte[] vertexSpirv = Fullscreen.triangleVertexSpirv();
        byte[] fragmentSpirv = Fullscreen.constantColorFragmentSpirv(0.11, 0.22, 0.44, 1.0);
        return open(title, width, height, vertexSpirv, fragmentSpirv);
    }

    /** Lowers the reflected shader to a fragment, pairs it with the UV fullscreen vertex, and presents it. */
    private static Object presentShader(NativeCalls.ReflectedFunction fn, String title, int width, int height) {
        if (fn == null) {
            throw new IllegalArgumentException(
                    "a shader must be a function value (e.g. $shade[Vec2, Frame]) or a resolvable name; "
                            + "reflection requires running under the interpreter engine");
        }
        byte[] fragmentSpirv = ScalarFieldFragment.lowerParams(fn.params(), fn.body());
        byte[] vertexSpirv = Fullscreen.triangleVertexWithUvSpirv();
        return open(title, width, height, vertexSpirv, fragmentSpirv);
    }

    /** Opens the windowed host and runs its loop (frame-capped by PONTIF_VULKAN_MAX_FRAMES for smoke tests). */
    private static Object open(String title, int width, int height, byte[] vertexSpirv, byte[] fragmentSpirv) {
        int maxFrames = envInt("PONTIF_VULKAN_MAX_FRAMES", 0);
        try (WindowedVulkanContext window = new WindowedVulkanContext(title, width, height,
                vertexSpirv, Fullscreen.ENTRY_POINT, fragmentSpirv, Fullscreen.ENTRY_POINT)) {
            window.run(maxFrames);
        }
        return new IrInterpreter.DriveResult();
    }

    /** Reflect a shader function value (a {@code $fn[…]} metaref / lambda) or its name to (params, body). */
    private static NativeCalls.ReflectedFunction reflect(Object shade, NativeCalls.Context ctx) {
        if (shade instanceof StringValue s) {
            NativeCalls.ReflectedFunction byTwo = ctx.reflectFunctionByName(s.content(), 2);
            return byTwo != null ? byTwo : ctx.reflectFunctionByName(s.content(), 1);
        }
        return ctx.reflectFunction(shade);
    }

    /** The {@code fragment} of the first {@code FullscreenPass} in a passes aggregate, or {@code null}. */
    private static Object firstFullscreenFragment(Object passes) {
        if (!(passes instanceof RecordValue aggregate)) {
            return null;
        }
        for (Object pass : aggregate.members().values()) {
            if (pass instanceof RecordValue p && "FullscreenPass".equals(bareType(p))) {
                return p.members().get("fragment");
            }
        }
        return null;
    }

    private static String bareType(RecordValue value) {
        String name = value.typeName();
        if (name == null) {
            return "";
        }
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    private static String memberStr(RecordValue record, String field, String def) {
        return record != null && record.members().get(field) instanceof StringValue s ? s.content() : def;
    }

    private static int memberInt(RecordValue record, String field, int def) {
        Object v = record == null ? null : record.members().get(field);
        if (v instanceof Long l && l > 0) {
            return Math.toIntExact(l);
        }
        if (v instanceof BigDecimal d && d.signum() > 0) {
            return d.intValue();
        }
        return def;
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

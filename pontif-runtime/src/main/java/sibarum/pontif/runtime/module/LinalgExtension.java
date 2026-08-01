package sibarum.pontif.runtime.module;

/**
 * The builtin <b>linear-algebra extension</b> ({@code pontif.linalg}) — the vector types ({@code Vec2},
 * {@code Vec3}, {@code Vec4}, and in future matrices / quaternions) and their utilities as instance methods
 * ({@code v.length()}, {@code a.dot(b)}, {@code v.normalize()}).
 *
 * <p>Pure Pontif: the module has no native calls. Its method bodies are ordinary Pontif that delegate to
 * {@code pontif.math}'s scalar functions (so they run on the CPU); a shader's vector-method call lowers to the
 * native SPIR-V op in {@code pontif-supirvast}. Installed like {@link MathExtension} — directly, after
 * {@code pontif.math}, which it requires. The source is loaded from the classpath resource
 * {@code /pontif-modules/pontif.linalg.ptf} by {@link Extension#pontifSource()}.
 */
public final class LinalgExtension implements Extension {

    public static final LinalgExtension INSTANCE = new LinalgExtension();

    private LinalgExtension() {
    }

    @Override
    public String moduleName() {
        return "pontif.linalg";
    }
}

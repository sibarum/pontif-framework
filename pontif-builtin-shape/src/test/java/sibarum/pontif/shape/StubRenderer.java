package sibarum.pontif.shape;

import sibarum.pontif.runtime.module.Extension;

/**
 * The renderer {@code pontif.shape} requires, reduced to the seam and nothing else — a test-scope
 * {@code pontif.plot} carrying one layer struct and the call that hands layers over.
 *
 * <p><b>Why this exists.</b> {@code pontif.shape} opens with {@code requires pontif.plot.{Volume, scene}}, so
 * without <em>some</em> {@code pontif.plot} the module does not link at all — not even for
 * {@code distanceAt(Sphere(1.0), …)}, which touches no renderer. This module's tests used to satisfy that by
 * depending on {@code pontif-builtin-gui}, which dragged a whole GLFW/OpenGL stack in behind it and made a
 * pure SDF-algebra assertion a consumer of a window toolkit. Six lines of Pontif satisfy it instead.
 *
 * <p><b>Registered as a service, not installed by each test.</b> It is on the test classpath's
 * {@code META-INF/services/sibarum.pontif.runtime.module.Extension}, so the runtime discovers it exactly as it
 * discovers a real extension — which is what keeps {@link DiscoveryTest} honest: that test deliberately installs
 * nothing and asserts {@code pontif.shape} <em>and its transitive plot dependency</em> both self-register.
 *
 * <p><b>What it is not.</b> It draws nothing: {@code renderScene}'s body is the same placeholder the real module
 * uses, and a test that wants to see what reached the renderer registers a capturing native over it
 * ({@link RenderLoweringTest}). Nothing here asserts anything about a renderer — the layer struct's shape is
 * shape's <em>request</em>, and what a renderer does with it is that renderer's own business to test.
 *
 * <p>The signature is the contract, so it is worth reading as one: this — plus {@code sdfMap}, which shape backs
 * itself — is the entire surface the next renderer has to provide for {@code render} and
 * {@code previewGradientField} to work (docs/plotting.md, §The renderer seam).
 */
public final class StubRenderer implements Extension {

    @Override
    public String moduleName() {
        return "pontif.plot";
    }

    /**
     * Synthesized rather than a resource file, which is the documented reason to override this at all: a stub
     * that shipped as {@code /pontif-modules/pontif.plot.ptf} would be a second file claiming to be the plot
     * module, and the one place it is allowed to exist is here.
     */
    @Override
    public String pontifSource() {
        return """
                requires pontif.core.{Stream}
                exports @.{Volume, scene}

                # A volumetric layer: the sampled field, the box it was sampled over, and how to show it.
                struct Volume(vs:_, xlo:Decimal, xhi:Decimal, ylo:Decimal, yhi:Decimal,
                              zlo:Decimal, zhi:Decimal, opacity:Decimal, normals:Bool, stride:Int)

                # (native) Hand a scene to the renderer. Nothing backs it here, so nothing opens.
                function renderScene(cfg:_, layers:_):Stream[String] -> {}

                function scene(cfg:_, layers:_):Stream[String] -> renderScene(cfg, layers)
                """;
    }
}

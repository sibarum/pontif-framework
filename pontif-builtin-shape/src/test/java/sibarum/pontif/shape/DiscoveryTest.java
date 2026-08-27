package sibarum.pontif.shape;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.PontifRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression for the "module can't be found" wiring gap — the editor couldn't resolve
 * {@code pontif.shape} because it wasn't hand-installed there. This test deliberately does NOT call
 * {@code Extensions.install}: it relies purely on ServiceLoader auto-discovery ({@code BuiltinModules}
 * installs every {@code Extension} on the classpath). A program that {@code requires pontif.shape}
 * must compile and run with no wiring at any entry point.
 *
 * <p>It used to prove one thing more — that shape's <em>transitive plot dependency</em> self-registered too,
 * because shape did not link without a renderer module present. It has no such dependency now: the views are
 * values it returns. So this is back to asserting one module, which is what it was always about.
 */
class DiscoveryTest {

    @Test
    void shapeModule_autoDiscovered_withNoExplicitInstall() {
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compile(
                        "requires pontif.shape.{Sphere, distanceAt}\n"
                      + "distanceAt(Sphere(1.0), 0.0, 0.0, 0.0) == -1.0", "disc.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "pontif.shape should be auto-discovered; got " + r.text());
        assertEquals("true", r.text());
    }
}

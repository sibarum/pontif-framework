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
 * — which itself {@code requires pontif.plot} — must compile and run, proving both the shape module
 * and its transitive plot dependency self-register with no wiring at any entry point.
 */
class DiscoveryTest {

    @Test
    void shapeModule_autoDiscovered_withNoExplicitInstall() {
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt(
                        "requires pontif.shape.{Sphere, distanceAt}\n"
                      + "distanceAt(Sphere(1.0), 0.0, 0.0, 0.0) == -1.0", "disc.ptf"),
                PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "pontif.shape should be auto-discovered; got " + r.text());
        assertEquals("true", r.text());
    }
}

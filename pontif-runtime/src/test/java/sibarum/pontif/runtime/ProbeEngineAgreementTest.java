package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every probe that compiles produces the same answer on every engine.
 *
 * <p>{@code ProbeHarnessTest} runs the same 151-probe corpus but RECORDS rather than asserts, and
 * it runs only the interpreter — so the corpus that exists to be the empirical works/doesn't
 * inventory could not see a divergence between the two engines, which is the one class of bug
 * that makes a compiler-accepted program mean two different things
 * (docs/soundness-holes.md).
 *
 * <p>Pointing it at both engines found five, in three groups, all now fixed:
 *
 * <ul>
 *   <li><b>Trait-view attributes</b> (3 probes) — a field no stored record carries, computed by a
 *       producer resolved on the value's type. The interpreter has always resolved it; the
 *       Truffle field access had no way to ask, and answered "Record has no field 'weight'".</li>
 *   <li><b>An operator over a trait-bounded type variable</b> (2 probes) — {@code sum[type
 *       E:Numeric](a:E, b:E) -> a + b} has no operand sort to route on until the argument
 *       arrives, so it survives to runtime unrouted. The interpreter finishes the job by
 *       dispatch; the Truffle node reached {@code (Long) leftValue} and died.</li>
 *   <li><b>Tuple concatenation</b> — found by asking a question the corpus does not: only one
 *       engine had the {@code +}-concatenates-sequences rule.</li>
 * </ul>
 *
 * <p>This test is the net that keeps them fixed, and it grows for free — a probe added for any
 * other reason is now also an agreement case.
 */
class ProbeEngineAgreementTest {

    @Test
    void everyProbeAgreesAcrossEngines() throws Exception {
        Path root = Path.of("src", "test", "resources", "probes");
        assertTrue(Files.isDirectory(root), () -> "no probe corpus at " + root.toAbsolutePath());
        PontifCompiler compiler = new PontifCompiler();
        PontifRunner runner = new PontifRunner();
        List<Path> dirs;
        try (Stream<Path> s = Files.list(root)) {
            dirs = s.filter(Files::isDirectory).sorted().toList();
        }
        List<String> divergences = new ArrayList<>();
        int ran = 0;
        for (Path dir : dirs) {
            Path entry = dir.resolve("entry.ptf");
            String name = dir.getFileName().toString();
            if (!Files.exists(entry)) continue;
            // A probe the compiler REJECTS is not an agreement case — both engines are handed
            // nothing to run. Those are the inventory's BUG rows, tracked in language-inventory.md.
            var compiled = compiler.compile(Files.readString(entry), name + "/entry.ptf", dir);
            if (compiled instanceof PontifCompiler.CompileResult.Failed) continue;
            ran++;
            String reference = null;
            Engine referenceEngine = null;
            for (Engine engine : Engine.values()) {
                String out = outcome(runner, compiled, engine);
                if (reference == null) {
                    reference = out;
                    referenceEngine = engine;
                } else if (!reference.equals(out)) {
                    divergences.add(name + ":\n    " + referenceEngine + " => " + reference
                            + "\n    " + engine + " => " + out);
                }
            }
        }
        assertTrue(divergences.isEmpty(),
                () -> "engines disagree on " + divergences.size() + " probe(s):\n"
                        + String.join("\n", divergences));
        // A guard on the guard: if linking or the corpus path breaks, this test would otherwise
        // pass vacuously by running nothing at all.
        final int actuallyRan = ran;
        assertTrue(ran >= 80, () -> "expected the corpus to run; only " + actuallyRan + " probes did");
    }

    /** One engine's answer, with a thrown exception counted as an outcome rather than a failure. */
    private static String outcome(
            PontifRunner runner, PontifCompiler.CompileResult compiled, Engine engine) {
        try {
            var run = runner.run(compiled, engine);
            return (run.isError() ? "ERROR: " : "OK: ") + run.text();
        } catch (Throwable t) {
            return "THREW: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }
}

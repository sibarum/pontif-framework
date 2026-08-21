package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs every language-interaction probe under {@code src/test/resources/probes/} and
 * prints a tab-separated matrix line per probe — the empirical works/doesn't inventory
 * (dispatch, traits, destructuring, generics, inference; single- and cross-module).
 *
 * <p>Each probe is a directory {@code probes/<name>/} containing an {@code entry.ptf}
 * (the module run) plus any sibling {@code .ptf} modules it {@code requires} (resolved
 * with the probe dir as the resolve root). The harness never asserts — it RECORDS, so
 * one run surfaces the whole matrix. Output lines: {@code PROBE\t<name>\t<status>\t<detail>}
 * where status is OK / COMPILE_FAIL / RUNTIME_FAIL / CRASH / NO_ENTRY.
 */
class ProbeHarnessTest {

    @Test
    void runAllProbes() throws Exception {
        Path root = Path.of("src", "test", "resources", "probes");
        if (!Files.isDirectory(root)) {
            System.out.println("PROBE_HARNESS no probes dir at " + root.toAbsolutePath());
            return;
        }
        PontifCompiler compiler = new PontifCompiler();
        PontifRunner runner = new PontifRunner();
        List<Path> dirs;
        try (Stream<Path> s = Files.list(root)) {
            dirs = s.filter(Files::isDirectory).sorted().toList();
        }
        int n = 0;
        for (Path dir : dirs) {
            Path entry = dir.resolve("entry.ptf");
            String name = dir.getFileName().toString();
            if (!Files.exists(entry)) {
                System.out.println("PROBE\t" + name + "\tNO_ENTRY\t");
                continue;
            }
            String status;
            String detail;
            try {
                var r = compiler.compile(Files.readString(entry), name + "/entry.ptf", dir);
                if (r instanceof PontifCompiler.CompileResult.Failed f) {
                    status = "COMPILE_FAIL";
                    detail = f.error().text();
                } else {
                    var run = runner.run(r, Engine.INTERPRETER);
                    status = run.isError() ? "RUNTIME_FAIL" : "OK";
                    detail = run.text();
                }
            } catch (Throwable t) {
                status = "CRASH";
                detail = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            System.out.println("PROBE\t" + name + "\t" + status + "\t"
                    + (detail == null ? "" : detail.replace("\n", " ").replace("\t", " ")));
            n++;
        }
        System.out.println("PROBE_HARNESS ran " + n + " probes");
    }
}

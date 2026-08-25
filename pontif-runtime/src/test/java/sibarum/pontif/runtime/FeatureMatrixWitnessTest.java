package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every witness `docs/feature-matrix.md` cites exists.
 *
 * <p>The matrix carries a no-lie rule — a supported cell must name a passing test or probe that
 * witnesses it — and a staleness discipline that says a cited test which was renamed or deleted
 * makes its cell "stale by construction: that's the detector". The detector was never wired to
 * anything, so nothing detected. Checking the citations by hand found three dead ones, and the
 * worst was not a rename: the `emit` row cited `EventEmitCheck` rejecting a provable non-Event,
 * a pass that has since been RETIRED because the rule was reversed — emit now accepts any value
 * by design. The ledger asserted the opposite of the language, with a citation to prove it.
 *
 * <p>So the detector runs. A citation is recognised by shape, which keeps prose out of it: a
 * backticked name ending in {@code Test} (optionally {@code Class.method}) is a JUnit witness, and
 * one containing {@code __} is a probe directory. Words like {@code Stream} or {@code Refine} are
 * types and column headings, not claims about a test, and are left alone.
 */
class FeatureMatrixWitnessTest {

    /** `Name` or `Name.member`, where Name ends in Test — or anything containing a probe's `__`. */
    private static final Pattern CITATION = Pattern.compile("`([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)?)`");

    @Test
    void everyCitedWitnessExists() throws IOException {
        Path repo = repoRoot();
        Path matrix = repo.resolve("docs").resolve("feature-matrix.md");
        assertTrue(Files.exists(matrix), () -> "feature matrix not found at " + matrix.toAbsolutePath());

        String text = Files.readString(matrix);
        int witnesses = text.indexOf("## Witnesses");
        String body = witnesses >= 0 ? text.substring(witnesses) : text;

        Map<String, List<Path>> classes = javaFilesByClassName(repo);
        Set<String> probes = probeNames(repo);

        List<String> stale = new ArrayList<>();
        int checked = 0;
        Matcher m = CITATION.matcher(body);
        while (m.find()) {
            String cited = m.group(1);
            String head = cited.contains(".") ? cited.substring(0, cited.indexOf('.')) : cited;
            if (cited.contains("__")) {
                checked++;
                // A probe may be cited elided (`traits__18…`); match on the prefix before the ellipsis.
                String prefix = cited.replace("…", "");
                if (probes.stream().noneMatch(p -> p.startsWith(prefix))) {
                    stale.add("probe directory '" + cited + "'");
                }
                continue;
            }
            if (!head.endsWith("Test")) continue;   // prose, a type name, or a pass name — not a witness
            checked++;
            List<Path> paths = classes.get(head);
            if (paths == null) {
                stale.add("test class '" + head + "'");
                continue;
            }
            if (!cited.contains(".")) continue;
            String method = cited.substring(cited.indexOf('.') + 1);
            boolean found = false;
            for (Path p : paths) {
                if (Files.readString(p).contains(method)) {
                    found = true;
                    break;
                }
            }
            if (!found) stale.add("method '" + cited + "'");
        }

        assertTrue(stale.isEmpty(),
                () -> "docs/feature-matrix.md cites " + stale.size() + " witness(es) that no longer exist"
                        + " — the cell each one supports is stale by construction:\n  "
                        + String.join("\n  ", stale));
        final int seen = checked;
        assertTrue(checked > 50,
                () -> "expected the matrix to carry citations; found only " + seen);
    }

    /** The repository root — tests run with the module directory as the working directory. */
    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        return Files.exists(here.resolve("docs")) ? here : here.getParent();
    }

    private static Map<String, List<Path>> javaFilesByClassName(Path repo) throws IOException {
        Map<String, List<Path>> byName = new HashMap<>();
        try (Stream<Path> files = Files.walk(repo)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> !p.toString().contains("worktrees"))
                    .forEach(p -> {
                        String file = p.getFileName().toString();
                        byName.computeIfAbsent(file.substring(0, file.length() - 5), k -> new ArrayList<>())
                                .add(p);
                    });
        }
        return byName;
    }

    private static Set<String> probeNames(Path repo) throws IOException {
        Path dir = repo.resolve("pontif-runtime/src/test/resources/probes");
        if (!Files.isDirectory(dir)) return Set.of();
        try (Stream<Path> s = Files.list(dir)) {
            Set<String> names = new HashSet<>();
            s.filter(Files::isDirectory).forEach(p -> names.add(p.getFileName().toString()));
            return names;
        }
    }
}

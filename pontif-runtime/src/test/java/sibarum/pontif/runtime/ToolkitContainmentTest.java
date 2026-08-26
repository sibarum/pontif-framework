package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window toolkit lives in two modules, and the build fails if it spreads.
 *
 * <p>Dasum — the GLFW/OpenGL toolkit the editor and the {@code pontif.gui} / {@code pontif.plot} modules were
 * built on — is being replaced by VexelRay. The replacement is not a port: {@code pontif-builtin-gui} is to be
 * deleted, and {@code pontif-playground} is transitional and goes with it. So the useful state to be in
 * <b>before</b> that happens is one where the two leave together and nothing else has to change, and the useful
 * thing to know is the moment that stops being true.
 *
 * <p>It stopped being true twice already, quietly. {@code pontif-builtin-shape} depended on
 * {@code pontif-builtin-gui} to borrow its render path — so a pure SDF-algebra assertion pulled a window toolkit
 * onto the test classpath, and one of its tests asserted things about Dasum's own layer objects. And the toolkit's
 * version was a property of the <em>root</em> pom, which is the build declaring a fact about a GUI it should not
 * know exists. Neither was visible from anywhere; both are what this test now prevents.
 *
 * <h2>What counts as a mention</h2>
 * The name, in source or build text, anywhere. Not a class reference, not a dependency — the <b>word</b>,
 * including in a comment. That is deliberately blunt, and the bluntness is the point: a comment saying "only the
 * string crosses to dasum" is a module still explaining itself in terms of the toolkit, and after this migration
 * it will be explaining itself in terms of one that no longer exists.
 *
 * <p>Two things are exempt, and only two. {@code docs/} is skipped whole — the migration has to be able to
 * describe itself somewhere, and one Pontif file lives there as a language reference rather than as a module.
 * And this file is skipped, because a rule about a name cannot be written without writing the name.
 */
class ToolkitContainmentTest {

    /** The toolkit's name, lowercased for a case-insensitive search. */
    private static final String TOOLKIT = "dasum";

    /**
     * The two modules allowed to name it, and the reason there are exactly two:
     * <ul>
     *   <li>{@code pontif-builtin-gui} — the {@code pontif.gui} / {@code pontif.plot} extension. It <em>is</em>
     *       the toolkit's face to the language; there is nothing to isolate it from itself.</li>
     *   <li>{@code pontif-playground} — the editor, its only consumer, and transitional.</li>
     * </ul>
     * <b>Adding a third entry is not how a failure of this test is fixed.</b> A module that needs to draw needs
     * the drawing to reach it as data — a string, a grid of numbers, a value — which is what the two modules that
     * used to breach this rule both turned out to want anyway.
     */
    private static final Set<String> MAY_NAME_IT = Set.of("pontif-builtin-gui", "pontif-playground");

    /** This file: a rule about a name cannot be written without writing the name. */
    private static final String SELF = "ToolkitContainmentTest.java";

    /** What is read: hand-written source and build files. Generated output and docs are not source. */
    private static final List<String> SCANNED = List.of(".java", ".ptf", "pom.xml");

    @Test
    void onlyTheEditorAndItsGuiExtensionNameTheWindowToolkit() throws IOException {
        Path repo = repoRoot();
        List<String> violations = new ArrayList<>();
        List<Path> files = scannedFiles(repo);
        for (Path file : files) {
            Path where = repo.relativize(file);
            if (!where.getFileName().toString().equals(SELF)
                    && !MAY_NAME_IT.contains(moduleOf(where))
                    && Files.readString(file).toLowerCase().contains(TOOLKIT)) {
                violations.add(String.valueOf(where));
            }
        }
        assertTrue(files.size() > 100, () -> "the walk found only " + files.size() + " files — it is looking in "
                + "the wrong place, which would make this test pass by seeing nothing");
        assertEquals(List.of(), violations,
                "a module outside " + MAY_NAME_IT + " names the window toolkit. It is being replaced and those "
                        + "two are being deleted together, so a third module naming it is a third thing to fix "
                        + "on the day. Take what this module actually needs across as data (a GLSL string, a "
                        + "sampled grid, a Picture) and let a renderer consume it — see docs/plotting.md, "
                        + "§The renderer seam. Widening the allow-list is not the fix.");
    }

    /**
     * The detector's own proof of life. A guard that silently matched nothing would look exactly like a clean
     * repository — the failure mode that let {@code docs/feature-matrix.md}'s citation rule rot for months
     * ({@link FeatureMatrixWitnessTest}).
     */
    @Test
    void theDetectorReportsAModuleThatNamesIt() {
        assertEquals("pontif-core", moduleOf(Path.of("pontif-core", "src", "main", "java", "X.java")));
        assertTrue(!MAY_NAME_IT.contains("pontif-core"), "the detector's own example must not be exempt");
        assertTrue("import sibarum.dasum.gui.Window;".toLowerCase().contains(TOOLKIT),
                "the match is on the word, so an import, a groupId and a comment all count");
    }

    /** The module a file belongs to: the first path segment under the repo root. */
    private static String moduleOf(Path file) {
        return file.getNameCount() == 0 ? "" : file.getName(0).toString();
    }

    private static List<Path> scannedFiles(Path repo) throws IOException {
        try (Stream<Path> walk = Files.walk(repo)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> SCANNED.stream().anyMatch(ext -> p.getFileName().toString().endsWith(ext)))
                    // Build output, git internals and agent worktrees are copies, not the tree under review.
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> !p.toString().contains("worktrees"))
                    .filter(p -> !p.toString().contains(".git"))
                    // Prose is exempt: the migration has to be able to describe itself somewhere, and one
                    // Pontif file lives in docs/ as a language reference rather than as a module.
                    .filter(p -> !repo.relativize(p).startsWith("docs"))
                    .sorted()
                    .toList();
        }
    }

    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        return Files.exists(here.resolve("docs")) ? here : here.getParent();
    }
}

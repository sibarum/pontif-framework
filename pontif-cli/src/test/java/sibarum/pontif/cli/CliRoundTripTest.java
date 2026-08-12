package sibarum.pontif.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end CLI behaviour over the real compiler: scaffold a project with
 * {@code new}, package it with {@code pack}, and run both the source directory
 * and the resulting {@code .ptfpkg} — all must yield the same value the sample
 * source computes ({@code greet(41)} = 42). Exercises the novel CLI logic
 * (manifest reading, the artifact zip/unzip round-trip) rather than the
 * runtime, which is covered in its own module.
 */
class CliRoundTripTest {

    /** Invoke the CLI like the shell would; return [exitCode, stdout]. */
    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            int code = new CommandLine(new Pontif()).execute(args);
            return new Result(code, out.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    private record Result(int exitCode, String stdout) {}

    @Test
    void newThenPackThenRun_roundTrips(@TempDir Path tmp) throws Exception {
        Path projectDir = tmp.resolve("demo.app");

        // new
        Result created = run("new", "demo.app", "--dir", projectDir.toString());
        assertEquals(0, created.exitCode(), created.stdout());
        assertTrue(Files.isRegularFile(projectDir.resolve(PackageManifest.MARKER)));
        assertTrue(Files.isRegularFile(projectDir.resolve("app.ptf")));

        // run the source directory
        Result ranDir = run("run", projectDir.toString());
        assertEquals(0, ranDir.exitCode(), ranDir.stdout());
        assertEquals("42", ranDir.stdout().strip());

        // pack to an explicit output, then run the artifact — same value
        Path pkg = tmp.resolve("demo.ptfpkg");
        Result packed = run("pack", projectDir.toString(), "-o", pkg.toString());
        assertEquals(0, packed.exitCode(), packed.stdout());
        assertTrue(Files.isRegularFile(pkg), "artifact should exist: " + pkg);

        Result ranPkg = run("run", pkg.toString());
        assertEquals(0, ranPkg.exitCode(), ranPkg.stdout());
        assertEquals("42", ranPkg.stdout().strip());
    }

    @Test
    void packRefusesABrokenProject(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(PackageManifest.MARKER), "entry = \"broke\"\n");
        Files.writeString(tmp.resolve("broke.ptf"), "module broke\nfunction f(x:Int):Int -> x +\n");
        Result packed = run("pack", tmp.toString());
        assertEquals(1, packed.exitCode(), "a project that does not compile must not pack");
    }

    @Test
    void artifactRoundTripPreservesSources(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("src");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve(PackageManifest.MARKER), "name = \"x\"\nversion = \"9.9\"\n");
        Files.writeString(root.resolve("a.ptf"), "module a\n0\n");
        Files.writeString(root.resolve("sub/b.ptf"), "module b\n0\n");

        Path pkg = tmp.resolve("x.ptfpkg");
        Artifacts.writeZip(pkg, root, List.of(
                root.resolve(PackageManifest.MARKER), root.resolve("a.ptf"), root.resolve("sub/b.ptf")));

        Path back = tmp.resolve("back");
        Artifacts.unpack(pkg, back);
        assertEquals("module a\n0\n", Files.readString(back.resolve("a.ptf")));
        assertEquals("module b\n0\n", Files.readString(back.resolve("sub/b.ptf")));
        assertTrue(Files.isRegularFile(back.resolve(PackageManifest.MARKER)));
    }
}

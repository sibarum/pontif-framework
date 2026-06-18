package sibarum.pontif.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The {@code .ptfpkg} artifact format: a plain zip of a project's
 * {@code module.ptf.toml} marker and its {@code .ptf} sources, paths preserved
 * relative to the project root. Source bundle, not compiled IR — so execution
 * re-runs the full compile/proof pipeline and an artifact can never carry
 * unproven code past the gates.
 *
 * <p>This class owns the read side (unpack + run); {@link Pack} owns the write
 * side. Both agree on {@link #EXTENSION} and on "zip of root-relative files".
 */
final class Artifacts {

    /** Artifact file extension, including the dot. */
    static final String EXTENSION = ".ptfpkg";

    private Artifacts() {}

    /**
     * Unpacks {@code pkg} to a temp directory, compiles it as a project, runs
     * it, and reports — then deletes the temp directory. Returns the process
     * exit code.
     */
    static int run(Path pkg) throws IOException {
        Path workDir = Files.createTempDirectory("pontif-pkg-");
        try {
            unpack(pkg, workDir);
            return CliSupport.runAndReport(CliSupport.COMPILER.compileProjectDir(workDir));
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * Extracts every entry of {@code pkg} under {@code destDir}, rejecting any
     * entry whose normalized path would escape the destination (zip-slip).
     */
    static void unpack(Path pkg, Path destDir) throws IOException {
        Path root = destDir.toAbsolutePath().normalize();
        try (InputStream in = Files.newInputStream(pkg);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path resolved = root.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(root)) {
                    throw new IOException("Artifact entry escapes the package root: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zip, resolved);
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * Writes {@code files} (each given as a path under {@code root}) into a zip
     * at {@code out}, keyed by their root-relative, forward-slash names. Entries
     * are written in the order given; callers sort for a stable archive.
     */
    static void writeZip(Path out, Path root, List<Path> files) throws IOException {
        Path rootAbs = root.toAbsolutePath().normalize();
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out);
             ZipOutputStream zip = new ZipOutputStream(os)) {
            for (Path file : files) {
                String name = rootAbs.relativize(file.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    /** Best-effort recursive delete of a temp work directory. */
    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A leftover temp file is harmless; don't fail a successful run over it.
                }
            });
        }
    }
}

package sibarum.pontif.runtime.module;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.parser.AltParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Demand-driven module resolution for the single-file (GUI) compile path:
 * starting from one entry module, load and link <b>only the modules it
 * transitively {@code requires}</b> — never the whole directory.
 *
 * <p>This is what lets the playground run a script whose siblings are broken:
 * an unrelated {@code .ptf} that fails to parse is never opened, because only
 * required modules are full-parsed. Sibling files are located by a cheap
 * <em>module-header scan</em> ({@link ModuleHeader}) that reads just the leading
 * {@code module a.b} declaration, so even a file whose <em>body</em> is
 * malformed is still indexable by name — and only actually parsed (and able to
 * fail) if some required chain reaches it.
 *
 * <p>Contrast with {@link ModuleLoader}, the eager whole-project loader used by
 * {@code compileProjectDir}: that parses every file up front, so one bad file
 * fails the whole build. This resolver is the per-entry counterpart.
 *
 * <p>The fast paths preserve today's behavior exactly: a file with no
 * {@code requires} returns unchanged, and with no resolution directory we fall
 * back to {@link ModuleLinker#combineSingle} (builtins-only). The actual link —
 * builtin injection, coherence, import/export validation, FQN rewrite — is
 * delegated to {@link ModuleLinker#combine} once the required closure is
 * gathered, so Run and the inspector reports share one linking rule.
 */
public final class ModuleResolver {

    private ModuleResolver() {}

    /**
     * Resolve {@code entry}'s required closure from {@code resolveDir} and link.
     *
     * @param entry      the parsed entry module (e.g. the editor buffer)
     * @param resolveDir directory to resolve sibling {@code requires} from, or
     *                   {@code null} when there is none (an unsaved buffer) — in
     *                   which case only builtin requires are honored
     * @throws CompileException if a required module is missing, ambiguous, or
     *                          itself fails to parse (a required-and-broken module
     *                          is a real error; an unrelated broken one is not)
     */
    public static IrModule resolveAndCombine(IrModule entry, Path resolveDir)
            throws CompileException {
        return resolveAndCombineWithTable(entry, resolveDir).module();
    }

    /** {@link #resolveAndCombine} that also returns the {@link ModuleLinker.LinkResult}
     *  (its table is null on the bare/unlinked fast paths) for the visibility gate. */
    public static ModuleLinker.LinkResult resolveAndCombineWithTable(IrModule entry, Path resolveDir)
            throws CompileException {
        boolean hasRequires = entry.statements().stream()
                .anyMatch(s -> s instanceof IrStmt.Requires);
        if (!hasRequires) return new ModuleLinker.LinkResult(entry, null);   // bare single-file path
        if (resolveDir == null) return ModuleLinker.combineSingleWithTable(entry);  // builtins-only fallback

        Set<String> builtins = BuiltinModules.all().keySet();
        ModuleHeader.Index index = ModuleHeader.scan(resolveDir);

        Map<String, IrModule> collected = new LinkedHashMap<>();
        collected.put(entry.name(), entry);

        Deque<IrModule> work = new ArrayDeque<>();
        work.push(entry);
        while (!work.isEmpty()) {
            IrModule current = work.pop();
            for (IrStmt stmt : current.statements()) {
                if (!(stmt instanceof IrStmt.Requires r)) continue;
                String name = r.targetModule();
                // Builtins are injected by ModuleLinker.combine; the entry and
                // already-loaded modules (incl. cycles) are skipped.
                if (builtins.contains(name) || collected.containsKey(name)) continue;

                if (index.isAmbiguous(name)) {
                    throw new CompileException(
                            "required module '" + name + "' is declared by more than one file under "
                                    + resolveDir + " — module names must be unique", r.origin());
                }
                Path file = index.fileFor(name);
                if (file == null) {
                    throw new CompileException(
                            "required module '" + name + "' was not found under " + resolveDir
                                    + " (no file declares `module " + name + "`)", r.origin());
                }
                IrModule loaded = parseRequired(name, file, resolveDir, r.origin());
                collected.put(name, loaded);
                work.push(loaded);
            }
        }
        return ModuleLinker.combineWithTable(collected, entry.name());
    }

    private static IrModule parseRequired(String name, Path file, Path root, Origin requireOrigin)
            throws CompileException {
        String label = root.relativize(file).toString().replace('\\', '/');
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException io) {
            throw new CompileException(
                    "required module '" + name + "' (" + label + ") could not be read: "
                            + io.getMessage(), requireOrigin);
        }
        try {
            IrModule loaded = AltParser.parseModule(source, label);
            if (!loaded.name().equals(name)) {
                throw new CompileException(
                        "file " + label + " declares `module " + loaded.name()
                                + "` but was required as '" + name + "'", requireOrigin);
            }
            return loaded;
        } catch (CompileException ce) {
            throw ce;
        } catch (Exception e) {
            // A module that's actually required and won't parse IS an error —
            // unlike an unrelated broken file, which is never reached here.
            throw new CompileException(
                    "required module '" + name + "' (" + label + ") failed to parse: "
                            + e.getMessage(), requireOrigin);
        }
    }

    /**
     * Cheap "what module does this file declare?" index, built without fully
     * parsing any file. The module declaration leads a {@code .ptf} file (after
     * optional blank/comment lines), so reading down to the first meaningful
     * line is enough to route {@code requires} to a file — and tolerant of a
     * file whose body is malformed.
     */
    static final class ModuleHeader {

        record Index(Map<String, Path> byName, Set<String> ambiguous) {
            Path fileFor(String module) { return byName.get(module); }
            boolean isAmbiguous(String module) { return ambiguous.contains(module); }
        }

        private ModuleHeader() {}

        static Index scan(Path dir) throws CompileException {
            Map<String, Path> byName = new HashMap<>();
            Set<String> ambiguous = new HashSet<>();
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".ptf"))
                    .sorted()
                    .forEach(p -> {
                        String name = declaredModule(p);
                        if (name == null) return;               // no/unreadable header → not name-addressable
                        Path prior = byName.putIfAbsent(name, p);
                        if (prior != null) ambiguous.add(name);  // two files, same module name
                    });
            } catch (IOException io) {
                throw new CompileException(
                        "could not scan module directory " + dir + ": " + io.getMessage(),
                        Origin.NONE);
            } catch (UncheckedIOException io) {
                throw new CompileException(
                        "could not scan module directory " + dir + ": " + io.getMessage(),
                        Origin.NONE);
            }
            return new Index(byName, ambiguous);
        }

        /**
         * The dotted name from a leading {@code module a.b.c} line, or
         * {@code null} if the first meaningful line isn't a module declaration
         * (or the file can't be read). Comment ({@code #}) and blank lines are
         * skipped; the scan stops at the first content line either way.
         */
        private static String declaredModule(Path file) {
            try {
                for (String raw : Files.readAllLines(file)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.equals("module") || line.startsWith("module ") || line.startsWith("module\t")) {
                        String rest = line.substring("module".length()).trim();
                        int cut = rest.length();
                        for (int i = 0; i < rest.length(); i++) {
                            char c = rest.charAt(i);
                            if (Character.isWhitespace(c) || c == '#') { cut = i; break; }
                        }
                        String name = rest.substring(0, cut);
                        return name.isEmpty() ? null : name;
                    }
                    return null;  // first content line isn't a module decl → anonymous
                }
            } catch (IOException io) {
                // Unreadable file: not indexable. Only an error if it's required,
                // surfaced then as "not found".
            }
            return null;
        }
    }
}

package sibarum.pontif.runtime.module;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.InferenceContext;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.ir.NarrowingInference;
import sibarum.pontif.parser.PontifParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
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
        return resolveAndCombine(entry, resolveDir, null);
    }

    /**
     * As {@link #resolveAndCombine(IrModule, Path)}, but {@code entryLabel} is the
     * {@code resolveDir}-relative path the {@code entry} buffer was parsed from (its
     * {@code sourceName}), or {@code null} if unsaved / unknown. The file it names is
     * excluded when folding the entry's same-namespace siblings — otherwise the entry's
     * own on-disk copy would be merged in twice (buffer + disk). Passing it is what lets
     * the live buffer be the authoritative version of its own file.
     */
    public static IrModule resolveAndCombine(IrModule entry, Path resolveDir, String entryLabel)
            throws CompileException {
        // The buffer's own on-disk file, resolved from its label so folding can exclude it.
        Path entryFile = (resolveDir != null && entryLabel != null)
                ? resolveDir.resolve(entryLabel) : null;
        // Fold same-namespace sibling files into the entry unit FIRST — intra-namespace
        // names must be visible with no `requires`, and this has to happen before the
        // needsLinking short-circuit (a library-style entry with no requires would else
        // return bare, never seeing its siblings). With no resolveDir there are no
        // siblings to fold, so the buffer stands alone.
        ModuleHeader.Index index = resolveDir == null ? null : ModuleHeader.scan(resolveDir);
        IrModule unit = index == null ? entry
                : foldNamespace(entry, index, resolveDir, entryFile);
        // A genuine multi-file fold produces a NEW module (NamespaceAssembler returns the
        // sole part unchanged otherwise), so identity inequality means siblings were merged.
        boolean folded = unit != entry;

        // The link-vs-bare decision is ModuleLinker.needsLinking's to make — the single source of
        // truth both this gate and combineSingle share, so they cannot drift (the bug where a
        // spawn-only program skipped seating because this gate still only checked `requires`).
        if (!ModuleLinker.needsLinking(unit)) {
            // A folded namespace still needs the link pipeline even with no `requires`:
            // the buffer's references to a sibling-defined struct were parsed as bare calls
            // (it couldn't see the sibling), and only the link's StructLiteralRewriter /
            // DestructureResolver / method resolution turn them into constructions. A truly
            // single-file unit stays on the bare path, byte-for-byte as before.
            return folded ? ModuleLinker.combine(Map.of(unit.name(), unit), unit.name()) : unit;
        }
        if (resolveDir == null) return ModuleLinker.combineSingle(unit);  // builtins-only fallback

        Set<String> builtins = BuiltinModules.all().keySet();

        Map<String, IrModule> collected = new LinkedHashMap<>();
        collected.put(unit.name(), unit);
        IrModule entryUnit = unit;

        Deque<IrModule> work = new ArrayDeque<>();
        work.push(entryUnit);
        while (!work.isEmpty()) {
            IrModule current = work.pop();
            for (IrStmt stmt : current.statements()) {
                if (!(stmt instanceof IrStmt.Requires r)) continue;
                String name = r.targetModule();
                // Builtins are injected by ModuleLinker.combine; the entry and
                // already-loaded modules (incl. cycles) are skipped.
                if (builtins.contains(name) || collected.containsKey(name)) continue;

                // Data require: `requires $a.b.c` resolves the data file
                // `$a.b.c.ptf` by LITERAL filename (a data file is a bare object
                // literal with no `module` header, so it is not in the header
                // index). Its terminal value is wrapped as a synthetic module
                // exporting one 0-arg constant — see loadDataModule. Data files
                // are pure literals: they `require` nothing, so no closure walk.
                if (name.startsWith("$")) {
                    IrModule dataMod = loadDataModule(name, resolveDir, r.origin());
                    collected.put(name, dataMod);
                    continue;
                }

                // A required namespace may itself span several files — load and merge
                // them all (NamespaceAssembler), the same rule the entry namespace uses.
                List<Path> files = index.filesFor(name);
                if (files.isEmpty()) {
                    throw new CompileException(
                            "required module '" + name + "' was not found under " + resolveDir
                                    + " (no file declares `module " + name + "`)", r.origin());
                }
                List<IrModule> parts = new ArrayList<>(files.size());
                for (Path file : files) {
                    parts.add(parseRequired(name, file, resolveDir, r.origin()));
                }
                IrModule loaded = NamespaceAssembler.merge(name, parts);
                collected.put(name, loaded);
                work.push(loaded);
            }
        }
        return ModuleLinker.combine(collected, entryUnit.name());
    }

    /**
     * Fold the {@code entry} buffer together with every sibling file that declares
     * the same namespace (excluding {@code entryFile}, the buffer's own on-disk
     * copy) into one module. The buffer comes first so it wins for its own file.
     */
    private static IrModule foldNamespace(
            IrModule entry, ModuleHeader.Index index, Path resolveDir, Path entryFile)
            throws CompileException {
        List<Path> siblings = index.filesFor(entry.name());
        if (siblings.isEmpty()) return entry;                 // nothing on disk to fold
        List<IrModule> parts = new ArrayList<>();
        parts.add(entry);                                     // live buffer is authoritative
        for (Path file : siblings) {
            if (sameFile(file, entryFile)) continue;          // skip the buffer's own disk copy
            parts.add(parseRequired(entry.name(), file, resolveDir, Origin.NONE));
        }
        return NamespaceAssembler.merge(entry.name(), parts);
    }

    /** Whether two paths denote the same file, tolerating non-normalized forms; false if either is null. */
    private static boolean sameFile(Path a, Path b) {
        if (a == null || b == null) return false;
        try {
            if (Files.isSameFile(a, b)) return true;
        } catch (IOException ignored) {
            // fall through to a normalized-path comparison
        }
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
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
            IrModule loaded = PontifParser.parseModule(source, label);
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
     * Loads a data file required as {@code requires $a.b.c} and wraps its
     * terminal value as a synthetic single-export module.
     *
     * <p>A data file is <em>just an object literal</em> — no {@code module}
     * header, no declarations — so it parses to an {@link IrModule} with empty
     * {@code statements} and a {@link IrExpr.Record} {@code main}. We turn that
     * value into a 0-arg {@code let}-style {@link IrStmt.FunctionDecl} named for
     * the FQN's last segment, whose return sort is the value's structural
     * <em>effective sort</em> ({@link NarrowingInference#inferFloor}) — the
     * compile-time-synthesized type that makes all downstream field access
     * typesafe. The synthetic module exports that one name, so the ordinary
     * import/link/name-resolution pipeline binds it with no special casing.
     *
     * @param target the required name including the leading {@code $}
     */
    private static IrModule loadDataModule(String target, Path resolveDir, Origin requireOrigin)
            throws CompileException {
        String fileName = target + ".ptf";                  // literal: keeps `$` and dots
        Path file = resolveDir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            throw new CompileException(
                    "required data file '" + fileName + "' was not found under " + resolveDir,
                    requireOrigin);
        }
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException io) {
            throw new CompileException(
                    "required data file '" + fileName + "' could not be read: " + io.getMessage(),
                    requireOrigin);
        }
        IrModule loaded;
        try {
            loaded = PontifParser.parseModule(source, fileName);
        } catch (Exception e) {
            throw new CompileException(
                    "required data file '" + fileName + "' failed to parse: " + e.getMessage(),
                    requireOrigin);
        }
        // A data file must be a single object literal: no top-level declarations
        // (requires / exports / function / struct / …) and a record terminal
        // value. This is the "leaf / substitutable-by-value" invariant at its
        // simplest — the file contributes one value and nothing else.
        if (!loaded.statements().isEmpty() || !(loaded.main() instanceof IrExpr.Record)) {
            throw new CompileException(
                    "data file '" + fileName + "' must be a single object literal "
                            + "(no declarations, and its content must be a `{…}` record)",
                    requireOrigin);
        }
        IrExpr value = loaded.main();
        // Bind under the last FQN segment WITHOUT the leading `$` — for a single-segment data require
        // (`requires $mathstyle`) there is no dot, so stripping the `$` first is what yields the bare
        // local name `mathstyle` (matching the parser's RequireEntry) rather than `$mathstyle`.
        String bare = target.startsWith("$") ? target.substring(1) : target;
        String localName = bare.substring(bare.lastIndexOf('.') + 1);  // last FQN segment
        IrSort sort = NarrowingInference.inferFloor(value, InferenceContext.empty());
        IrStmt.FunctionDecl constant = new IrStmt.FunctionDecl(
                localName, List.of(), sort, value, requireOrigin, /*topLevelLet*/ true);
        IrStmt exports = IrStmt.exports(List.of(localName), /*self*/ true);
        return new IrModule(target, List.of(exports, constant), new IrExpr.Lit(0, requireOrigin));
    }

    /**
     * Cheap "what module does this file declare?" index, built without fully
     * parsing any file. The module declaration leads a {@code .ptf} file (after
     * optional blank/comment lines), so reading down to the first meaningful
     * line is enough to route {@code requires} to a file — and tolerant of a
     * file whose body is malformed.
     */
    static final class ModuleHeader {

        record Index(Map<String, List<Path>> byName) {
            /** Every file declaring {@code module}, in sorted-path order (empty if none). */
            List<Path> filesFor(String module) { return byName.getOrDefault(module, List.of()); }
        }

        private ModuleHeader() {}

        static Index scan(Path dir) throws CompileException {
            // name → all files declaring it. A namespace legitimately spans several files
            // (they merge — see NamespaceAssembler), so same-name files accumulate rather
            // than collide; sorted-path insertion keeps the merge order stable.
            Map<String, List<Path>> byName = new LinkedHashMap<>();
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".ptf"))
                    .sorted()
                    .forEach(p -> {
                        String name = declaredModule(p);
                        if (name == null) return;               // no/unreadable header → not name-addressable
                        byName.computeIfAbsent(name, k -> new ArrayList<>()).add(p);
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
            return new Index(byName);
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

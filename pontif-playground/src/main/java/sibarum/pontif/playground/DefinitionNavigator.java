package sibarum.pontif.playground;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSourcePrinter;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.parser.ParseException;
import sibarum.pontif.parser.PontifParser;
import sibarum.pontif.runtime.module.BuiltinModules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * "Go to definition" for the Pontif Editor: resolve a clicked identifier (a
 * type / function / method / let name) to the SOURCE of the module that
 * declares it, plus the character span of the name within that source so the
 * caller can highlight it.
 *
 * <p>Resolution is deliberately a tolerant search rather than the full
 * link-time {@code ModuleSymbolTable} machinery — a Ctrl+click is an explicit,
 * one-off action, and erring toward "find it somewhere" beats refusing on a
 * half-finished import. Precedence, first match wins:
 * <ol>
 *   <li><b>This file</b> — the editor buffer's own declarations (shown verbatim).</li>
 *   <li><b>Sibling modules</b> — {@code .ptf} files in the open file's directory,
 *       shown as their real on-disk source.</li>
 *   <li><b>Builtins</b> — {@link BuiltinModules#all()}; these have no shipped
 *       {@code .ptf}, so the {@link IrPrinter} dump of the module stands in.</li>
 * </ol>
 *
 * <p>Names match a declaration when they equal it OR are its last dotted
 * segment (so clicking {@code zero} finds {@code let Traction.zero}, and
 * clicking {@code inv} finds {@code method Ternion.inv}). Primitives carry no
 * source and are reported separately ({@link #isPrimitive}).
 */
public final class DefinitionNavigator {

    /** A located definition: a module label, the source to show, and the name's span in it. */
    public record Target(String moduleLabel, String sourceText, int selStart, int selEnd) {}

    /** Scalar/structural builtins with no Pontif source to open. */
    private static final Set<String> PRIMITIVES =
            Set.of("Int", "Bool", "Decimal", "Frac", "Char", "String", "_");

    private DefinitionNavigator() {}

    public static boolean isPrimitive(String name) {
        return PRIMITIVES.contains(name);
    }

    /**
     * True when {@code name} is usable in the editor buffer <em>without</em> a new
     * import — declared locally, or already brought in by an existing {@code requires}.
     * The navigate-vs-import switch: in scope ⇒ go to definition; not ⇒ offer the import.
     */
    public static boolean inScope(String editorContent, String name) {
        IrModule mod = tryParse(editorContent, "<editor>");
        if (mod == null) return false;
        if (declares(mod, name)) return true;
        for (IrStmt s : mod.statements()) {
            if (s instanceof IrStmt.Requires r) {
                for (IrStmt.RequireEntry e : r.entries()) {
                    if (e.localName().equals(name)) return true;
                }
            }
        }
        return false;
    }

    /**
     * The character span {@code [start, end)} of {@code name}'s declaration <em>in the
     * editor buffer itself</em>, or empty when the buffer doesn't declare it. The
     * "jump within the editor" case for go-to-definition: a locally-defined name should
     * move the caret in the editor rather than open a read-only copy in the Definition
     * view. Mirrors {@link #resolve}'s step 1 (this file), returning just the span.
     */
    public static Optional<int[]> localDeclaration(String editorContent, String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        IrModule mod = tryParse(editorContent, "<editor>");
        if (mod == null || !declares(mod, name)) return Optional.empty();
        Target t = located("(this file)", editorContent, name, mod);
        return t.selEnd() > t.selStart() ? Optional.of(new int[]{t.selStart(), t.selEnd()}) : Optional.empty();
    }

    /**
     * Module names that <em>export</em> {@code name} — the candidates a {@code requires}
     * could pull it from. Searches sibling {@code .ptf} modules (by their declared
     * {@code module} name) and the builtins (+ the GUI extension). Only exported names
     * qualify: importing a non-exported name would be a link error.
     */
    public static List<String> exporters(String editorContent, String name, Path resolveDir) {
        List<String> out = new ArrayList<>();
        if (resolveDir != null && Files.isDirectory(resolveDir)) {
            for (Map.Entry<String, String> e : siblingSources(resolveDir).entrySet()) {
                IrModule m = tryParse(e.getValue(), e.getKey());
                if (m != null && !"_anonymous".equals(m.name())
                        && exportsName(m, name) && !out.contains(m.name())) {
                    out.add(m.name());
                }
            }
        }
        for (Map.Entry<String, Builtin> e : candidateBuiltins().entrySet()) {
            Builtin b = e.getValue();
            if (b != null && b.module() != null && exportsName(b.module(), name)
                    && !out.contains(e.getKey())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Result of {@link #insertRequires}: the edited text plus where it changed (for
     *  caret tracking), or {@code changed=false} with a reason when nothing was done. */
    public record RequiresEdit(String text, int editOffset, int delta, boolean changed, String message) {}

    /**
     * Add {@code name} to a {@code requires <module>.{…}} in {@code content}, merging into
     * the module's existing line if present, else inserting a fresh line after the last
     * {@code requires} (or the {@code module} header, or the top). Pure: returns the new
     * text and the edit position; the caller applies it and moves the caret. Line-based on
     * the single-line {@code requires} form (the norm). A no-op (with a message) when the
     * name is already imported from that module.
     */
    public static RequiresEdit insertRequires(String content, String module, String name) {
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(content.split("\n", -1)));
        int mergeLine = -1, lastRequires = -1, moduleLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).strip();
            if (t.startsWith("requires ")) {
                lastRequires = i;
                String rest = t.substring("requires ".length()).strip();
                int db = rest.indexOf(".{");
                if (db > 0 && rest.substring(0, db).equals(module)) mergeLine = i;
            } else if (t.startsWith("module ")) {
                moduleLine = i;
            }
        }

        if (mergeLine >= 0) {
            String line = lines.get(mergeLine);
            int open = line.indexOf('{', line.indexOf(".{"));
            int close = line.lastIndexOf('}');
            if (open < 0 || close < 0 || close < open) {
                return new RequiresEdit(content, 0, 0, false,
                        "Couldn't merge into the existing requires for " + module + ".");
            }
            List<String> entries = new ArrayList<>();
            String inner = line.substring(open + 1, close).strip();
            if (!inner.isEmpty()) for (String p : inner.split(",")) entries.add(p.strip());
            for (String e : entries) {
                String local = e.contains("->") ? e.substring(e.indexOf("->") + 2).strip() : e;
                if (local.equals(name)) {
                    return new RequiresEdit(content, 0, 0, false,
                            "'" + name + "' is already imported from " + module + ".");
                }
            }
            entries.add(name);
            String indent = line.substring(0, line.length() - line.stripLeading().length());
            String rebuilt = indent + "requires " + module + ".{" + String.join(", ", entries) + "}";
            int editOffset = lineStartOffset(lines, mergeLine);
            int delta = rebuilt.length() - line.length();
            lines.set(mergeLine, rebuilt);
            return new RequiresEdit(String.join("\n", lines), editOffset, delta, true,
                    "Added " + name + " to requires " + module + ".{…}");
        }

        String insert = "requires " + module + ".{" + name + "}";
        int at = lastRequires >= 0 ? lastRequires + 1 : (moduleLine >= 0 ? moduleLine + 1 : 0);
        int editOffset = lineStartOffset(lines, at);
        lines.add(at, insert);
        return new RequiresEdit(String.join("\n", lines), editOffset, insert.length() + 1, true,
                "Added requires " + module + ".{" + name + "}");
    }

    /** Char offset of {@code lineIdx}'s start in a newline-joined {@code lines} list. */
    private static int lineStartOffset(List<String> lines, int lineIdx) {
        int off = 0;
        for (int i = 0; i < lineIdx && i < lines.size(); i++) off += lines.get(i).length() + 1;
        return off;
    }

    private static boolean exportsName(IrModule m, String name) {
        for (IrStmt s : m.statements()) {
            if (s instanceof IrStmt.Exports ex && ex.names().contains(name)) return true;
        }
        return false;
    }

    /** A module and the names it exports — one row group in the module explorer.
     *  {@code builtin} separates the shipped modules from this project's siblings. */
    public record ModuleExports(String module, List<String> symbols, boolean builtin) {}

    /**
     * Every importable module with its exported names — sibling project modules first
     * (alphabetical by file), then the builtins (+ the GUI extension). The browse-side
     * complement of {@link #exporters}: for "I don't know the name, show me what's there."
     * Modules that export nothing are omitted.
     */
    public static List<ModuleExports> allModules(String editorContent, Path resolveDir) {
        List<ModuleExports> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (resolveDir != null && Files.isDirectory(resolveDir)) {
            for (Map.Entry<String, String> e : siblingSources(resolveDir).entrySet()) {
                IrModule m = tryParse(e.getValue(), e.getKey());
                if (m == null || "_anonymous".equals(m.name()) || !seen.add(m.name())) continue;
                List<String> syms = exportedNames(m);
                if (!syms.isEmpty()) out.add(new ModuleExports(m.name(), syms, false));
            }
        }
        for (Map.Entry<String, Builtin> e : candidateBuiltins().entrySet()) {
            Builtin b = e.getValue();
            if (b == null || b.module() == null || !seen.add(e.getKey())) continue;
            List<String> syms = exportedNames(b.module());
            if (!syms.isEmpty()) out.add(new ModuleExports(e.getKey(), syms, true));
        }
        return out;
    }

    private static List<String> exportedNames(IrModule m) {
        List<String> out = new ArrayList<>();
        for (IrStmt s : m.statements()) {
            if (s instanceof IrStmt.Exports ex) {
                for (String n : ex.names()) if (!out.contains(n)) out.add(n);
            }
        }
        return out;
    }

    /** Every whole-word occurrence of {@code name} in {@code text}, as {@code [start, end)}
     *  ranges — the references to highlight in the opened definition view. */
    public static List<int[]> references(String text, String name) {
        List<int[]> out = new ArrayList<>();
        if (name == null || name.isEmpty()) return out;
        int i = 0;
        while (true) {
            int hit = wholeWordIndex(text, name, i);
            if (hit < 0) break;
            out.add(new int[]{hit, hit + name.length()});
            i = hit + name.length();
        }
        return out;
    }

    /**
     * Resolve {@code name} to its defining source. {@code editorContent} is the
     * live editor buffer; {@code resolveDir} is the open file's directory (null
     * for an unsaved buffer — sibling search is then skipped). Never throws.
     */
    public static Optional<Target> resolve(String editorContent, String name, Path resolveDir) {
        if (name == null || name.isEmpty()) return Optional.empty();

        // 1. This file.
        IrModule editorMod = tryParse(editorContent, "<editor>");
        if (editorMod != null && declares(editorMod, name)) {
            return Optional.of(located("(this file)", editorContent, name, editorMod));
        }

        // 2. Sibling .ptf files (deterministic by filename).
        if (resolveDir != null && Files.isDirectory(resolveDir)) {
            for (Map.Entry<String, String> e : siblingSources(resolveDir).entrySet()) {
                IrModule mod = tryParse(e.getValue(), e.getKey());
                if (mod != null && declares(mod, name)) {
                    return Optional.of(located(e.getKey(), e.getValue(), name, mod));
                }
            }
        }

        // 3. Builtins. Source-authored ones (pontif.core, extensions) show their real
        //    source; the IR-built std.* modules are reflected back to Pontif source.
        for (Map.Entry<String, Builtin> e : candidateBuiltins().entrySet()) {
            Builtin b = e.getValue();
            if (b == null || b.module() == null || !declares(b.module(), name)) continue;
            String label = "builtin " + e.getKey();
            if (b.source() != null) {
                // Real source: its declarations' origins map into it — locate like a sibling.
                return Optional.of(located(label, b.source(), name, b.module()));
            }
            // No shipped source: reflect the IR back to Pontif, then re-parse the
            // reflection so the name's span is found via real origins in that text.
            String reflected = IrSourcePrinter.print(b.module());
            IrModule reMod = tryParse(reflected, e.getKey());
            return Optional.of(reMod != null
                    ? located(label, reflected, name, reMod)
                    : new Target(label, reflected,
                            span(wholeWordIndex(reflected, name, 0), name)[0],
                            span(wholeWordIndex(reflected, name, 0), name)[1]));
        }

        return Optional.empty();
    }

    /** A builtin candidate: its module, and its real Pontif source if one exists
     *  ({@code null} when the module is built from IR and must be reflected). */
    private record Builtin(IrModule module, String source) {}

    /**
     * Builtin modules to search: the default-registered set ({@link BuiltinModules#all()},
     * with {@link BuiltinModules#sourceOf real source} where it exists) plus the GUI
     * extension's module, parsed locally for <em>lookup only</em>. The editor runs GUI
     * programs in a subprocess, so it never installs the GUI extension globally — parsing
     * its source here keeps {@code pontif.gui} names navigable without changing the
     * in-process Run path.
     */
    private static Map<String, Builtin> candidateBuiltins() {
        Map<String, Builtin> out = new LinkedHashMap<>();
        for (Map.Entry<String, IrModule> e : BuiltinModules.all().entrySet()) {
            out.put(e.getKey(), new Builtin(e.getValue(), BuiltinModules.sourceOf(e.getKey())));
        }
        out.computeIfAbsent("pontif.gui", k -> {
            // Through OptionalGui: the windowed extension is optional (and outlived by this editor), so its
            // absence costs one navigable module rather than a NoClassDefFoundError on startup. The parse is
            // caught separately and narrowly — a module that is present but does not parse is a different
            // fact from one that is not here, and the old catch-all could not tell them apart.
            String src = OptionalGui.moduleSource();
            if (src == null) {
                return null;
            }
            try {
                return new Builtin(PontifParser.parseModule(src, "pontif.gui"), src);
            } catch (ParseException e) {
                return null;
            }
        });
        return out;
    }

    // --- declaration search -------------------------------------------------

    /** True when {@code module} declares a function / type / method named {@code name}. */
    private static boolean declares(IrModule module, String name) {
        return declOrigin(module, name) != null;
    }

    /**
     * Origin of the declaration of {@code name} in {@code module}, or null if it
     * declares no such name. Functions, top-level lets, type/struct/trait aliases,
     * and trait-impl methods are all candidates.
     */
    private static Origin declOrigin(IrModule module, String name) {
        for (IrStmt s : module.statements()) {
            switch (s) {
                case IrStmt.FunctionDecl fd -> { if (nameMatches(fd.name(), name)) return fd.origin(); }
                case IrStmt.TypeAlias ta -> { if (nameMatches(ta.name(), name)) return ta.origin(); }
                case IrStmt.TraitImpl ti -> {
                    for (IrStmt.FunctionDecl m : ti.methods()) {
                        if (nameMatches(m.name(), name)) return m.origin();
                    }
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                        if (nameMatches(a.name(), name)) return a.origin();
                    }
                }
                default -> { /* Coercion / Proof / Requires / Exports / NoOp declare no clickable name */ }
            }
        }
        return null;
    }

    /** A clicked name matches a declaration name verbatim or as its last dotted segment. */
    private static boolean nameMatches(String declName, String clicked) {
        return declName.equals(clicked) || declName.endsWith("." + clicked);
    }

    /** Build a Target for a source whose declarations carry real origins (editor / sibling). */
    private static Target located(String label, String source, String name, IrModule mod) {
        Origin o = declOrigin(mod, name);
        int from = (o != null && o.isPresent())
                ? offsetOf(o.span().start(), source)
                : 0;
        int start = wholeWordIndex(source, name, from);
        if (start < 0) start = wholeWordIndex(source, name, 0);  // origin missed — scan from top
        int[] span = span(start, name);
        return new Target(label, source, span[0], span[1]);
    }

    private static int[] span(int start, String name) {
        if (start < 0) return new int[]{0, 0};  // not found — show source, no highlight
        return new int[]{start, start + name.length()};
    }

    // --- sibling sources ----------------------------------------------------

    /** {@code .ptf} files in {@code dir}, name → contents; unreadable files skipped. */
    private static Map<String, String> siblingSources(Path dir) {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".ptf"))
                 .forEach(p -> {
                     try {
                         out.put(p.getFileName().toString(),
                                 Files.readString(p, StandardCharsets.UTF_8));
                     } catch (IOException ignored) {
                         // unreadable sibling — skip it
                     }
                 });
        } catch (IOException ignored) {
            // directory not listable — no siblings
        }
        return out;
    }

    // --- text helpers (identifier-boundary aware; mirror PontifHighlighter rules) ---

    private static IrModule tryParse(String source, String name) {
        try {
            return PontifParser.parseModule(source, name);
        } catch (Exception e) {
            return null;
        }
    }

    /** First whole-word occurrence of {@code word} at or after {@code from}, or -1. */
    private static int wholeWordIndex(String text, String word, int from) {
        if (word.isEmpty()) return -1;
        int i = Math.max(0, from);
        while (true) {
            int hit = text.indexOf(word, i);
            if (hit < 0) return -1;
            boolean leftOk = hit == 0 || !isIdentPart(text.charAt(hit - 1));
            int after = hit + word.length();
            boolean rightOk = after >= text.length() || !isIdentPart(text.charAt(after));
            if (leftOk && rightOk) return hit;
            i = hit + 1;
        }
    }

    /** Flat char offset of a 1-indexed (line, column) position in {@code source}. */
    private static int offsetOf(Origin.Position p, String source) {
        int line = 1, offset = 0;
        while (line < p.line() && offset < source.length()) {
            int nl = source.indexOf('\n', offset);
            if (nl < 0) return source.length();
            offset = nl + 1;
            line++;
        }
        return Math.min(source.length(), offset + p.column() - 1);
    }

    private static boolean isIdentPart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '$';
    }
}

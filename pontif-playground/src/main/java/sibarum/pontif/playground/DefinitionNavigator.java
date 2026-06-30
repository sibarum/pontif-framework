package sibarum.pontif.playground;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSourcePrinter;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.parser.AltParser;
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
            try {
                String src = new sibarum.pontif.gui.GuiExtension().pontifSource();
                return new Builtin(AltParser.parseModule(src, "pontif.gui"), src);
            } catch (Exception e) {
                return null;  // GUI module unavailable — skip it
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

    // --- text helpers (identifier-boundary aware; mirror AltHighlighter rules) ---

    private static IrModule tryParse(String source, String name) {
        try {
            return AltParser.parseModule(source, name);
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

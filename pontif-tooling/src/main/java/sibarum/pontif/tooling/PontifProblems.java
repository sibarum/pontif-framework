package sibarum.pontif.tooling;

import sibarum.pontif.core.Origin;
import sibarum.pontif.runtime.PontifCompiler;
import sibarum.pontif.runtime.RunResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where an editor marks a failed compile: the compiler's error, mapped onto the buffer it was compiled from as
 * half-open character ranges. Any editor that checks Pontif as it is typed (the Pontif Editor, the VexelRay IDE)
 * asks this, so they agree on where an error goes. Toolkit-free and Truffle-free.
 *
 * <p>The precedence, in order:
 * <ol>
 *   <li>an error whose origin points into <b>this buffer</b> marks the offending token, even a "Link error" for an
 *       unresolved name used here (its origin is the use site, not an import problem). A call error is anchored by
 *       the compiler at the application ({@code (args)}), so it is pulled left onto the name being called;</li>
 *   <li>otherwise a link error, or an origin in another module's source, marks the {@code requires} statement it
 *       came through (or every {@code requires}, when no single one can be pinned);</li>
 *   <li>otherwise an empty range at the end of the text: nothing to underline, but a failed compile always has
 *       a problem, so its message still reaches the person.</li>
 * </ol>
 *
 * <p>An error at the very end of the text (an unexpected end of file) is marked on the last thing typed.
 *
 * <p>Offsets count {@code \n} as the line break, as the compiler's lines do.
 */
public final class PontifProblems {

    /**
     * One error to surface: {@code [start, end)} in the buffer and the compiler's message. {@code fromImport} says
     * the failure came from an imported module, so the range is the {@code requires} statement, not the place in the
     * other module.
     */
    public record Problem(int start, int end, String message, boolean fromImport) {
    }

    private PontifProblems() {
    }

    /**
     * Compile {@code content} as the file {@code sourceName} would be, resolving sibling {@code requires} from
     * {@code resolveDir} (null for builtins only), and say where it fails: empty when it compiles.
     */
    public static List<Problem> check(PontifCompiler compiler, String content, String sourceName, Path resolveDir) {
        if (compiler.compile(content, sourceName, resolveDir) instanceof PontifCompiler.CompileResult.Failed failed) {
            return of(content, sourceName, failed.error());
        }
        return List.of();
    }

    /** Where {@code error}, from compiling {@code content} as {@code sourceName}, is marked. */
    public static List<Problem> of(String content, String sourceName, RunResult error) {
        String message = error.text();
        Optional<Origin> originOpt = error.origin();
        if (originOpt.isPresent() && originOpt.get().isPresent() && sourceName.equals(originOpt.get().source())) {
            int[] span = spanOffsets(content, originOpt.get());
            if (span != null) {
                span = callNameSpan(content, span);
                return List.of(new Problem(span[0], span[1], message, false));
            }
        }
        if (message.startsWith("Link error")
                || (originOpt.isPresent() && originOpt.get().isPresent()
                    && !sourceName.equals(originOpt.get().source()))) {
            List<Problem> requires = requiresMarks(content, message, originOpt.orElse(null));
            if (!requires.isEmpty()) {
                return requires;
            }
        }
        return List.of(new Problem(content.length(), content.length(), message, false));
    }

    /** When {@code [start, end)} begins right after an identifier, that identifier's span instead. */
    private static int[] callNameSpan(String content, int[] span) {
        int start = span[0];
        if (start <= 0 || !isIdentChar(content.charAt(start - 1))) {
            return span;
        }
        int nameStart = start;
        while (nameStart > 0 && isIdentChar(content.charAt(nameStart - 1))) {
            nameStart--;
        }
        return new int[] {nameStart, start};
    }

    /**
     * Character offsets {@code [start, end)} for an origin, clamped to its first line. A point is widened over the
     * token that begins there. Null if it does not map into {@code content}.
     */
    private static int[] spanOffsets(String content, Origin o) {
        int start = offsetOf(content, o.span().start().line(), o.span().start().column());
        if (start < 0) {
            return null;
        }
        if (start >= content.length() || content.substring(start).isBlank()) {
            // At the end of the text ("unexpected end of file"): the last thing typed is what is unfinished.
            int last = content.stripTrailing().length() - 1;
            return last < 0 ? null : new int[] {last, last + 1};
        }
        int end = offsetOf(content, o.span().end().line(), o.span().end().column());
        if (end <= start) {
            end = wordEnd(content, start);
        }
        int lineEnd = content.indexOf('\n', start);
        if (lineEnd < 0) {
            lineEnd = content.length();
        }
        end = Math.min(end, lineEnd);
        if (end <= start) {
            end = Math.min(start + 1, content.length());
        }
        return start >= end ? null : new int[] {start, end};
    }

    /** The offset of 1-based {@code (line, column)}, or -1 past the last line; the column is clamped. */
    private static int offsetOf(String content, int line, int column) {
        int idx = 0;
        int ln = 1;
        while (ln < line && idx < content.length()) {
            if (content.charAt(idx) == '\n') {
                ln++;
            }
            idx++;
        }
        if (ln != line) {
            return -1;
        }
        return Math.min(idx + (column - 1), content.length());
    }

    /** The end of the identifier starting at {@code start}, at least one character on. */
    private static int wordEnd(String content, int start) {
        int i = start;
        while (i < content.length() && isIdentChar(content.charAt(i))) {
            i++;
        }
        return i > start ? i : Math.min(start + 1, content.length());
    }

    private static List<Problem> requiresMarks(String content, String message, Origin origin) {
        String foreign = origin != null && origin.isPresent() ? stripExt(origin.source()) : null;
        List<Problem> all = new ArrayList<>();
        List<Problem> matched = new ArrayList<>();
        int offset = 0;
        for (String line : content.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("requires ")) {
                int lead = line.length() - line.stripLeading().length();
                int start = offset + lead;
                int end = offset + line.stripTrailing().length();
                if (end > start) {
                    Problem mark = new Problem(start, end, message, true);
                    all.add(mark);
                    String module = requiresModule(t);
                    if (module != null && (message.contains(module) || (foreign != null
                            && (foreign.equals(module) || foreign.contains(module) || module.contains(foreign))))) {
                        matched.add(mark);
                    }
                }
            }
            offset += line.length() + 1;
        }
        return List.copyOf(matched.isEmpty() ? all : matched);
    }

    private static String requiresModule(String strippedLine) {
        String rest = strippedLine.substring("requires ".length()).strip();
        int db = rest.indexOf(".{");
        if (db > 0) {
            return rest.substring(0, db).strip();
        }
        int sp = rest.indexOf(' ');
        return sp > 0 ? rest.substring(0, sp).strip() : rest;
    }

    private static String stripExt(String source) {
        if (source == null) {
            return null;
        }
        int dot = source.lastIndexOf('.');
        return dot > 0 ? source.substring(0, dot) : source;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}

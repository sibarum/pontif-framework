package sibarum.pontif.runtime;

import sibarum.pontif.ir.AliasResolver;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSourceReflector;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.ir.MethodOperatorResolver;
import sibarum.pontif.parser.PontifParser;
import sibarum.pontif.parser.ParseException;
import sibarum.pontif.runtime.module.ModuleResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces the "reflected source" debug view: the program re-emitted in
 * source-shaped text with declared sorts replaced by the inferred narrowings
 * {@link IrSourceReflector} derives from the one inference engine. A window into
 * what the compiler knows — the same family of plain-text artifact as the
 * receipt-graph and conservation reports (per the project preference for review-
 * able text over visualization).
 *
 * <p>Pipeline mirrors the Run path so the view agrees with what compiles: alt
 * source → {@link PontifParser} → {@link ModuleResolver} (link) →
 * {@link MethodOperatorResolver} (resolve methods + route operators) →
 * {@link AliasResolver} → {@link IrSourceReflector}. The entrypoint is variable
 * (a function/let name, or {@code main} when none is given); only the functions
 * reachable from it are emitted.
 */
public final class ReflectionReport {

    private ReflectionReport() {}

    /** Outcome of report generation: the text, or a parse/compile error. */
    public sealed interface Result permits Result.Generated, Result.Failed {
        record Generated(String text) implements Result {}
        record Failed(String error) implements Result {}
    }

    /** Reflects at {@code main}, single-file (no sibling {@code requires} resolution). */
    public static Result fromPontifSource(String source, String sourceName) {
        return fromPontifSource(source, sourceName, null, null);
    }

    /**
     * Reflects {@code source}, resolving sibling {@code requires} from
     * {@code resolveDir}, rooted at {@code entryName} (a function/let name, or
     * {@code null} for {@code main}). Never throws — failures come back as
     * {@link Result.Failed}.
     */
    public static Result fromPontifSource(
            String source, String sourceName, java.nio.file.Path resolveDir, String entryName) {
        IrModule parsed;
        try {
            parsed = PontifParser.parseModule(source, sourceName);
        } catch (ParseException pe) {
            return new Result.Failed("Parse error: " + pe.getMessage());
        } catch (RuntimeException e) {
            return new Result.Failed("Parse error: " + e.getMessage());
        }
        try {
            IrModule linked = ModuleResolver.resolveAndCombine(parsed, resolveDir, sourceName);
            // Resolve methods + route operators (the Run path), so the reflected
            // calls are the resolved dispatch calls and inference sees real targets.
            IrModule resolved = AliasResolver.resolve(MethodOperatorResolver.resolve(linked));
            return new Result.Generated(IrSourceReflector.reflect(resolved, entryName));
        } catch (CompileException ce) {
            return new Result.Failed("Compile error: " + ce.getMessage());
        } catch (RuntimeException e) {
            return new Result.Failed("Compile error: " + e.getMessage());
        }
    }

    /**
     * The selectable entrypoints for {@code source}: {@code "main"} plus each
     * top-level function / let name (best-effort, from a parse alone — operator
     * symbols are omitted as they read poorly in a menu). Parse failures degrade
     * to just {@code "main"}, so a selector can always offer at least the default.
     */
    public static List<String> entrypoints(String source, String sourceName) {
        List<String> names = new ArrayList<>();
        names.add("main");
        try {
            IrModule parsed = PontifParser.parseModule(source, sourceName);
            for (IrStmt stmt : parsed.statements()) {
                if (stmt instanceof IrStmt.FunctionDecl fd
                        && isReadableName(fd.name()) && !names.contains(fd.name())) {
                    names.add(fd.name());
                }
            }
        } catch (ParseException | RuntimeException ignored) {
            // Unparseable in-progress edit — the default entrypoint is still valid.
        }
        return names;
    }

    /** Skips operator-symbol names (`+`, `//`, …) — they don't read as menu items. */
    private static boolean isReadableName(String name) {
        return !name.isEmpty() && (Character.isLetter(name.charAt(0)) || name.charAt(0) == '_');
    }
}

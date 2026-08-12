package sibarum.pontif.runtime;

import sibarum.pontif.ast.AstPrinter;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrPrinter;
import sibarum.pontif.ir.TruffleLowering;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;
import sibarum.pontif.runtime.module.ModuleResolver;

import java.util.List;
import java.util.Map;

/**
 * Reviewable text artifact for the two compilation intermediates the parser
 * and lowering produce: the parsed <b>IR</b> tree, and the lowered Truffle
 * <b>execution AST</b>. Mirrors {@link ReceiptGraphReport} / {@link
 * ConservationReport} — alt source in, formatted text out — but inlines its
 * stage failures rather than collapsing to a single error, so the view always
 * shows what compiled and names precisely the stage where it stopped:
 *
 * <pre>
 *   parse  → IR section (the IrPrinter dump, or the located parse error)
 *   link + IR-compile + lower → AST section (the AstPrinter dump per function
 *                               and main, or the located stage failure)
 * </pre>
 *
 * The proof gates (return-refinement, conservation) are deliberately NOT run
 * here — those are the Receipts tab's job; this view stops at the AST so a
 * program that lowers cleanly but fails a proof still shows its IR and AST.
 */
public final class IrAstReport {

    public sealed interface Result permits Result.Generated, Result.Failed {
        record Generated(String text) implements Result {}
        record Failed(String error) implements Result {}
    }

    private static final String DIVIDER = "=".repeat(72);

    private IrAstReport() {}

    public static Result fromAltSource(String source, String sourceName) {
        return fromAltSource(source, sourceName, null);
    }

    /** As {@link #fromAltSource(String, String)} but resolving sibling
     *  {@code requires} from {@code resolveDir} — mirrors the Run path. */
    public static Result fromAltSource(String source, String sourceName, java.nio.file.Path resolveDir) {
        StringBuilder out = new StringBuilder();
        out.append("# IR — ").append(sourceName).append("\n\n");

        IrModule parsed;
        try {
            parsed = AltParser.parseModule(source, sourceName);
        } catch (ParseException pe) {
            out.append("Parse failed").append(located(pe.origin())).append(":\n  ")
                    .append(pe.getMessage()).append('\n');
            return new Result.Generated(astUnavailable(out, sourceName,
                    "source did not parse").toString());
        } catch (RuntimeException e) {
            out.append("Parse failed:\n  ").append(e.getMessage()).append('\n');
            return new Result.Generated(astUnavailable(out, sourceName,
                    "source did not parse").toString());
        }

        out.append(IrPrinter.print(parsed));

        // --- Execution AST: link → IR-compile → lower, naming the failing stage. ---
        out.append('\n').append(DIVIDER).append("\n\n");
        out.append("# Execution AST — ").append(sourceName).append("\n\n");

        IrModule linked;
        try {
            linked = ModuleResolver.resolveAndCombine(parsed, resolveDir, sourceName);
        } catch (CompileException ce) {
            out.append("(not generated — link failed").append(located(ce.origin())).append(")\n  ")
                    .append(ce.getMessage()).append('\n');
            return new Result.Generated(out.toString());
        } catch (RuntimeException e) {
            out.append("(not generated — link failed)\n  ").append(e.getMessage()).append('\n');
            return new Result.Generated(out.toString());
        }

        CompiledModule compiled;
        IrCompiler compiler;
        try {
            Simplifier simplifier = new Simplifier(List.copyOf(PontifCompiler.defaultRules()));
            compiler = new IrCompiler(simplifier);
            // Trait method expansion is a caller-owned pre-pass (see IrCompiler.compile).
            compiled = compiler.compile(sibarum.pontif.ir.TraitDefaultExpansion.expand(linked));
        } catch (CompileException ce) {
            out.append("(not generated — compile failed").append(located(ce.origin())).append(")\n  ")
                    .append(ce.getMessage()).append('\n');
            return new Result.Generated(out.toString());
        } catch (RuntimeException e) {
            out.append("(not generated — compile failed)\n  ").append(e.getMessage()).append('\n');
            return new Result.Generated(out.toString());
        }

        try {
            TruffleLowering lowering = new TruffleLowering(compiler);
            for (Map.Entry<FunctionDecl, CompiledModule.CompiledFunction> e : compiled.functions().entrySet()) {
                out.append("function ").append(e.getKey().name()).append('\n');
                PontifNode body = lowering.lowerForDisplay(e.getValue().body(), compiled);
                out.append(indent(AstPrinter.print(body))).append('\n');
            }
            out.append("main\n");
            PontifNode main = lowering.lowerForDisplay(compiled.main(), compiled);
            out.append(indent(AstPrinter.print(main)));
        } catch (RuntimeException e) {
            out.append("(lowering stopped — ").append(e.getMessage()).append(")\n");
        }
        return new Result.Generated(out.toString());
    }

    private static StringBuilder astUnavailable(StringBuilder out, String sourceName, String why) {
        out.append('\n').append(DIVIDER).append("\n\n");
        out.append("# Execution AST — ").append(sourceName).append("\n\n");
        out.append("(not generated — ").append(why).append(")\n");
        return out;
    }

    /** Indents a printed subtree one level so it nests under its "function …"/"main" header. */
    private static String indent(String block) {
        StringBuilder sb = new StringBuilder(block.length() + 16);
        for (String l : block.split("\n", -1)) {
            if (!l.isEmpty()) sb.append("  ").append(l);
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String located(Origin o) {
        return (o != null && o.isPresent()) ? " at " + o : "";
    }
}

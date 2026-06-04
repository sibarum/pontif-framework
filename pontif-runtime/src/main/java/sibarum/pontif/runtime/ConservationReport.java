package sibarum.pontif.runtime;

import sibarum.pontif.conservation.ConservationDrafter;
import sibarum.pontif.conservation.ConservationGraph.Ledger;
import sibarum.pontif.conservation.ConservationLedgerPrinter;
import sibarum.pontif.ir.AliasResolver;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;
import sibarum.pontif.runtime.module.ModuleLinker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Produces the reviewable text artifact for a program's conservation ledger —
 * the dataflow sibling of {@link ReceiptGraphReport}, same pipeline
 * (parse → link → alias-resolve → draft → print), same text-artifact
 * philosophy. This is the "see the data first" deliverable the query surface
 * will be designed from.
 */
public final class ConservationReport {

    private ConservationReport() {}

    public sealed interface Result permits Result.Generated, Result.Failed {
        record Generated(String text, Ledger ledger) implements Result {}
        record Failed(String error) implements Result {}
    }

    /** Drafts and renders the conservation report for alt-syntax source. Never throws. */
    public static Result fromAltSource(String source, String sourceName) {
        IrModule parsed;
        try {
            parsed = AltParser.parseModule(source, sourceName);
        } catch (ParseException | RuntimeException e) {
            return new Result.Failed("Parse error: " + e.getMessage());
        }
        try {
            IrModule linked = ModuleLinker.combineSingle(parsed);
            IrModule resolved = AliasResolver.resolve(linked);
            Ledger ledger = ConservationDrafter.draft(resolved);
            String text = "# Conservation ledger: " + sourceName + "\n\n"
                    + ConservationLedgerPrinter.print(ledger);
            return new Result.Generated(text, ledger);
        } catch (CompileException ce) {
            return new Result.Failed("Compile error: " + ce.getMessage());
        }
    }

    /**
     * Writes the report to {@code dir/baseName.conservation.txt}; failures
     * are written as the file body so the artifact always exists for review.
     */
    public static Path writeReport(Path dir, String baseName, String source, String sourceName)
            throws IOException {
        String body = switch (fromAltSource(source, sourceName)) {
            case Result.Generated g -> g.text();
            case Result.Failed f -> "# Conservation ledger: " + sourceName + "\n\n"
                    + f.error() + "\n";
        };
        Files.createDirectories(dir);
        Path out = dir.resolve(baseName + ".conservation.txt");
        Files.writeString(out, body);
        return out;
    }
}

package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Temporary reproduction harness for pontif-playground/examples/ternion.ptf. */
class TernionReproTest {

    @Test
    void reproduce() throws Exception {
        Path file = Path.of("../pontif-playground/examples/ternion.ptf");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(file),
                "playground example not present; skipping dev harness");
        String src = Files.readString(file);
        // Dev harness for whatever's currently in the playground example —
        // prints the outcome; never fails the suite.
        try {
            PontifCompiler.CompileResult r = new PontifCompiler().compile(src, "ternion.ptf");
            PontifRunner.RunResult run = new PontifRunner().run(r, PontifRunner.Engine.INTERPRETER);
            System.out.println("isError=" + run.isError());
            System.out.println("origin=" + run.origin());
            System.out.println("text=" + run.text());
        } catch (Exception e) {
            System.out.println("THREW: " + e.getClass().getName() + ": " + e.getMessage());
        }
        // The playground's Receipts tab exercises BOTH report paths — test them too.
        switch (ReceiptGraphReport.fromPontifSource(src, "ternion.ptf")) {
            case ReceiptGraphReport.Result.Generated g ->
                    System.out.println("receiptGraph=OK");
            case ReceiptGraphReport.Result.Failed f ->
                    System.out.println("receiptGraph FAILED: " + f.error());
        }
        switch (ConservationReport.fromPontifSource(src, "ternion.ptf")) {
            case ConservationReport.Result.Generated g ->
                    System.out.println("conservation=OK");
            case ConservationReport.Result.Failed f ->
                    System.out.println("conservation FAILED: " + f.error());
        }
    }
}

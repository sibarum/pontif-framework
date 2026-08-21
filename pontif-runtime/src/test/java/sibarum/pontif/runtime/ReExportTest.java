package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-exports (first real consumer: {@code std.common}, ruled 2026-06-06):
 * a module may export a name it imports, and importers resolve through the
 * chase to the DECLARING origin — one nominal, however many doors it's
 * served from. {@code Leaf()} lives in {@code std.common}; {@code std.proof}
 * re-exports it, so both import paths name the same type.
 */
class ReExportTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src) {
        return runner.run(compiler.compile(src, "t.ptf"), Engine.INTERPRETER);
    }

    @Test
    void leafImportsFromItsNewHome() {
        RunResult r = run("""
                requires std.common.{Leaf}
                match Leaf() {
                  [Leaf] -> 1
                  _ -> 0
                }
                """);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("1", r.text());
    }

    @Test
    void leafStillImportsThroughStdProof_theReExportDoor() {
        // The compatibility pin: the pre-std.common import surface unchanged.
        RunResult r = run("""
                requires std.proof.{Leaf}
                match Leaf() {
                  [Leaf] -> 1
                  _ -> 0
                }
                """);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("1", r.text());
    }

    @Test
    void bothDoors_areTheSameNominal_notAmbiguous() {
        // Importing the same origin through two re-export doors is one name
        // arriving twice — neither ambiguous nor two types: a value built via
        // the std.proof door matches a pattern resolved via the std.common door.
        RunResult r = run("""
                requires std.proof.{Leaf}
                requires std.common.{Leaf}
                match Leaf() {
                  [Leaf] -> 1
                  _ -> 0
                }
                """);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("1", r.text());
    }

    @Test
    void crossDoorValues_matchEachOther() {
        // Construct through std.proof's door (renamed), test against
        // std.common's — same nominal, so the claim matches.
        RunResult r = run("""
                requires std.proof.{Leaf -> ProofLeaf}
                requires std.common.{Leaf}
                match ProofLeaf() {
                  [Leaf] -> 1
                  _ -> 0
                }
                """);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("1", r.text());
    }

    @Test
    void proofTreesStillBuild_throughTheReExport() {
        // The original consumer keeps working: Leaf arrives via std.proof's
        // re-export and the proof vocabulary recognizes it.
        CompileResult r = compiler.compile("""
                requires std.proof.{Leaf, Split}
                function f(x:Int):[Int:@>=0] -> x*(x-1)
                proof f = Split(x>=1, Leaf(), Leaf())
                42
                """, "t.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((CompileResult.Failed) r).error().text());
    }

    @Test
    void unexportedName_throughAnyDoor_stillRejects() {
        CompileResult r = compiler.compile("""
                requires std.common.{Split}
                42
                """, "t.ptf");
        CompileResult.Failed failed = assertInstanceOf(CompileResult.Failed.class, r,
                "std.common declares no Split — must reject");
        assertTrue(failed.error().text().contains("Split"),
                () -> "got: " + failed.error().text());
    }
}

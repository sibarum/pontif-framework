package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An arm no value of the scrutinee could ever reach is a compile error (RULED James 2026-08-25).
 *
 * <p>Dead code in a match is a lie about what the program considers: the author believes a case
 * is handled and it is not. It is also invisible, because the `_` default that satisfies totality
 * is exactly what hides it — the match runs, takes the default, and looks fine.
 *
 * <p><b>The proof is deliberately coarse.</b> A runtime value is a scalar, a positional tuple, or
 * a record, and never two of those, so an arm demanding a different kind than the scrutinee can
 * supply is unreachable. That is the only disjointness claimed. Two records, two scalars, a
 * refinement that happens not to overlap, a trait, a type variable — all abstain, because
 * rejecting on anything less than a proof is how a gate starts refusing valid programs. The
 * abstentions below are as much the specification as the rejections.
 */
class DeadMatchArmTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Runs on both engines, asserts they agree, and returns the shared answer. */
    private String value(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "arm.ptf");
        assertFalse(r instanceof PontifCompiler.CompileResult.Failed,
                () -> "expected compile success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        String first = null;
        for (Engine e : Engine.values()) {
            RunResult out = runner.run(r, e);
            assertFalse(out.isError(), () -> "expected success; got: " + out.text());
            if (first == null) {
                first = out.text();
            } else {
                final String expected = first;
                assertEquals(expected, out.text(), () -> "engines disagree on: " + src);
            }
        }
        return first;
    }

    private String rejectsAsDead(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "arm.ptf");
        assertTrue(r instanceof PontifCompiler.CompileResult.Failed,
                "expected the dead arm to be rejected; the program compiled");
        String err = ((PontifCompiler.CompileResult.Failed) r).error().text();
        assertTrue(err.contains("can never match"),
                () -> "expected the dead-arm diagnostic; got: " + err);
        return err;
    }

    // --- rejected: the kinds cannot coincide --------------------------------------

    @Test
    void aScalarArmOverARecordScrutinee() {
        String err = rejectsAsDead("""
                let p = {x = 3, y = 4}
                match p { [Int] -> 1  [_] -> 0 }
                """);
        assertTrue(err.contains("a scalar"), () -> "the diagnostic should name the arm's kind: " + err);
    }

    @Test
    void aScalarArmOverAStructScrutinee() {
        rejectsAsDead("""
                struct Point(x:Int, y:Int)
                let p = Point(3, 4)
                match p { [Int] -> 1  [_] -> 0 }
                """);
    }

    @Test
    void aTupleArmOverARecordScrutinee() {
        rejectsAsDead("""
                let p = {x = 3, y = 4}
                match p { [{Int, Int}] -> 1  [_] -> 0 }
                """);
    }

    @Test
    void aTupleArmOverAScalarScrutinee() {
        rejectsAsDead("""
                let n = 3
                match n { [{Int, Int}] -> 1  [_] -> 0 }
                """);
    }

    @Test
    void aStructArmOverAScalarScrutinee() {
        rejectsAsDead("""
                struct Point(x:Int, y:Int)
                let n = 3
                match n { [Point] -> 1  [_] -> 0 }
                """);
    }

    @Test
    void theDiagnosticPointsAtTheArm() {
        PontifCompiler.CompileResult r = compiler.compile("""
                let p = {x = 3, y = 4}
                match p
                  [Int] -> 1
                  [_]   -> 0
                """, "arm.ptf");
        var failed = (PontifCompiler.CompileResult.Failed) r;
        assertTrue(failed.error().origin().isPresent(), "the error should carry an origin");
        assertEquals(3, failed.error().origin().get().span().start().line(),
                "the error belongs on the dead arm, not on the match");
    }

    // --- abstained: everything short of a proof -----------------------------------

    @Test
    void twoScalarArmsThatDoNotOverlap() {
        // `[@>0]` and `[@<=0]` are disjoint from each other but neither is dead — the
        // scrutinee reaches both. Predicate reasoning is the totality check's job, not this one's.
        assertEquals("1", value("""
                let n = 3
                match n { [Int:@>0] -> 1  [Int:@<=0] -> 0 }
                """));
    }

    @Test
    void aRefinementTheValueHappensNotToSatisfy() {
        // `[@>100]` never fires for THIS value, but it is not dead for the sort `Int`.
        assertEquals("0", value("""
                let n = 3
                match n { [Int:@>100] -> 1  [_] -> 0 }
                """));
    }

    @Test
    void aUnionScrutineeWithOneArmPerMember() {
        // The canonical sum-type match: each arm is dead against the OTHER branch and live
        // against its own, so none is dead against the union.
        assertEquals("2", value("""
                struct A(x:Int)
                struct B(y:Int)
                function f(v:[A|B]):Int -> match v
                  [A] -> 1
                  [B] -> 2
                f(B(5))
                """));
    }

    @Test
    void aUnionOfAScalarAndAStruct() {
        // The kinds differ across the union's branches, so an arm matching either branch must
        // still be admitted — this is the case a naive kind check would break.
        assertEquals("1", value("""
                struct Wrapped(x:Int)
                function f(v:[Int|Wrapped]):Int -> match v
                  [Int] -> 1
                  [Wrapped] -> 2
                f(7)
                """));
    }

    @Test
    void twoUnrelatedStructs() {
        // Both are records, so the kind proof says nothing. A nominal-disjointness proof would
        // reject this — that is a different, larger claim and is not made here.
        assertEquals("0", value("""
                struct Point(x:Int, y:Int)
                struct Label(text:String)
                let p = Point(3, 4)
                match p { [Label] -> 1  [_] -> 0 }
                """));
    }

    @Test
    void aStructArmOverAnAnonymousRecordScrutinee() {
        // A record could carry the struct's claim, so nothing is proved. (It does not here, and
        // the value falls to the default — at runtime, which is correct.)
        assertEquals("0", value("""
                struct Point(x:Int, y:Int)
                let p = {x = 3, y = 4}
                match p { [Point] -> 1  [_] -> 0 }
                """));
    }

    @Test
    void aTraitArmOverAStructScrutinee() {
        assertEquals("4", value("""
                trait Sized { size:[Method():Int] }
                struct Brick(n:Int)
                assign trait Brick:Sized { size():Int -> 4 }
                let b = Brick(4)
                match b { [Sized] -> b.size()  [_] -> 0 }
                """));
    }

    @Test
    void aDecimalScrutineeWhoseAnatomyIsAShape() {
        // Decimal is a scalar that also projects to (unscaled, scale), so inference can hand
        // back a record-shaped sort for it. It clashes with neither kind — the case that made
        // the first cut of this check reject a valid program.
        assertEquals("2", value("""
                let d = Decimal(250, 2)
                match d { [Decimal] -> d.scale  [_] -> 0 }
                """));
    }

    @Test
    void anEnumMatchOverItsCases() {
        assertEquals("1", value("""
                enum Colour { Red; Green }
                let c = Colour.Red
                match c { [Colour.Red] -> 1  [Colour.Green] -> 2 }
                """));
    }
}

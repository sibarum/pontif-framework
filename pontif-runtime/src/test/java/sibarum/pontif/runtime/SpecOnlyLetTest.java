package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec-only top-level lets: a value-pinning sort IS the definition. The
 * predicate {@code @==EXPR} carries its witness as an expression, so the
 * parser synthesizes the body verbatim from the pin —
 * {@code let zero:[Decimal:@==0.0]} means {@code zero = 0.0}. The synthesized
 * binding rides everything a written one does: the claim wrapper (notarized
 * at force — the synthesis-bug detector), Int→Decimal promotion, and the
 * Inquisition's force-evaluation. Sorts that don't pin a unique witness
 * ({@code [Int:@>0]}, self-referential pins) stay NoOp — synthesis from
 * non-singleton maximally-specific sorts is a separate, unruled TODO.
 */
class SpecOnlyLetTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src, Engine engine) {
        return runner.run(compiler.compileAlt(src, "t.ptf"), engine);
    }

    @Test
    void pinnedDecimal_definesTheBinding_andFlowsIntoMixedArithmetic() {
        // The motivating program, verbatim.
        String src = "let zero:[Decimal:@==0.0]\nlet five = zero + 5\nfive";
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("5.0", r.text(), engine.toString());
        }
    }

    @Test
    void pinnedInt_andBareExprSugar_bothSynthesize() {
        for (Engine engine : Engine.values()) {
            assertEquals("6", run("let six:[Int:@==6]\nsix", engine).text(), engine.toString());
            // [Int:6] ≡ [Int:@==6] — the sugar pins identically.
            assertEquals("6", run("let six:[Int:6]\nsix", engine).text(), engine.toString());
        }
    }

    @Test
    void expressionPin_synthesizesTheExpression() {
        // The witness is an expression, not just a literal — synthesized
        // verbatim; evaluation does the rest.
        for (Engine engine : Engine.values()) {
            assertEquals("6", run("let six:[Int:@==2*3]\nsix", engine).text(), engine.toString());
        }
    }

    @Test
    void intLiteralPin_atDecimalBase_promotes() {
        // The synthesized Int witness rides the same Int→Decimal embedding
        // as a written one.
        for (Engine engine : Engine.values()) {
            RunResult r = run("let zero:[Decimal:@==0]\nzero", engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("0.0", r.text(), engine.toString());
        }
    }

    @Test
    void unpinnedSort_staysNoOp() {
        // No unique witness — no synthesis. (Whether this should become a
        // parse error instead of a silent NoOp is an open ruling.)
        for (Engine engine : Engine.values()) {
            RunResult r = run("let pos:[Int:@>0]\n42", engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("42", r.text(), engine.toString());
        }
    }

    // --- semantic pins: integer discreteness collapses intervals ------------

    @Test
    void intInterval_thatIsASingleton_synthesizes() {
        // The motivating program: over the INTEGERS, @>-1 & @<1 is {0} —
        // the bound engine's integer-strict cuts derive the witness. The
        // synthesized binding then flows like any other.
        String src = "let zero:[Int:@>-1 & @<1]\nlet five = zero + 5\nfive";
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("5", r.text(), engine.toString());
        }
    }

    @Test
    void semanticSingletons_acrossShapes() {
        for (Engine engine : Engine.values()) {
            // [3, 4) over Int = {3}
            assertEquals("3", run("let three:[Int:@>=3 & @<4]\nthree", engine).text(), engine.toString());
            // (-2, 0) over Int = {-1}
            assertEquals("-1", run("let negOne:[Int:@>-2 & @<0]\nnegOne", engine).text(), engine.toString());
            // [0, 0]
            assertEquals("0", run("let z:[Int:@>=0 & @<=0]\nz", engine).text(), engine.toString());
        }
    }

    @Test
    void nonSingletonInterval_staysNoOp() {
        // {1, 2} — two witnesses, no synthesis; choosing one would inject
        // information the program never supplied.
        for (Engine engine : Engine.values()) {
            RunResult r = run("let small:[Int:@>0 & @<3]\n42", engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("42", r.text(), engine.toString());
        }
    }

    @Test
    void decimalInterval_neverPinsSemantically() {
        // (-1.0, 1.0) over Decimal is not a singleton — discreteness is the
        // license, and Decimal doesn't have it. Stays NoOp.
        for (Engine engine : Engine.values()) {
            RunResult r = run("let zero:[Decimal:@>-1.0 & @<1.0]\n42", engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("42", r.text(), engine.toString());
        }
    }

    @Test
    void inferredLetReturnSorts_areDefinitional_notObligations() {
        // `five`'s sort [Int:@==zero+5] was inferred FROM the body — a
        // receipt of inference, not a claim, so the return gate mints no
        // obligation for it (the let's declared claim lives in the claim
        // wrapper instead). Chained Int lets through calls compile.
        String src = "let zero:[Int:@>-1 & @<1]\nlet six = zero + 6\nlet out = six + 0\nout";
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("6", r.text(), engine.toString());
        }
    }

    @Test
    void selfReferentialPin_hasNoWitness_staysNoOp() {
        for (Engine engine : Engine.values()) {
            RunResult r = run("let weird:[Int:@==@+1]\n42", engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("42", r.text(), engine.toString());
        }
    }

    @Test
    void synthesizedBinding_isForcedLikeAnyOther() {
        // The synthesized let is a top-level let: the Inquisition forces it.
        // A pin whose witness violates a SECOND constraint elsewhere proves
        // the wrapper claim still notarizes — here the claim is the pin
        // itself, so it trivially passes; the force is observable through
        // the chained binding's claim instead.
        String misses = "let zero:[Decimal:@==0.0]\nlet bad:[Decimal:@>0] = zero\n42";
        for (Engine engine : Engine.values()) {
            RunResult bad = run(misses, engine);
            assertTrue(bad.isError(), () -> engine + ": expected a binding-claim failure");
            assertTrue(bad.text().contains("claim violated"),
                    () -> engine + " got: " + bad.text());
        }
    }
}

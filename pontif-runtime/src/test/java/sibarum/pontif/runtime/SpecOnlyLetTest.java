package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec-only top-level lets: a value-pinning sort IS the definition. Terminating the
 * let without a value synthesizes it — synthesis is a consequence of the definition,
 * not a directive. The optional {@code ;} is just a terminator, so both
 * {@code let zero:[Decimal:@==0.0];} and {@code let zero:[Decimal:@==0.0]} mean
 * {@code zero = 0.0}. The predicate {@code @==EXPR} carries its witness as an
 * expression, synthesized verbatim from the pin. The synthesized binding rides
 * everything a written one does: the claim wrapper (compile-checked like any claim —
 * the synthesis-bug detector), Int→Decimal promotion, and the Inquisition's
 * force-evaluation. A sort that doesn't pin a unique witness ({@code [Int:@>0]},
 * self-referential pins) is an honest "does not pin" error — not a silent NoOp.
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
        String src = "let zero:[Decimal:@==0.0];\nlet five = zero + 5\nfive";
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("5.0", r.text(), engine.toString());
        }
    }

    @Test
    void pinnedSort_withoutTerminator_stillSynthesizes() {
        // The `;` is optional — a value-pinning let terminated by a newline (the next
        // statement) synthesizes just the same. This is the core of demoting `;` from
        // a synthesis directive to a mere terminator.
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
            assertEquals("6", run("let six:[Int:@==6];\nsix", engine).text(), engine.toString());
            // [Int:6] ≡ [Int:@==6] — the sugar pins identically.
            assertEquals("6", run("let six:[Int:6];\nsix", engine).text(), engine.toString());
        }
    }

    @Test
    void expressionPin_synthesizesTheExpression() {
        // The witness is an expression, not just a literal — synthesized
        // verbatim; evaluation does the rest.
        for (Engine engine : Engine.values()) {
            assertEquals("6", run("let six:[Int:@==2*3];\nsix", engine).text(), engine.toString());
        }
    }

    @Test
    void intLiteralPin_atDecimalBase_promotes() {
        // The synthesized Int witness rides the same Int→Decimal embedding
        // as a written one.
        for (Engine engine : Engine.values()) {
            RunResult r = run("let zero:[Decimal:@==0];\nzero", engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("0.0", r.text(), engine.toString());
        }
    }

    @Test
    void unpinnedSort_withDirective_isError() {
        // No unique witness — `;` requested synthesis the sort can't supply.
        for (Engine engine : Engine.values()) {
            RunResult r = run("let pos:[Int:@>0];\n42", engine);
            assertTrue(r.isError(), () -> engine + ": expected a 'does not pin' error");
            assertTrue(r.text().contains("does not pin"), () -> engine + " got: " + r.text());
        }
    }

    // --- semantic pins: integer discreteness collapses intervals ------------

    @Test
    void intInterval_thatIsASingleton_synthesizes() {
        // The motivating program: over the INTEGERS, @>-1 & @<1 is {0} —
        // the bound engine's integer-strict cuts derive the witness. The
        // synthesized binding then flows like any other.
        String src = "let zero:[Int:@>-1 & @<1];\nlet five = zero + 5\nfive";
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
            assertEquals("3", run("let three:[Int:@>=3 & @<4];\nthree", engine).text(), engine.toString());
            // (-2, 0) over Int = {-1}
            assertEquals("-1", run("let negOne:[Int:@>-2 & @<0];\nnegOne", engine).text(), engine.toString());
            // [0, 0]
            assertEquals("0", run("let z:[Int:@>=0 & @<=0];\nz", engine).text(), engine.toString());
        }
    }

    @Test
    void nonSingletonInterval_withDirective_isError() {
        // {1, 2} — two witnesses; choosing one would inject information the
        // program never supplied, so `;` is an honest "does not pin" error.
        for (Engine engine : Engine.values()) {
            RunResult r = run("let small:[Int:@>0 & @<3];\n42", engine);
            assertTrue(r.isError(), () -> engine + ": expected a 'does not pin' error");
            assertTrue(r.text().contains("does not pin"), () -> engine + " got: " + r.text());
        }
    }

    @Test
    void decimalInterval_neverPinsSemantically_isError() {
        // (-1.0, 1.0) over Decimal is not a singleton — discreteness is the
        // license, and Decimal doesn't have it. `;` can't synthesize → error.
        for (Engine engine : Engine.values()) {
            RunResult r = run("let zero:[Decimal:@>-1.0 & @<1.0];\n42", engine);
            assertTrue(r.isError(), () -> engine + ": expected a 'does not pin' error");
            assertTrue(r.text().contains("does not pin"), () -> engine + " got: " + r.text());
        }
    }

    @Test
    void inferredLetReturnSorts_areDefinitional_notObligations() {
        // `five`'s sort [Int:@==zero+5] was inferred FROM the body — a
        // receipt of inference, not a claim, so the return gate mints no
        // obligation for it (the let's declared claim lives in the claim
        // wrapper instead). Chained Int lets through calls compile.
        String src = "let zero:[Int:@>-1 & @<1];\nlet six = zero + 6\nlet out = six + 0\nout";
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("6", r.text(), engine.toString());
        }
    }

    @Test
    void selfReferentialPin_hasNoWitness_isError() {
        for (Engine engine : Engine.values()) {
            RunResult r = run("let weird:[Int:@==@+1];\n42", engine);
            assertTrue(r.isError(), () -> engine + ": expected a 'does not pin' error");
            assertTrue(r.text().contains("does not pin"), () -> engine + " got: " + r.text());
        }
    }

    @Test
    void synthesizedBinding_claimIsCompileCheckedLikeAnyOther() {
        // The synthesized let is a top-level let, judged like any written one. Its
        // pinned witness (zero = 0.0) flows into the chained binding's claim via the
        // effective-sort lens: `bad`'s value is [Decimal:@==0.0], which cannot be
        // proved to satisfy [Decimal:@>0]. §1d: a compile error, not a runtime force.
        String misses = "let zero:[Decimal:@==0.0];\nlet bad:[Decimal:@>0] = zero\n42";
        for (Engine engine : Engine.values()) {
            RunResult bad = run(misses, engine);
            assertTrue(bad.isError(), () -> engine + ": expected a compile-time rejection");
            assertTrue(bad.text().contains("cannot be proved to satisfy"),
                    () -> engine + " got: " + bad.text());
        }
    }
}

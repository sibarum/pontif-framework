package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim rule's binding half: a declared sort at a {@code let} is a claim
 * made where the binding is made, judged by the {@code ConstructionGate}
 * exactly like a constructor argument — three-way:
 * <ul>
 *   <li><b>provable fit</b> — passes with NO runtime check (the claim is
 *       stripped from the IR; the proof discharged it),</li>
 *   <li><b>provable miss</b> — compile-time error (the binding would be
 *       born lying),</li>
 *   <li><b>genuine overlap / undecidable</b> — compiles, with a runtime
 *       check at bind.</li>
 * </ul>
 * Before this gate, the declared sort was silently dropped at parse time —
 * {@code let z:[Decimal:@==0] = 1.0} ran clean, a leniency that lied. Both
 * let levels are covered: local ({@code LetIn} carries the claim directly)
 * and top-level (the 0-arg lowering wraps its value in a claim-bearing
 * {@code LetIn}). The Int→Decimal embedding rides along: a declared Decimal
 * boundary now admits an Int literal (promoted, then judged) instead of the
 * parser's base-sort mismatch.
 */
class LetClaimGateTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src, Engine engine) {
        return runner.run(compiler.compileAlt(src, "t.ptf"), engine);
    }

    private CompiledModule compiled(String src) {
        CompileResult result = compiler.compileAlt(src, "t.ptf");
        CompileResult.Compiled ok = assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "expected a clean compile; got: "
                        + ((CompileResult.Failed) result).error().text());
        return ok.program().module();
    }

    private void assertCompileError(String src) {
        CompileResult result = compiler.compileAlt(src, "t.ptf");
        CompileResult.Failed failed = assertInstanceOf(CompileResult.Failed.class, result,
                "expected a compile-time rejection");
        assertTrue(failed.error().text().contains("can never satisfy"),
                () -> "Expected the disjoint-binding error; got: " + failed.error().text());
    }

    // --- provable miss → compile error --------------------------------------

    @Test
    void topLevelLet_disjointLiteral_isCompileError() {
        // [Int:@==1] is disjoint from the claimed [Int:@==0] — caught at
        // compile time, where the claim is made.
        assertCompileError("let a:[Int:@==0] = 1\na");
    }

    @Test
    void localLet_disjointLiteral_isCompileError() {
        assertCompileError("function h():Int -> { let a:[Int:@==0] = 1\n a }\nh()");
    }

    // --- provable fit → passes with NO runtime check -------------------------

    @Test
    void provableFit_passes_andClaimIsStripped() {
        String src = "function h():Int -> { let a:[Int:@==0] = 0\n a }\nh()";
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("0", r.text(), engine.toString());
        }
        // The discriminating half: the fit was PROVEN, so the binding
        // carries no claim in the compiled IR — no runtime check exists.
        IrExpr.LetIn let = findLet(compiled(src), "a");
        assertNotNull(let, "expected the let binding in h's body");
        assertNull(let.claim(), () -> "provable fit must strip the claim; got: " + let.claim());
    }

    @Test
    void looserClaim_overTighterValue_isAlsoDischarged() {
        // [Int:@==5] implies [Int:@>0] — fit proven, claim stripped.
        String src = "let k:[Int:@>0] = 5\nk";
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("5", r.text(), engine.toString());
        }
    }

    // --- genuine overlap → compiles, runtime check at bind -------------------

    @Test
    void overlapClaim_isActuallyStamped() {
        // The value is a bare-Int param — overlaps @==0 without implying it.
        CompiledModule module = compiled(
                "function g(v:Int):Int -> { let a:[Int:@==0] = v\n a }\ng(0)");
        IrExpr.LetIn let = findLet(module, "a");
        assertNotNull(let, "expected the let binding in g's body");
        assertNotNull(let.claim(), "overlap must keep the claim for the runtime");
    }

    @Test
    void overlapClaim_passesWhenValueFits_failsWhenItMisses() {
        String fits = "function g(v:Int):Int -> { let a:[Int:@==0] = v\n a }\ng(0)";
        String misses = "function g(v:Int):Int -> { let a:[Int:@==0] = v\n a }\ng(1)";
        for (Engine engine : Engine.values()) {
            RunResult ok = run(fits, engine);
            assertFalse(ok.isError(), () -> engine + " got: " + ok.text());
            assertEquals("0", ok.text(), engine.toString());

            RunResult bad = run(misses, engine);
            assertTrue(bad.isError(), () -> engine + ": expected a binding-claim failure");
            assertTrue(bad.text().contains("claim violated"),
                    () -> engine + " got: " + bad.text());
        }
    }

    // --- Decimal claims: outside the compile-time kernel → runtime ----------

    @Test
    void decimalClaim_misses_atRuntime() {
        // Decimal predicates are outside the compile-time kernel (for now),
        // so the miss is caught by the bind check — previously it ran clean.
        for (Engine engine : Engine.values()) {
            RunResult bad = run("let z:[Decimal:@==0] = 1.0\nz", engine);
            assertTrue(bad.isError(), () -> engine + ": expected a binding-claim failure");
            assertTrue(bad.text().contains("claim violated"),
                    () -> engine + " got: " + bad.text());
        }
    }

    @Test
    void decimalClaim_fits_upToScale() {
        // 0.00 == 0.0 up to scale — the equality narrow, not bitwise
        // equality. (Display canonicalizes every zero to "0.0".)
        for (Engine engine : Engine.values()) {
            RunResult r = run("let w:[Decimal:@==0.0] = 0.00\nw", engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("0.0", r.text(), engine.toString());
        }
    }

    // --- the Int→Decimal embedding at a let boundary -------------------------

    @Test
    void intLiteral_atDeclaredDecimal_promotesInsteadOfMismatching() {
        // Previously a parse-time "base sort mismatch"; the embedding is
        // lossless, so the literal promotes and the claim judges clean.
        for (Engine engine : Engine.values()) {
            RunResult bare = run("let x:Decimal = 0\nx", engine);
            assertFalse(bare.isError(), () -> engine + " got: " + bare.text());

            RunResult refined = run("let u:[Decimal:@==0] = 0\nu", engine);
            assertFalse(refined.isError(), () -> engine + " got: " + refined.text());
        }
    }

    @Test
    void nonEmbeddingBaseMismatch_staysAParseError() {
        // The embedding exemption is Int→Decimal only — Bool against Int is
        // still the parser's early rejection.
        CompileResult result = compiler.compileAlt("let m:Bool = 5\nm", "t.ptf");
        CompileResult.Failed failed = assertInstanceOf(CompileResult.Failed.class, result,
                "expected a compile-time rejection");
        assertTrue(failed.error().text().contains("base sort mismatch"),
                () -> "Expected the parser's base check; got: " + failed.error().text());
    }

    // --- the Inquisition: top-level lets force-evaluate at program start ----

    @Test
    void unreferencedTopLevelLet_isStillNotarized() {
        // The lazy ruling's loophole, closed (2026-06-07): nothing references
        // `zero`, but the binding force-evaluates before main and its claim
        // check fires anyway. No unnotarized lies.
        for (Engine engine : Engine.values()) {
            RunResult bad = run("let zero:[Decimal:@==0.0] = 1\n42", engine);
            assertTrue(bad.isError(), () -> engine + ": expected a binding-claim failure");
            assertTrue(bad.text().contains("claim violated"),
                    () -> engine + " got: " + bad.text());
        }
    }

    @Test
    void unreferencedTopLevelLet_constructionChecksAlsoFire() {
        // Same closure for construction claims living inside a let's value.
        String src = """
                struct Account(balance:[Decimal:@>=0], rate:Decimal)
                let a = Account(-5.0, 0.05)
                42
                """;
        for (Engine engine : Engine.values()) {
            RunResult bad = run(src, engine);
            assertTrue(bad.isError(), () -> engine + ": expected a construction failure");
            assertTrue(bad.text().contains("Construction claim violated"),
                    () -> engine + " got: " + bad.text());
        }
    }

    @Test
    void genuineZeroArgFunctions_areNotForced() {
        // Forcing is for LETS. A 0-arg FUNCTION with a failing body is
        // legitimate until applied — main never calls it, program runs.
        String src = """
                struct Pos(v:[Int:@>0])
                function mk(o:[Int:@<=1]):Pos -> Pos(o)
                function unused():Pos -> mk(1)
                42
                """;
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("42", r.text(), engine.toString());
        }
    }

    @Test
    void forcing_chainsThroughDependentLets() {
        // Declaration-order forcing: `a` computes through `b` (lets stay
        // callable functions, so the dependency just evaluates — pure means
        // the double evaluation is invisible), and a's claim checks at force.
        // (Decimal chain: an Int chain trips a PRE-EXISTING return-prover
        // limitation — `let a = b + 1` infers [Int:@==b+1], whose obligation
        // keeps raw `b` while the body equation hoists the call. Unrelated
        // to forcing; see the chained-lets note.)
        String fits = "let b = 2.0\nlet a:[Decimal:@>0] = b\na";
        String misses = "let b = -2.0\nlet a:[Decimal:@>0] = b\n42";
        for (Engine engine : Engine.values()) {
            RunResult r = run(fits, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("2.0", r.text(), engine.toString());

            // The chained claim is notarized at force, unreferenced by main.
            RunResult bad = run(misses, engine);
            assertTrue(bad.isError(), () -> engine + ": expected a binding-claim failure");
            assertTrue(bad.text().contains("claim violated"),
                    () -> engine + " got: " + bad.text());
        }
    }

    /** The named let binding anywhere in the module — function bodies first, then main. */
    private static IrExpr.LetIn findLet(CompiledModule module, String name) {
        for (var fn : module.functions().values()) {
            IrExpr.LetIn found = firstLet(fn.body(), name);
            if (found != null) return found;
        }
        return firstLet(module.main(), name);
    }

    /** Depth-first search for the first let of the given name in an expression. */
    private static IrExpr.LetIn firstLet(IrExpr e, String name) {
        if (e == null) return null;
        return switch (e) {
            case IrExpr.LetIn l -> {
                if (name.equals(l.name())) yield l;
                IrExpr.LetIn v = firstLet(l.value(), name);
                yield v != null ? v : firstLet(l.body(), name);
            }
            case IrExpr.BinOp op -> {
                IrExpr.LetIn l = firstLet(op.left(), name);
                yield l != null ? l : firstLet(op.right(), name);
            }
            case IrExpr.Call c -> {
                IrExpr.LetIn found = null;
                for (IrExpr a : c.args()) {
                    found = firstLet(a, name);
                    if (found != null) break;
                }
                yield found;
            }
            case IrExpr.Match m -> {
                IrExpr.LetIn s = firstLet(m.scrutinee(), name);
                if (s != null) yield s;
                IrExpr.LetIn found = null;
                for (IrExpr.MatchBranch b : m.branches()) {
                    found = firstLet(b.result(), name);
                    if (found != null) break;
                }
                yield found;
            }
            case IrExpr.FieldAccess fa -> firstLet(fa.base(), name);
            default -> null;
        };
    }
}

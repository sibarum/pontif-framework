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
 * exactly like a constructor argument, under the §1d no-unproven-runtime-check
 * law (roadmap §1d):
 * <ul>
 *   <li><b>provable fit</b> — passes with NO runtime check (the claim is
 *       stripped from the IR; the proof discharged it),</li>
 *   <li><b>anything else (provable miss OR undecidable overlap)</b> —
 *       a compile-time error. The compiler must prove the binding's value
 *       satisfies the claim; an unprovable claim is not silently stamped for
 *       the runtime (the retired third verdict).</li>
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

    /** §1d: an undecidable (overlap / outside-the-kernel) claim is a compile error —
     *  "cannot be proved to satisfy" — not a silently stamped runtime check. */
    private void assertUnprovable(String src) {
        CompileResult result = compiler.compileAlt(src, "t.ptf");
        CompileResult.Failed failed = assertInstanceOf(CompileResult.Failed.class, result,
                "expected a compile-time rejection (§1d: no silent runtime stamp)");
        assertTrue(failed.error().text().contains("cannot be proved to satisfy"),
                () -> "Expected the unprovable-claim error; got: " + failed.error().text());
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
        String src = "function h():Int -> ( let a:[Int:@==0] = 0\n a )\nh()";
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

    // --- genuine overlap → compile error (§1d), not a runtime stamp ----------

    @Test
    void overlapClaim_isCompileError() {
        // The value is a bare-Int param — overlaps @==0 without implying it. §1d:
        // the claim can't be proved from a bare Int, so it is a compile error
        // inside g's own body. (Formerly this compiled and kept the claim as a
        // runtime check; there is no third "stamp" verdict anymore.)
        assertUnprovable("function g(v:Int):Int -> ( let a:[Int:@==0] = v\n a )\ng(0)");
    }

    @Test
    void provenClaim_overNarrowedParam_compilesAndRuns() {
        // The §1d way to make it legal: narrow the param so the claim is PROVEN.
        // [Int:@==0] implies [Int:@==0] → discharged; g(0) runs to 0.
        String src = "function g(v:[Int:@==0]):Int -> ( let a:[Int:@==0] = v\n a )\ng(0)";
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("0", r.text(), engine.toString());
        }
    }

    // --- Decimal claims: a miss is unprovable → compile error (§1d) ----------

    @Test
    void decimalClaim_misses_isCompileError() {
        // [Decimal:@==1.0] does not imply [Decimal:@==0], and the miss is outside
        // the integer kernel's DISJOINT reach — so it lands as "cannot be proved".
        // §1d rejects it at compile time (it formerly ran clean, then was stamped).
        assertUnprovable("let z:[Decimal:@==0] = 1.0\nz");
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
        assertTrue(failed.error().text().contains("different types"),
                () -> "Expected the parser's base check; got: " + failed.error().text());
    }

    // --- §1d: claims are compile-checked regardless of reference / reachability -

    @Test
    void unreferencedTopLevelLet_claimIsCompileChecked() {
        // Nothing references `zero`, yet its claim is judged at compile time —
        // reachability is irrelevant to a compile error. §1d: the unprovable
        // Decimal claim is rejected before the program ever runs. (Formerly this
        // slipped past parse-time and was caught only by runtime forcing.)
        assertUnprovable("let zero:[Decimal:@==0.0] = 1\n42");
    }

    @Test
    void unreferencedTopLevelLet_constructionClaimsAreCompileChecked() {
        // Same for a construction claim living inside an unreferenced let's value.
        assertUnprovable("""
                struct Account(balance:[Decimal:@>=0], rate:Decimal)
                let a = Account(-5.0, 0.05)
                42
                """);
    }

    @Test
    void unprovableConstructionInUnusedFunction_isStillCompileError() {
        // §1d checks every function body unconditionally: `mk`'s Pos(o) is an
        // undecidable fit ([Int:@<=1] overlaps [Int:@>0] without implying it), so
        // `mk` is a compile error even though main never calls it. (Formerly the
        // body compiled with a runtime stamp that only fired if `mk` was applied.)
        assertUnprovable("""
                struct Pos(v:[Int:@>0])
                function mk(o:[Int:@<=1]):Pos -> Pos(o)
                function unused():Pos -> mk(1)
                42
                """);
    }

    @Test
    void dependentLetChain_provenFitCompiles_missIsCompileError() {
        // `a`'s claim is decided from `b`'s EFFECTIVE sort (the effective-sort lens
        // threads the pinned value of the preceding let). b=2.0 gives a's value the
        // narrowing [Decimal:@==2.0], which discharges [Decimal:@>0] — compiles and
        // runs. b=-2.0 gives [Decimal:@==-2.0], which cannot be proved to satisfy
        // [Decimal:@>0] → §1d compile error (formerly caught by runtime forcing).
        String fits = "let b = 2.0\nlet a:[Decimal:@>0] = b\na";
        for (Engine engine : Engine.values()) {
            RunResult r = run(fits, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("2.0", r.text(), engine.toString());
        }
        assertUnprovable("let b = -2.0\nlet a:[Decimal:@>0] = b\n42");
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

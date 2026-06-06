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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim rule at construction sites (the {@code ConstructionGate}),
 * three-way per constructor argument against its declared field sort:
 * <ul>
 *   <li><b>provable fit</b> — passes with NO runtime check (the proof
 *       discharged it),</li>
 *   <li><b>provable miss</b> — compile-time error (the value would be
 *       born lying),</li>
 *   <li><b>genuine overlap / undecidable</b> — compiles, with a runtime
 *       check at construction.</li>
 * </ul>
 * Pins the 2026-06-05 ruling on the {@code Lift(base:[[Int:0]|Omega])}
 * examples, on both engines.
 */
class ConstructionGateTest {

    private static final String LIFT = """
            struct Omega()
            struct Lift(base:[[Int:0]|Omega])
            """;

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
                () -> "Expected the disjoint-construction error; got: " + failed.error().text());
    }

    // --- provable miss → compile error --------------------------------------

    @Test
    void literalDisjointFromUnionField_isCompileError() {
        // 1 narrows to [Int:@==1] — disjoint from the @==0 branch AND from Omega.
        assertCompileError(LIFT + "let z = Lift(1)\n42");
    }

    @Test
    void declaredVarDisjointFromUnionField_isCompileError() {
        // A param declared [Int:@>1] provably misses both union branches.
        assertCompileError(LIFT + """
                function mk(noOverlap:[Int:@>1]):Lift -> Lift(noOverlap)
                mk(5)
                """);
    }

    @Test
    void letBoundLiteralDisjoint_isCompileError() {
        // The let's VALUE narrowing ([Int:@==5]) decides, not just the
        // declared sort — still provably disjoint.
        assertCompileError(LIFT + "let five = 5\nlet z = Lift(five)\n42");
    }

    // --- genuine overlap → compiles, runtime check --------------------------

    @Test
    void overlappingDeclaredVar_compilesAndPassesWhenValueFits() {
        // [Int:@<=1] overlaps @==0 without implying it → runtime check; 0 passes.
        String src = LIFT + """
                function mk(overlap:[Int:@<=1]):Lift -> Lift(overlap)
                mk(0).base
                """;
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("0", r.text(), engine.toString());
        }
    }

    @Test
    void overlappingDeclaredVar_failsAtConstructionWhenValueMisses() {
        // 1 satisfies [Int:@<=1] (dispatch passes) but not the field sort —
        // the construction check catches it, on both engines.
        String src = LIFT + """
                function mk(overlap:[Int:@<=1]):Lift -> Lift(overlap)
                mk(1)
                """;
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertTrue(r.isError(), () -> engine + ": expected a construction failure");
            assertTrue(r.text().contains("Construction claim violated"),
                    () -> engine + " got: " + r.text());
            assertTrue(r.text().contains("Lift.base"),
                    () -> engine + " got: " + r.text());
        }
    }

    // --- provable fit → passes with NO runtime check -------------------------

    @Test
    void provableFit_passes_withNoRuntimeCheck() throws Exception {
        RunResult r = run(LIFT + "let z = Lift(0)\nz.base", Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("0", r.text());

        // The discriminating half of the ruling: the fit was PROVEN, so the
        // record carries no runtime check at all. (A top-level let lowers to a
        // 0-arg function, so the construction lives in that function's body.)
        IrExpr.Record construction = findConstruction(
                compiled(LIFT + "let z = Lift(0)\n42"), "Lift");
        assertNotNull(construction, "expected the Lift construction");
        assertTrue(construction.runtimeChecks().isEmpty(),
                () -> "provable fit must not be stamped; got: " + construction.runtimeChecks());
    }

    @Test
    void structBranchFit_passes_withNoRuntimeCheck() throws Exception {
        RunResult r = run(LIFT + "let z = Lift(Omega())\n42", Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "got: " + r.text());

        IrExpr.Record construction = findConstruction(
                compiled(LIFT + "let z = Lift(Omega())\n42"), "Lift");
        assertNotNull(construction, "expected the Lift construction");
        assertTrue(construction.runtimeChecks().isEmpty(),
                () -> "a named-record argument fits its own branch; got: "
                        + construction.runtimeChecks());
    }

    @Test
    void overlapCase_isActuallyStamped() throws Exception {
        // The third verdict is a real stamp, not a coincidence of leniency.
        CompiledModule module = compiled(LIFT + """
                function mk(overlap:[Int:@<=1]):Lift -> Lift(overlap)
                mk(0)
                """);
        IrExpr.Record construction = findConstruction(module, "Lift");
        assertNotNull(construction, "expected the Lift construction in mk's body");
        assertTrue(construction.runtimeChecks().containsKey("base"),
                () -> "overlap must be stamped for the runtime; got: "
                        + construction.runtimeChecks());
    }

    // --- the README flagship, now honest -------------------------------------

    @Test
    void decimalRefinedField_checkedAtConstruction() {
        // Decimal predicates are outside the compile-time kernel (for now), so
        // the gate stamps a runtime check — a negative balance dies at the
        // construction site instead of living until a dispatch boundary.
        String accounts = """
                struct Account(balance:[Decimal:@>=0], rate:Decimal)
                """;
        for (Engine engine : Engine.values()) {
            RunResult ok = run(accounts + "let a = Account(100.0, 0.05)\na.rate", engine);
            assertFalse(ok.isError(), () -> engine + " got: " + ok.text());

            // a.rate forces the (lazy) top-level binding to evaluate.
            RunResult bad = run(accounts + "let a = Account(-5.0, 0.05)\na.rate", engine);
            assertTrue(bad.isError(), () -> engine + ": expected a construction failure");
            assertTrue(bad.text().contains("Construction claim violated"),
                    () -> engine + " got: " + bad.text());
        }
    }

    /** The named construction anywhere in the module — function bodies first, then main. */
    private static IrExpr.Record findConstruction(CompiledModule module, String typeName) {
        for (var fn : module.functions().values()) {
            IrExpr.Record found = firstRecord(fn.body());
            if (found != null && typeName.equals(found.typeName())) return found;
        }
        IrExpr.Record found = firstRecord(module.main());
        return found != null && typeName.equals(found.typeName()) ? found : null;
    }

    /** Depth-first search for the first named Record construction in an expression. */
    private static IrExpr.Record firstRecord(IrExpr e) {
        if (e == null) return null;
        return switch (e) {
            case IrExpr.Record r -> r.typeName() != null ? r : firstInMembers(r);
            case IrExpr.LetIn l -> {
                IrExpr.Record v = firstRecord(l.value());
                yield v != null ? v : firstRecord(l.body());
            }
            case IrExpr.BinOp op -> {
                IrExpr.Record l = firstRecord(op.left());
                yield l != null ? l : firstRecord(op.right());
            }
            case IrExpr.Call c -> {
                IrExpr.Record found = null;
                for (IrExpr a : c.args()) {
                    found = firstRecord(a);
                    if (found != null) break;
                }
                yield found;
            }
            case IrExpr.Match m -> {
                IrExpr.Record s = firstRecord(m.scrutinee());
                if (s != null) yield s;
                IrExpr.Record found = null;
                for (IrExpr.MatchBranch b : m.branches()) {
                    found = firstRecord(b.result());
                    if (found != null) break;
                }
                yield found;
            }
            case IrExpr.FieldAccess fa -> firstRecord(fa.base());
            default -> null;
        };
    }

    private static IrExpr.Record firstInMembers(IrExpr.Record r) {
        for (IrExpr v : r.members().values()) {
            IrExpr.Record found = firstRecord(v);
            if (found != null) return found;
        }
        return null;
    }
}

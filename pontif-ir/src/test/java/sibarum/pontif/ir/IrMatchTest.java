package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.defaults.DefaultRules;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrMatchTest {

    private static Simplifier simplifier() throws Exception {
        return new Simplifier(DefaultRules.production());
    }

    private static Object runInterpreter(IrModule module) throws Exception {
        Simplifier simp = simplifier();
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    private static Object runTruffle(IrModule module) throws Exception {
        Simplifier simp = simplifier();
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        TruffleProgram program = new TruffleLowering(compiler).lower(compiled);
        return program.run();
    }

    /**
     * Builds a {@link CompiledModule} by hand, skipping the compile pipeline
     * (and so the SortChecker totality gate). The runtime no-match safety net
     * is unreachable through a checked compile now that match totality is
     * enforced (principle 8: undeterminable totality requires a default arm) —
     * but it stays as defense-in-depth for hand-built IR, and these tests keep
     * it honest.
     */
    private static CompiledModule compileUnchecked(IrModule module) throws Exception {
        java.util.IdentityHashMap<IrSort, sibarum.pontif.core.types.Sort> sorts =
                new java.util.IdentityHashMap<>();
        if (module.main() instanceof IrExpr.Match m) {
            for (IrExpr.MatchBranch b : m.branches()) {
                sorts.put(b.pattern(), IrCompiler.compileSort(b.pattern()));
            }
        }
        return new CompiledModule(
                module.name(), new sibarum.pontif.core.symbolic.DispatchTable(),
                java.util.Map.of(), module.main(), sorts, java.util.Map.of());
    }

    private static Object runUncheckedInterpreter(IrModule module) throws Exception {
        return new IrInterpreter(simplifier()).eval(compileUnchecked(module));
    }

    private static Object runUncheckedTruffle(IrModule module) throws Exception {
        Simplifier simp = simplifier();
        return new TruffleLowering(new IrCompiler(simp)).lower(compileUnchecked(module)).run();
    }

    // --- Pattern sorts reused across tests ---

    private static IrSort positive() {
        return IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
    }

    private static IrSort negative() {
        return IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.LT, IrExpr.self(), IrExpr.lit(0)));
    }

    private static IrSort zero() {
        return IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.EQ, IrExpr.self(), IrExpr.lit(0)));
    }

    private static IrSort anyInt() {
        return IrSort.named("Int");
    }

    // --- Interpreter path: branch selection ---

    @Test
    void interpreter_positiveLiteral_selectsPositiveBranch() throws Exception {
        IrExpr match = IrExpr.match(IrExpr.lit(5), List.of(
                IrExpr.matchBranch(positive(), IrExpr.lit(42)),
                IrExpr.matchBranch(anyInt(), IrExpr.lit(-1))));
        IrModule module = new IrModule("m", List.of(), match);
        assertEquals(42L, runInterpreter(module));
    }

    @Test
    void interpreter_zeroLiteral_selectsZeroBranchBetweenPositiveAndNegative() throws Exception {
        IrExpr match = IrExpr.match(IrExpr.lit(0), List.of(
                IrExpr.matchBranch(positive(), IrExpr.lit(1)),
                IrExpr.matchBranch(zero(), IrExpr.lit(42)),
                IrExpr.matchBranch(negative(), IrExpr.lit(-1))));
        IrModule module = new IrModule("m", List.of(), match);
        assertEquals(42L, runInterpreter(module));
    }

    @Test
    void interpreter_firstMatchSemantics_takesFirstOfMultipleAcceptingPatterns() throws Exception {
        // 5 satisfies both positive and "non-negative"; the first listed wins.
        IrSort nonNegative = IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(0)));
        IrExpr match = IrExpr.match(IrExpr.lit(5), List.of(
                IrExpr.matchBranch(positive(), IrExpr.lit(1)),
                IrExpr.matchBranch(nonNegative, IrExpr.lit(2)),
                // catch-all completes the cover (totality is enforced now);
                // never taken — 5 hits the first accepting arm above.
                IrExpr.matchBranch(anyInt(), IrExpr.lit(3))));
        IrModule module = new IrModule("m", List.of(), match);
        assertEquals(1L, runInterpreter(module));
    }

    // --- Truffle lowering path: same behaviour ---

    @Test
    void truffle_positiveLiteral_selectsPositiveBranch() throws Exception {
        IrExpr match = IrExpr.match(IrExpr.lit(5), List.of(
                IrExpr.matchBranch(positive(), IrExpr.lit(42)),
                IrExpr.matchBranch(anyInt(), IrExpr.lit(-1))));
        IrModule module = new IrModule("m", List.of(), match);
        assertEquals(42L, runTruffle(module));
    }

    @Test
    void truffle_zeroLiteral_selectsZeroBranchBetweenPositiveAndNegative() throws Exception {
        IrExpr match = IrExpr.match(IrExpr.lit(0), List.of(
                IrExpr.matchBranch(positive(), IrExpr.lit(1)),
                IrExpr.matchBranch(zero(), IrExpr.lit(42)),
                IrExpr.matchBranch(negative(), IrExpr.lit(-1))));
        IrModule module = new IrModule("m", List.of(), match);
        assertEquals(42L, runTruffle(module));
    }

    @Test
    void truffle_branchResultEvaluatesInScopeOfSurroundingBindings() throws Exception {
        // let x = 6 in match x with | zero -> 0 | positive -> x + 1
        IrExpr match = IrExpr.match(IrExpr.var("x"), List.of(
                IrExpr.matchBranch(zero(), IrExpr.lit(0)),
                IrExpr.matchBranch(positive(),
                        IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1))),
                // negative arm completes the cover (x is 6 → never taken); the
                // match must be total now that SortChecker enforces principle 8.
                IrExpr.matchBranch(negative(), IrExpr.lit(0))));
        IrExpr program = IrExpr.letIn("x", anyInt(), IrExpr.lit(6), match);
        IrModule module = new IrModule("m", List.of(), program);
        assertEquals(7L, runTruffle(module));
    }

    // --- Origin: no-match error carries the IrExpr.Match origin ---

    @Test
    void interpreter_noMatch_throwsWithMatchOriginInMessage() throws Exception {
        Origin matchSite = Origin.at("test.ptf", 17, 4);
        IrExpr match = new IrExpr.Match(IrExpr.lit(-3),
                List.of(IrExpr.matchBranch(positive(), IrExpr.lit(99))),
                matchSite);
        IrModule module = new IrModule("m", List.of(), match);

        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runUncheckedInterpreter(module));

        assertEquals(matchSite, ex.origin());
        assertTrue(ex.getMessage().contains("test.ptf:17:4"),
                "error should include origin; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("No match branch"),
                "error should explain the failure; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("-3"),
                "error should cite the offending value; got: " + ex.getMessage());
    }

    @Test
    void truffle_noMatch_throwsWithMatchOriginInMessage() throws Exception {
        Origin matchSite = Origin.span("test.ptf", 17, 4, 19, 10);
        IrExpr match = new IrExpr.Match(IrExpr.lit(-3),
                List.of(IrExpr.matchBranch(positive(), IrExpr.lit(99))),
                matchSite);
        IrModule module = new IrModule("m", List.of(), match);

        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runUncheckedTruffle(module));

        assertEquals(matchSite, ex.origin());
        assertTrue(ex.getMessage().contains("test.ptf:17:4"),
                "error should include origin; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("No match branch"),
                "error should explain the failure; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("-3"),
                "error should cite the offending value; got: " + ex.getMessage());
    }

    // --- Origin: nested exception inside a branch result is re-tagged with the match origin
    //     only when the inner exception had no origin of its own (so deeper origins win).

    @Test
    void interpreter_innerExceptionWithOwnOrigin_isNotOverwrittenByMatchOrigin() throws Exception {
        // Branch result triggers a runtime failure (field access on a literal —
        // the base isn't a record). The inner exception's origin must win
        // over the outer match's origin.
        //
        // (Previously this test used a call to an undeclared function, but
        // that's now caught at compile time by SortChecker. A runtime-only
        // failure is the right trigger for testing runtime origin
        // propagation.)
        Origin matchSite = Origin.at("outer.ptf", 5, 5);
        Origin fieldSite = Origin.at("inner.ptf", 100, 1);
        IrExpr badFieldAccess = new IrExpr.FieldAccess(IrExpr.lit(5), "x", fieldSite);
        IrExpr match = new IrExpr.Match(IrExpr.lit(5),
                List.of(IrExpr.matchBranch(positive(), badFieldAccess),
                        // catch-all for totality; 5 takes the positive arm.
                        IrExpr.matchBranch(anyInt(), IrExpr.lit(0))),
                matchSite);
        IrModule module = new IrModule("m", List.of(), match);

        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runInterpreter(module));

        assertEquals(fieldSite, ex.origin(),
                "inner origin should win over outer match origin when present");
        assertTrue(ex.getMessage().contains("inner.ptf:100:1"),
                "message should carry inner origin; got: " + ex.getMessage());
    }

    // --- Origin: no origin set => message has no bracket prefix ---

    @Test
    void interpreter_noOrigin_noMatchMessageHasNoBracketPrefix() throws Exception {
        IrExpr match = IrExpr.match(IrExpr.lit(-3),
                List.of(IrExpr.matchBranch(positive(), IrExpr.lit(99))));
        IrModule module = new IrModule("m", List.of(), match);

        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runUncheckedInterpreter(module));

        assertFalse(ex.origin().isPresent());
        assertFalse(ex.getMessage().startsWith("["),
                "message should not start with bracket when origin is absent; got: " + ex.getMessage());
    }

    // --- Factory / validation ---

    @Test
    void match_factoryDefaultsToOriginNONE() throws Exception {
        IrExpr.Match m = (IrExpr.Match) IrExpr.match(IrExpr.lit(0),
                List.of(IrExpr.matchBranch(zero(), IrExpr.lit(1))));
        assertEquals(Origin.NONE, m.origin());
    }

    @Test
    void match_emptyBranches_isRejectedAtConstruction() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> IrExpr.match(IrExpr.lit(0), List.of()));
    }
}

package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
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

    private static List<RewriteRule> defaultRules() {
        List<RewriteRule> rules = new ArrayList<>();
        rules.add(cmpLitLit());
        return rules;
    }

    private static RewriteRule cmpLitLit() {
        return (expr, simp) -> {
            if (expr instanceof SymExpr.Cmp(SymExpr.Lit l, SymExpr.CmpOp op, SymExpr.Lit r)) {
                boolean truth = switch (op) {
                    case LT -> l.value() < r.value();
                    case LE -> l.value() <= r.value();
                    case GT -> l.value() > r.value();
                    case GE -> l.value() >= r.value();
                    case EQ -> l.value() == r.value();
                    case NE -> l.value() != r.value();
                };
                return Optional.of(SymExpr.bool(truth));
            }
            return Optional.empty();
        };
    }

    private static Simplifier simplifier() throws Exception {
        return new Simplifier(defaultRules());
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
                IrExpr.matchBranch(nonNegative, IrExpr.lit(2))));
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
                        IrExpr.binOp(IrExpr.Op.ADD, IrExpr.var("x"), IrExpr.lit(1)))));
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
                () -> runInterpreter(module));

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
                () -> runTruffle(module));

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
        // Branch result calls a missing function; that call site has its own origin.
        // The dispatch failure should bubble up with its own origin intact, not the match's.
        Origin matchSite = Origin.at("outer.ptf", 5, 5);
        Origin callSite = Origin.at("inner.ptf", 100, 1);
        IrExpr badCall = new IrExpr.Call("doesNotExist", List.of(IrExpr.lit(1)), callSite);
        IrExpr match = new IrExpr.Match(IrExpr.lit(5),
                List.of(IrExpr.matchBranch(positive(), badCall)),
                matchSite);
        IrModule module = new IrModule("m", List.of(), match);

        RuntimeCheckException ex = assertThrows(
                RuntimeCheckException.class,
                () -> runInterpreter(module));

        assertEquals(callSite, ex.origin(),
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
                () -> runInterpreter(module));

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

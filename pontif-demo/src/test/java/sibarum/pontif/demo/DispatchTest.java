package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.CompiledCall;
import sibarum.pontif.core.symbolic.Context;
import sibarum.pontif.core.symbolic.DispatchResult;
import sibarum.pontif.core.symbolic.DispatchTable;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.core.symbolic.ArithmeticRules;
import sibarum.pontif.core.symbolic.HypothesisRules;
import sibarum.pontif.core.symbolic.RefinementRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(allRules());

    private static List<RewriteRule> allRules() {
        List<RewriteRule> all = new ArrayList<>();
        all.addAll(HypothesisRules.all());
        all.addAll(RefinementRules.all());
        all.addAll(ArithmeticRules.all());
        return all;
    }

    private static final Sort POSITIVE = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GT, SymExpr.lit(0)));
    private static final Sort AT_LEAST_ONE = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(1)));
    private static final Sort NON_NEGATIVE = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.GE, SymExpr.lit(0)));
    private static final Sort EXACTLY_ZERO = Sort.refined("Int",
            SymExpr.cmp(SymExpr.self(), SymExpr.CmpOp.EQ, SymExpr.lit(0)));
    private static final Sort ANY_INT = Sort.of("Int");

    // --- Trivial cases ---

    @Test
    void emptyTable_resolvingAnything_yieldsNoMatch() throws Exception {
        DispatchTable table = new DispatchTable();
        DispatchResult r = table.resolve("anything", List.of(SymExpr.lit(5)), SIMPLIFIER);
        assertInstanceOf(DispatchResult.NoMatch.class, r);
    }

    @Test
    void singleDeclaration_matchingArgs_resolves() throws Exception {
        FunctionDecl id = FunctionDecl.declaration("id",
                List.of(new FunctionDecl.Param("x", ANY_INT)), ANY_INT);
        DispatchTable table = new DispatchTable().register(id);
        DispatchResult r = table.resolve("id", List.of(SymExpr.lit(42)), SIMPLIFIER);
        assertInstanceOf(DispatchResult.Resolved.class, r);
        assertEquals(id, ((DispatchResult.Resolved) r).decl());
    }

    @Test
    void singleDeclaration_failingArgs_yieldsNoMatch() throws Exception {
        FunctionDecl positiveOnly = FunctionDecl.declaration("pos",
                List.of(new FunctionDecl.Param("x", POSITIVE)), POSITIVE);
        DispatchTable table = new DispatchTable().register(positiveOnly);
        DispatchResult r = table.resolve("pos", List.of(SymExpr.lit(-3)), SIMPLIFIER);
        assertInstanceOf(DispatchResult.NoMatch.class, r);
    }

    // --- Specificity ---

    @Test
    void moreSpecificOverloadWinsWhenBothMatch() throws Exception {
        // pow(b: Int[@>0]) and pow(b: Int) — call with 5 matches both; @>0 is more specific
        FunctionDecl tight = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", POSITIVE)), POSITIVE);
        FunctionDecl general = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", ANY_INT)), ANY_INT);
        DispatchTable table = new DispatchTable().register(tight).register(general);

        DispatchResult r = table.resolve("pow", List.of(SymExpr.lit(5)), SIMPLIFIER);
        assertInstanceOf(DispatchResult.Resolved.class, r);
        assertEquals(tight, ((DispatchResult.Resolved) r).decl());
    }

    @Test
    void registrationOrderDoesNotAffectSpecificity() throws Exception {
        // Register general first, then specific — still picks specific
        FunctionDecl tight = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", POSITIVE)), POSITIVE);
        FunctionDecl general = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", ANY_INT)), ANY_INT);
        DispatchTable table = new DispatchTable().register(general).register(tight);

        DispatchResult r = table.resolve("pow", List.of(SymExpr.lit(5)), SIMPLIFIER);
        assertEquals(tight, ((DispatchResult.Resolved) r).decl());
    }

    @Test
    void specificFails_fallsThroughToGeneral() throws Exception {
        // pow(b: Int[@>0]) and pow(b: Int); call with -3 fails the specific, takes the general
        FunctionDecl tight = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", POSITIVE)), POSITIVE);
        FunctionDecl general = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", ANY_INT)), ANY_INT);
        DispatchTable table = new DispatchTable().register(tight).register(general);

        DispatchResult r = table.resolve("pow", List.of(SymExpr.lit(-3)), SIMPLIFIER);
        assertInstanceOf(DispatchResult.Resolved.class, r);
        assertEquals(general, ((DispatchResult.Resolved) r).decl());
    }

    @Test
    void transitiveSpecificity_picksTheTightest() throws Exception {
        // pow(b: Int[@>=1]) ⊂ pow(b: Int[@>=0]) ⊂ pow(b: Int)
        FunctionDecl tightest = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", AT_LEAST_ONE)), AT_LEAST_ONE);
        FunctionDecl middle = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", NON_NEGATIVE)), NON_NEGATIVE);
        FunctionDecl loose = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", ANY_INT)), ANY_INT);
        DispatchTable table = new DispatchTable().register(middle).register(loose).register(tightest);

        // 5 satisfies all three; should pick the tightest
        DispatchResult r = table.resolve("pow", List.of(SymExpr.lit(5)), SIMPLIFIER);
        assertEquals(tightest, ((DispatchResult.Resolved) r).decl());
    }

    // --- Ambiguity ---

    @Test
    void incomparableOverloads_bothMatching_yieldAmbiguous() throws Exception {
        // pow(b: Int[@>0], x: Int) vs pow(b: Int, x: Int[@>0]) — neither dominates
        FunctionDecl tightLeft = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", POSITIVE),
                        new FunctionDecl.Param("x", ANY_INT)), POSITIVE);
        FunctionDecl tightRight = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", ANY_INT),
                        new FunctionDecl.Param("x", POSITIVE)), POSITIVE);
        DispatchTable table = new DispatchTable().register(tightLeft).register(tightRight);

        // pow(5, 5) — both overloads apply; neither is strictly more specific
        DispatchResult r = table.resolve("pow", List.of(SymExpr.lit(5), SymExpr.lit(5)), SIMPLIFIER);
        assertInstanceOf(DispatchResult.Ambiguous.class, r);
        DispatchResult.Ambiguous amb = (DispatchResult.Ambiguous) r;
        assertEquals(2, amb.candidates().size());
        assertTrue(amb.candidates().contains(tightLeft));
        assertTrue(amb.candidates().contains(tightRight));
    }

    @Test
    void incomparableOverloads_onlyOneMatching_resolves() throws Exception {
        // Same as above but pow(-3, 5): tightLeft fails (b<0); tightRight succeeds
        FunctionDecl tightLeft = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", POSITIVE),
                        new FunctionDecl.Param("x", ANY_INT)), POSITIVE);
        FunctionDecl tightRight = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", ANY_INT),
                        new FunctionDecl.Param("x", POSITIVE)), POSITIVE);
        DispatchTable table = new DispatchTable().register(tightLeft).register(tightRight);

        DispatchResult r = table.resolve("pow", List.of(SymExpr.lit(-3), SymExpr.lit(5)), SIMPLIFIER);
        assertInstanceOf(DispatchResult.Resolved.class, r);
        assertEquals(tightRight, ((DispatchResult.Resolved) r).decl());
    }

    // --- Arity / name filtering ---

    @Test
    void differentArities_onlyMatchingArityConsidered() throws Exception {
        FunctionDecl unary = FunctionDecl.declaration("f",
                List.of(new FunctionDecl.Param("x", ANY_INT)), ANY_INT);
        FunctionDecl binary = FunctionDecl.declaration("f",
                List.of(new FunctionDecl.Param("x", ANY_INT),
                        new FunctionDecl.Param("y", ANY_INT)), ANY_INT);
        DispatchTable table = new DispatchTable().register(unary).register(binary);

        DispatchResult r1 = table.resolve("f", List.of(SymExpr.lit(5)), SIMPLIFIER);
        assertEquals(unary, ((DispatchResult.Resolved) r1).decl());

        DispatchResult r2 = table.resolve("f", List.of(SymExpr.lit(5), SymExpr.lit(7)), SIMPLIFIER);
        assertEquals(binary, ((DispatchResult.Resolved) r2).decl());
    }

    @Test
    void unrelatedName_yieldsNoMatch() throws Exception {
        FunctionDecl decl = FunctionDecl.declaration("foo",
                List.of(new FunctionDecl.Param("x", ANY_INT)), ANY_INT);
        DispatchTable table = new DispatchTable().register(decl);
        DispatchResult r = table.resolve("bar", List.of(SymExpr.lit(5)), SIMPLIFIER);
        assertInstanceOf(DispatchResult.NoMatch.class, r);
    }

    // --- Symbolic arguments ---

    @Test
    void symbolicArg_withoutHypothesis_resolvesToMostGeneral() throws Exception {
        FunctionDecl positiveOnly = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", POSITIVE)), POSITIVE);
        FunctionDecl general = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", ANY_INT)), ANY_INT);
        DispatchTable table = new DispatchTable().register(positiveOnly).register(general);

        // Var("x") — undecided whether x > 0
        DispatchResult r = table.resolve("pow", List.of(SymExpr.var("x")), SIMPLIFIER);
        assertInstanceOf(DispatchResult.Resolved.class, r);
        // The specific overload requires a runtime check; the general one needs none.
        // With both viable, the specific is more specific — so it wins, with a deferred check.
        DispatchResult.Resolved resolved = (DispatchResult.Resolved) r;
        assertEquals(positiveOnly, resolved.decl());
        assertEquals(1, resolved.call().deferredChecks().size());
    }

    @Test
    void symbolicArg_withHypothesis_resolvesStatically() throws Exception {
        FunctionDecl positiveOnly = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", POSITIVE)), POSITIVE);
        FunctionDecl general = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", ANY_INT)), ANY_INT);
        DispatchTable table = new DispatchTable().register(positiveOnly).register(general);

        // With x > 0 in context, the specific overload is statically applicable; no runtime check
        Simplifier withHypothesis = SIMPLIFIER.withContext(
                Context.of(SymExpr.cmp(SymExpr.var("x"), SymExpr.CmpOp.GT, SymExpr.lit(0))));
        DispatchResult r = table.resolve("pow", List.of(SymExpr.var("x")), withHypothesis);
        DispatchResult.Resolved resolved = (DispatchResult.Resolved) r;
        assertEquals(positiveOnly, resolved.decl());
        assertEquals(0, resolved.call().deferredChecks().size(),
                "with x>0 hypothesis, all preconditions discharged statically");
    }

    // --- Composes with runtime checks ---

    @Test
    void dispatchedCall_executesAtRuntimeWithBoundValue() throws Exception {
        FunctionDecl positiveOnly = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", POSITIVE)), POSITIVE);
        DispatchTable table = new DispatchTable().register(positiveOnly);

        DispatchResult r = table.resolve("pow", List.of(SymExpr.var("x")), SIMPLIFIER);
        CompiledCall call = ((DispatchResult.Resolved) r).call();

        // At runtime, x = 7 → check passes
        call.executeChecks(Map.of("x", SymExpr.lit(7)), SIMPLIFIER);
    }

    // --- Singleton dispatch ---

    @Test
    void singletonSortPrefersOverGeneral() throws Exception {
        // pow(b: Int[@=0]) — singleton type
        // pow(b: Int) — general
        // Call pow(0): both match; singleton is more specific
        FunctionDecl zeroCase = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", EXACTLY_ZERO)), EXACTLY_ZERO);
        FunctionDecl general = FunctionDecl.declaration("pow",
                List.of(new FunctionDecl.Param("b", ANY_INT)), ANY_INT);
        DispatchTable table = new DispatchTable().register(general).register(zeroCase);

        DispatchResult r = table.resolve("pow", List.of(SymExpr.lit(0)), SIMPLIFIER);
        assertEquals(zeroCase, ((DispatchResult.Resolved) r).decl());
    }

    // --- The headline demo ---

    @Test
    void headlineDemo_powWithTwoOverloads() throws Exception {
        // pow(b: Int[@>0], x: Int[@>=1]) : Int[@>0]   — the strong specification
        FunctionDecl tight = FunctionDecl.declaration("pow",
                List.of(
                        new FunctionDecl.Param("b", POSITIVE),
                        new FunctionDecl.Param("x", AT_LEAST_ONE)),
                POSITIVE);
        // pow(b: Int, x: Int) : Int  — fallback
        FunctionDecl general = FunctionDecl.declaration("pow",
                List.of(
                        new FunctionDecl.Param("b", ANY_INT),
                        new FunctionDecl.Param("x", ANY_INT)),
                ANY_INT);
        DispatchTable table = new DispatchTable().register(tight).register(general);

        // pow(5, 2) — both match; tight is strictly more specific
        DispatchResult r1 = table.resolve("pow",
                List.of(SymExpr.lit(5), SymExpr.lit(2)), SIMPLIFIER);
        assertEquals(tight, ((DispatchResult.Resolved) r1).decl(),
                "5 satisfies @>0 and 2 satisfies @>=1 — the strong overload wins");

        // pow(-3, 2) — tight fails on b; only general matches
        DispatchResult r2 = table.resolve("pow",
                List.of(SymExpr.lit(-3), SymExpr.lit(2)), SIMPLIFIER);
        assertEquals(general, ((DispatchResult.Resolved) r2).decl(),
                "-3 violates the @>0 precondition of the strong overload; fall through");

        // pow(5, 0) — tight fails on x; only general matches
        DispatchResult r3 = table.resolve("pow",
                List.of(SymExpr.lit(5), SymExpr.lit(0)), SIMPLIFIER);
        assertEquals(general, ((DispatchResult.Resolved) r3).decl(),
                "0 violates @>=1; fall through to general");
    }
}

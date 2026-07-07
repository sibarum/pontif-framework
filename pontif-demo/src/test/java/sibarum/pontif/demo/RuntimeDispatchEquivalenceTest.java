package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;

import sibarum.pontif.core.symbolic.DispatchResult;
import sibarum.pontif.core.symbolic.DispatchTable;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.defaults.DefaultRules;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.InferenceContext;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.types.DispatchQuery;
import sibarum.pontif.types.TypeSystem;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * WAR(dispatch-query) slice 4 — the executable proof that <b>runtime dispatch is the fully-determined
 * dispatch query</b>. The ratified model (2026-07-07; docs/dispatch-unification.md "Execution model")
 * says static and dynamic dispatch are ONE satisfiability question over a constraint set, evaluated at
 * different determinacy: the runtime {@link DispatchTable} is that same query with <em>every argument
 * pinned to its constant value</em>.
 *
 * <p><b>Why they must agree.</b> On a constant pin, the static side's match test
 * {@code Refinements.imply(singleton, param)} ("is the value-set {v} a subset of the param sort") and the
 * runtime side's {@code Refinements.satisfies(v, param)} ("does the value v inhabit the param sort") ask
 * the same thing; and the most-specific tiebreak is the <em>same</em> algorithm in both
 * ({@code Refinements.imply} over the param sorts). So {@link DispatchTable#resolve} on a concrete value
 * and {@link TypeSystem#dispatch} on that value's singleton sort must resolve to the same overload — or
 * both reject (runtime {@code NoMatch} ⇔ static {@code Unsatisfiable}, the provable misroute).
 *
 * <p>This is the runtime end of the determinacy gradient made a <em>checked</em> property (a regression
 * meter: a future change to either resolver's specificity/match logic that breaks the correspondence
 * fails here), per the ratified caveat "provably the same query on constant inputs, not literally the
 * same code path — a justified refinement": the fast value-matching hot path is untouched.
 *
 * <p><b>Scope.</b> The provable core — concrete free-function overloads over primitive refinements,
 * resolved names, constant args — where the value→singleton pin is crisp and the kernel is decisive. Two
 * runtime-only refinements are deliberately out of scope (they are the runtime table abstaining beyond
 * what the static query commits to, never a disagreement): trait satisfaction via the runtime
 * {@code TraitRegistry} ({@code DispatchTable.enforceTraitParams}), and the {@code Trait.method →
 * ConcreteType.method} runtime fallback (which corresponds to slice-3 <em>name</em>-routing, not the
 * {@code forCall} refinement path exercised here). Genuine ambiguity is also out of scope: a compiled
 * module's overloads have already passed {@code OverloadOverlap}, so a provably-ambiguous overload set
 * cannot reach the runtime table.
 */
class RuntimeDispatchEquivalenceTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(DefaultRules.production());

    private static final IrSort INT = IrSort.named("Int");
    private static final IrSort POSITIVE = IrSort.refined("Int",
            IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
    private static final IrSort NON_NEGATIVE = IrSort.refined("Int",
            IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(0)));
    private static final IrSort AT_LEAST_ONE = IrSort.refined("Int",
            IrExpr.binOp(IrExpr.Op.GE, IrExpr.self(), IrExpr.lit(1)));

    /** A 1-param overload. The body returns a plain {@code Int} so the return-refinement gate is a
     *  no-op; the PARAM sort is the dispatch-distinguishing feature this test compares. */
    private static IrStmt.FunctionDecl decl(String name, IrSort param) {
        return IrStmt.functionDecl(name, List.of(new IrParam("x", param)), INT, IrExpr.lit(0));
    }

    /**
     * The differential harness: compile the overload set once (so the runtime table is the REAL one the
     * compiler builds, not a hand-mock), then assert the runtime resolution over the concrete value and
     * the static query over that value's constant pin agree.
     */
    private void assertAgree(String name, List<IrStmt.FunctionDecl> overloads, long arg) throws Exception {
        IrModule module = new IrModule("equiv", new ArrayList<>(overloads), IrExpr.lit(0));
        CompiledModule compiled = new IrCompiler(SIMPLIFIER).compile(module);
        DispatchTable table = compiled.dispatch();
        InferenceContext ctx = InferenceContext.fromModule(module);

        DispatchResult runtime = table.resolve(name, List.of(SymExpr.lit(arg)), SIMPLIFIER);
        // infer(lit) IS the constant pin ([Int:@==arg]) — the fully-determined instance of the query.
        IrSort pin = TypeSystem.standard().infer(IrExpr.lit((int) arg), ctx);
        sibarum.pontif.types.DispatchResult statik =
                TypeSystem.standard().dispatch(DispatchQuery.forCall(name, List.of(pin)), ctx);

        String where = name + "(" + arg + ")";
        switch (runtime) {
            case DispatchResult.Resolved rr -> {
                sibarum.pontif.types.DispatchResult.Resolved sr = assertInstanceOf(
                        sibarum.pontif.types.DispatchResult.Resolved.class, statik,
                        "runtime resolved but static did not, for " + where);
                assertEquals(coreParams(rr.decl()), coreParams(sr.target()),
                        "runtime and static picked different overloads for " + where);
            }
            case DispatchResult.NoMatch nm -> assertInstanceOf(
                    sibarum.pontif.types.DispatchResult.Unsatisfiable.class, statik,
                    "runtime rejected but static did not judge the call unsatisfiable, for " + where);
            case DispatchResult.Ambiguous amb -> throw new AssertionError(
                    "OverloadOverlap precludes provably-ambiguous compiled overload sets; "
                            + "a runtime ambiguity means the scenario is out of this test's scope: " + where);
        }
    }

    /** The picked overload's param sorts, as core {@link Sort}s — the cross-boundary identity. */
    private static List<Sort> coreParams(sibarum.pontif.core.symbolic.FunctionDecl decl) {
        List<Sort> out = new ArrayList<>();
        for (sibarum.pontif.core.symbolic.FunctionDecl.Param p : decl.parameters()) out.add(p.sort());
        return out;
    }

    private static List<Sort> coreParams(IrStmt.FunctionDecl decl) throws Exception {
        List<Sort> out = new ArrayList<>();
        for (IrParam p : decl.params()) out.add(IrCompiler.compileSort(p.sort()));
        return out;
    }

    // --- runtime resolve ⇔ static dispatch on the constant pin ---------------

    @Test
    void singleOverload_argSatisfies_bothResolveSame() throws Exception {
        assertAgree("f", List.of(decl("f", INT)), 5);
    }

    @Test
    void singleOverload_argProvablyMisses_bothReject() throws Exception {
        // f(x:[Int:@>0]) called with -3: runtime NoMatch ⇔ static Unsatisfiable (the provable misroute).
        assertAgree("f", List.of(decl("f", POSITIVE)), -3);
    }

    @Test
    void tightVsGeneral_argHitsTight_bothPickTight() throws Exception {
        // 5 satisfies both f(x:[Int:@>0]) and f(x:Int); the refined one is more specific.
        assertAgree("f", List.of(decl("f", POSITIVE), decl("f", INT)), 5);
    }

    @Test
    void tightVsGeneral_argMissesTight_bothPickGeneral() throws Exception {
        // -3 fails the specific overload; both fall through to f(x:Int).
        assertAgree("f", List.of(decl("f", POSITIVE), decl("f", INT)), -3);
    }

    @Test
    void transitiveSpecificity_bothPickTightest() throws Exception {
        // [Int:@>=1] ⊂ [Int:@>=0] ⊂ Int; 5 satisfies all three, both pick the tightest.
        assertAgree("f",
                List.of(decl("f", AT_LEAST_ONE), decl("f", NON_NEGATIVE), decl("f", INT)), 5);
    }
}

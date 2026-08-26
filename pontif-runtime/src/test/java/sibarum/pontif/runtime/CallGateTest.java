package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The call gate (WAR(dependent-sorts) slice 2 step (c)): the dual of the return
 * gate. {@code PontifCompiler} rejects a call whose arguments PROVABLY fail every
 * overload's parameter refinements, and accepts the ones that route or are merely
 * undecided. Like {@link ReturnGateTest}, these assert a real rejection AND a real
 * acceptance through the production compile path — a vacuously-abstaining gate
 * would also leave the suite green.
 */
class CallGateTest {

    private final PontifCompiler compiler = new PontifCompiler();

    @Test
    void rejectsProvablyFailingCall() {
        // h(-3) against [Int:@>0]: -3 provably violates @>0 → no overload routes.
        CompileResult r = compiler.compile(
                "module m\nfunction h(x:[Int:@>0]):Int -> x\nh(-3)", "h.ptf");
        CompileResult.Failed f =
                assertInstanceOf(CompileResult.Failed.class, r, "expected a compile rejection");
        assertTrue(f.error().text().contains("Cannot prove the call to"),
                () -> "unexpected gate message: " + f.error().text());
        assertTrue(f.error().text().contains("'h'"),
                () -> "message should name the offending call: " + f.error().text());
    }

    @Test
    void rejectsDependentParamCall_afterSiblingSubstitution() {
        // g(x:Int, i:[Int:@<x]) called g(5, 7): substitute x↦5 ⇒ i:[Int:@<5]; 7 ⊀ 5.
        // The §0 dependent hole — only provable once the sibling value is pinned.
        CompileResult r = compiler.compile(
                "module m\nfunction g(x:Int, i:[Int:@<x]):Int -> i\ng(5, 7)", "g.ptf");
        CompileResult.Failed f =
                assertInstanceOf(CompileResult.Failed.class, r, "expected a compile rejection");
        assertTrue(f.error().text().contains("'g'"),
                () -> "message should name the offending call: " + f.error().text());
    }

    @Test
    void acceptsProvablyRoutingCall() {
        // h(5): 5 satisfies @>0 → routes → compiles.
        CompileResult r = compiler.compile(
                "module m\nfunction h(x:[Int:@>0]):Int -> x\nh(5)", "h-ok.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "a provably-routing call should compile; got " + r);
    }

    @Test
    void acceptsDependentParamCall_whenSatisfied() {
        // g(5, 3): x↦5 ⇒ i:[Int:@<5]; 3 < 5 → routes → compiles.
        CompileResult r = compiler.compile(
                "module m\nfunction g(x:Int, i:[Int:@<x]):Int -> i\ng(5, 3)", "g-ok.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "a satisfied dependent call should compile; got " + r);
    }

    @Test
    void acceptsRecursiveDecrementedCall() {
        // Single-overload inductive recursion: fac(n-1) under the [@>0] arm — n-1 is
        // bounded to [Int:@>=0] from the hypothesis n>0 (inferArg), which satisfies
        // the param [Int:@>=0]. The gate must PROVE this routes, not reject it.
        CompileResult r = compiler.compile("""
                module m
                function fac(n:[Int:@>=0]):[Int:@>=1] -> match n {
                  [@==0] -> 1
                  [@>0]  -> n * fac(n-1)
                }
                fac(5)""", "fac-rec.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "single-overload inductive recursion must compile; got " + r);
    }

    @Test
    void acceptsMultiOverloadRecursion() {
        // Multi-overload recursion: sum(n-1) bounded to [Int:@>=0] STRADDLES the
        // {[Int:0],[Int:@>0]} overloads — not a subset of either, but disjoint from
        // neither. Must abstain (RESIDUAL), never reject (the regression we fixed).
        CompileResult r = compiler.compile("""
                module m
                function sum(n:[Int:0]):Int   -> 0
                function sum(n:[Int:@>0]):Int -> n + sum(n-1)
                sum(5)""", "sum-rec.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "multi-overload recursion must compile (abstain, not reject); got " + r);
    }

    @Test
    void unionAliasParam_memberArg_isNotWronglyRejected() {
        // firstUnprovableCall resolves type aliases (AliasResolver.resolve) BEFORE
        // walking the gate. Without that step the param sort `U` reaches the gate as a
        // bare Named the refinement kernel can't relate to member A, so f(A()) reads as
        // a provable misroute (imply(A, U) → Failed) and is WRONGLY rejected. With
        // resolution, U ↦ [A|B], A is a member → the call routes → compiles.
        //
        // This is the inverse failure mode of the abstain tests above: here the gate
        // must NOT fire on a call that genuinely routes. Reverting the alias-resolve
        // line turns this red with a spurious "Cannot prove the call to 'f'".
        CompileResult r = compiler.compile("""
                module m
                struct A()
                struct B()
                let U:Type[A|B]
                function f(x:U):Int -> 0
                f(A())""", "alias.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "a member arg at a union-alias param must route, not be rejected; got: "
                        + (r instanceof CompileResult.Failed f ? f.error().text() : r));
    }

    @Test
    void abstainsOnResidualCall() {
        // pass(n) where n:Int is unrefined: the arg can't be proven to satisfy
        // @>0, but it isn't provably disjoint either → RESIDUAL → the gate
        // abstains (no rejection). Pins that the gate fires ONLY on provable
        // failure, not on every unproven call (the no-lie sweep is a later ruling).
        CompileResult r = compiler.compile("""
                module m
                function pass(x:[Int:@>0]):Int -> x
                function caller(n:Int):Int -> pass(n)
                caller(5)""", "residual.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "a residual (undecided) call must not be rejected yet; got " + r);
    }

    // ---------------------------------------------------------------- the wildcard is not a name

    /**
     * A parameter written {@code x:_} accepts anything, including a value whose sort reaches the
     * kernel as a bare nominal name.
     *
     * <p>It used not to. {@code _} was compared as a nominal type whose name happened to be
     * {@code "_"}, so {@code imply(T, _)} reported Failed — which by that method's contract means
     * PROVABLY DISJOINT — and the gate turned that into a compile error. `_` excludes nothing, so
     * the claim was a lie and the rejection was of valid code.
     *
     * <p>What made it hard to see is that it only fired when the argument's sort arrived as a bare
     * {@code Named}. Most arguments infer to something structural, and a structural-vs-name pairing
     * is merely undecided, so the gate abstained and the bug stayed hidden. The three below are the
     * ways a bare name shows up.
     */
    @Test
    void acceptsAnyArgumentAtAWildcardParam_declaredStructParam() {
        // The plainest one: a value typed by a declared struct, arriving through a parameter.
        CompileResult r = compiler.compile("""
                module m
                struct T(a:Int)
                function w(r:_):Int -> 0
                function pass(t:T):Int -> w(t)
                pass(T(1))""", "wildcard-named.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "`_` accepts every sort; got: "
                        + (r instanceof CompileResult.Failed f ? f.error().text() : r));
    }

    @Test
    void acceptsAnyArgumentAtAWildcardParam_allStringFieldedStruct() {
        // The one that surfaced it (docs/anybox.md): a struct whose fields are all String infers to
        // a bare name rather than a shape, where the same struct with an Int field does not. The
        // difference is inference's, and it should not decide whether `_` accepts the value.
        CompileResult r = compiler.compile("""
                module m
                struct T(a:String, b:String)
                function w(r:_):Int -> 0
                w(T("x", "y"))""", "wildcard-strings.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "`_` accepts every sort; got: "
                        + (r instanceof CompileResult.Failed f ? f.error().text() : r));
    }

    @Test
    void acceptsAnyArgumentAtAWildcardParam_wildcardFieldedStruct() {
        // The shape `window(cfg:_, root:_)` is written in: wildcards on both sides.
        CompileResult r = compiler.compile("""
                module m
                struct Box(style:_, children:_)
                function w(cfg:_, root:_):Int -> 0
                w({}, Box({}, {}))""", "wildcard-both.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "`_` accepts every sort; got: "
                        + (r instanceof CompileResult.Failed f ? f.error().text() : r));
    }

    /**
     * The wildcard is top, not a hole in the gate: a genuine misroute between two unrelated structs
     * is still a rejection. Without this, "everything passes" would also make the tests above green.
     */
    @Test
    void stillRejectsAGenuineMisrouteBetweenStructs() {
        CompileResult r = compiler.compile("""
                module m
                struct A(x:Int)
                struct B(y:String)
                function w(r:A):Int -> 0
                w(B("q"))""", "misroute.ptf");
        CompileResult.Failed f =
                assertInstanceOf(CompileResult.Failed.class, r, "expected a compile rejection");
        assertTrue(f.error().text().contains("Cannot prove the call to"),
                () -> "unexpected gate message: " + f.error().text());
    }
}

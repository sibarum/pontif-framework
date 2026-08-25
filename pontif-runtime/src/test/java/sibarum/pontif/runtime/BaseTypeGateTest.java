package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The BASE type of a declared sort is a claim, judged wherever a claim is made.
 *
 * <p>It had not been. Pontif proved refinements everywhere and checked base types almost
 * nowhere: {@code struct P(n:[Int:@>0])} correctly rejected {@code P(0-5)}, and
 * {@code struct P(x:Int)} accepted {@code P("s")}. The asymmetry held at every judgment
 * site — constructor arguments, by-name literals, dictionary promotion, constructor
 * extension fields, function and method returns — because the gate that decides which
 * declared sorts are worth judging answered "no" for a bare primitive. Only two sites got
 * it right and are the model the rest now follow: a {@code let} claim and a trait attribute.
 *
 * <p>The consequence was not merely a missed error. The value was built anyway, and the two
 * engines then disagreed about what the program MEANT — {@code P("s").x + 1} was
 * {@code "s1"} on the interpreter and a runtime error on Truffle. A compiler-accepted
 * program whose meaning depends on the engine is the clearest possible statement that the
 * value should never have been constructible.
 *
 * <p>The rule is <b>the provable miss only</b>. A bare base carries no predicate, so there
 * is nothing for a value to prove; demanding proof would reject every construction whose
 * argument sort inference abstains on. DISJOINT is still decidable — {@code Int} and
 * {@code String} are disjoint sorts — so that half bites and the other stays silent. The
 * negative controls at the bottom are as much the point as the rejections: {@code Int →
 * Decimal} is a ruled coercion and must survive, and it does because the cast is inserted
 * before this gate judges.
 */
class BaseTypeGateTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Runs on every engine and asserts they agree; returns the shared result. */
    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "base.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        String first = null;
        for (PontifRunner.Engine e : PontifRunner.Engine.values()) {
            String out = runner.run(r, e).text();
            if (first == null) {
                first = out;
            } else {
                final String expected = first;
                assertEquals(expected, out, () -> "engines disagree on the same program");
            }
        }
        return first;
    }

    private String reject(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "base.ptf");
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "expected a compile rejection").error().text();
    }

    /** Asserts a rejection that names the disjointness, and returns the message. */
    private String rejectsAsDisjoint(String src) {
        String err = reject(src);
        assertTrue(err.contains("disjoint"),
                () -> "expected a provable-miss diagnostic; got: " + err);
        return err;
    }

    // --- constructor arguments ---------------------------------------------------

    @Test
    void positionalConstructor() {
        assertTrue(rejectsAsDisjoint("""
                struct P(x:Int)
                P("s").x
                """).contains("x"), "the diagnostic should name the offending field");
    }

    @Test
    void byNameLiteral() {
        rejectsAsDisjoint("""
                struct P(x:Int)
                P{x = "s"}.x
                """);
    }

    @Test
    void dictionaryPromotedToAStruct() {
        rejectsAsDisjoint("""
                struct P(x:Int)
                let p:P = {x = "s"}
                p.x
                """);
    }

    @Test
    void boolForInt() {
        rejectsAsDisjoint("""
                struct P(x:Int)
                P(true).x
                """);
    }

    @Test
    void intForString() {
        rejectsAsDisjoint("""
                struct P(x:String)
                P(3).x
                """);
    }

    @Test
    void stringForDecimal() {
        rejectsAsDisjoint("""
                struct P(d:Decimal)
                P("s").d
                """);
    }

    @Test
    void intForChar() {
        rejectsAsDisjoint("""
                struct P(c:Char)
                P(3).c
                """);
    }

    @Test
    void aWholeStructForInt() {
        rejectsAsDisjoint("""
                struct Q(a:Int)
                struct P(x:Int)
                P(Q(1)).x
                """);
    }

    @Test
    void nestedConstruction() {
        rejectsAsDisjoint("""
                struct I(n:Int)
                struct O(i:I)
                O(I(true)).i.n
                """);
    }

    // --- constructor extension fields ---------------------------------------------

    /**
     * The type-system guide says an extension field is "judged against its type exactly
     * like a constructor argument". That was literally true — including the hole. Now it
     * means what a reader takes it to mean.
     */
    @Test
    void constructorExtensionField() {
        assertTrue(rejectsAsDisjoint("""
                struct R(w:Int) ->
                    let this.a:String = this.w
                R(1).a
                """).contains("a"), "the diagnostic should name the extension field");
    }

    // --- returns -------------------------------------------------------------------

    @Test
    void functionReturn() {
        assertTrue(rejectsAsDisjoint("""
                function f():Int -> "s"
                f()
                """).contains("return sort"), "the diagnostic should say it is the return");
    }

    /** A match arm is a return position too — the same lie, one level in. */
    @Test
    void returnThroughAMatchArm() {
        rejectsAsDisjoint("""
                function f(n:Int):Int -> match n { [@>0] -> "s"  [_] -> 0 }
                f(1)
                """);
    }

    @Test
    void methodReturn() {
        rejectsAsDisjoint("""
                struct P(x:Int)
                method P.get():Int -> "s"
                P(1).get()
                """);
    }

    /** A let through to the tail is still a return position. */
    @Test
    void returnThroughALet() {
        rejectsAsDisjoint("""
                function f():Int ->
                  let s = "s"
                  s
                f()
                """);
    }

    // --- negative controls: everything legitimate still compiles --------------------

    /**
     * {@code Int → Decimal} is a ruled language feature, not a hole. It survives only
     * because {@code NumericCoercion} has inserted the {@code Cast} before this gate
     * judges — the pass order in {@code IrCompiler} is AggregatePromotion →
     * NumericCoercion → ConstructionGate. This test is the pin on that ordering: reorder
     * those passes and it fails here rather than silently somewhere else.
     */
    @Test
    void intIntoADecimalField_stillCoerces() {
        assertEquals("3.0", run("""
                struct P(d:Decimal)
                P(3).d
                """));
    }

    @Test
    void intIntoADecimalReturn_stillCoerces() {
        assertEquals("3.0", run("""
                function f():Decimal -> 3
                f()
                """));
    }

    @Test
    void matchingBaseTypes_areUnaffected() {
        assertEquals("3", run("""
                struct P(x:Int)
                P(3).x
                """));
        assertEquals("\"s\"", run("""
                struct P(x:String)
                P("s").x
                """));
    }

    /**
     * A widen is not a miss: a sub-struct value satisfies a base-struct field, and that
     * path runs through the same classify the primitives now reach.
     */
    @Test
    void widenIntoAField_isUnaffected() {
        assertEquals("1", run("""
                struct Base(n:Int)
                struct Sub:Base(n:Int)
                struct Holder(b:Base)
                Holder(Sub(1)).b.n
                """));
    }

    /**
     * The rule is the provable MISS, not a proof obligation: when inference abstains on an
     * argument's sort there is nothing to prove against a bare base, and the construction
     * compiles. A method result resolved later in the pass order is the everyday case.
     */
    @Test
    void anUnknownArgumentSort_isNotAFailedProof() {
        assertEquals("7", run("""
                struct Box(n:Int)
                method Box.plus(k:Int):Int -> this.n + k
                function make(b:Box):Box -> Box(b.plus(4))
                make(Box(3)).n
                """));
    }

    /**
     * String concatenation renders its other operand, so {@code "" + n} is a String — and
     * a String field must accept it. Inference used to type that expression {@code Int}
     * (its Decimal and user-type guards had no String sibling), which nothing noticed
     * until the base type started being checked.
     */
    @Test
    void stringConcatenation_isAString() {
        assertEquals("\"n=3\"", run("""
                struct Label(text:String)
                let n = 3
                Label("n=" + n).text
                """));
    }

    @Test
    void stringConcatenation_throughAReturn() {
        assertEquals("\"3\"", run("""
                function show(n:Int):String -> "" + n
                show(3)
                """));
    }
}

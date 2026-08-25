package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The return gate judges every declared base it can resolve, not only the scalar tower.
 *
 * <p>When the base-type gate landed (docs/soundness-holes.md, family 3) its return half was
 * deliberately narrowed to "both sides a bare primitive", because a return position is reached
 * through desugars — decomposition lets, the param-conversion prologue — and the tail expression
 * there was often not yet the value the function returns. That scope is what the ledger left
 * open, calling the fix a pass-ordering change.
 *
 * <p>It turned out not to be one. Widening the gate to every base the registry knows produced
 * eleven failures, and reading them found the desugars were never the problem: they lower
 * correctly. What was wrong is that their synthesized nodes BORROWED a source span, and the
 * effective-sort lens is keyed by span — so a projection read as the record it projects from, a
 * binder read as its sibling, and a conversion's result read as its input. That is family 6
 * again, at three more sites, and the remedy was family 6's: a synthesized node carries no
 * origin, and the lens omits it by design.
 *
 * <p>What the widening then found on its own account is below: a function may no more return a
 * String where it declares a struct than where it declares an Int. The controls matter as much —
 * a widen to a base type, a trait a struct satisfies, an alias, and an anonymous literal at a
 * nominal return (a CONSTRUCTION, which is the construction machinery's question, not an
 * assignment) all still compile.
 */
class ReturnBaseGateTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Runs on every engine and asserts they agree; returns the shared result. */
    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "ret.ptf");
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

    /** Asserts a rejection that names the disjointness, and returns the message. */
    private String rejectsAsDisjoint(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "ret.ptf");
        String err = assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "expected a compile rejection").error().text();
        assertTrue(err.contains("disjoint"),
                () -> "expected a provable-miss diagnostic; got: " + err);
        return err;
    }

    // --- what the widening catches -----------------------------------------------

    @Test
    void aPrimitiveReturnedWhereAStructIsDeclared() {
        assertTrue(rejectsAsDisjoint("""
                struct Point(x:Int, y:Int)
                function origin():Point -> 3
                origin().x
                """).contains("Point"), "the diagnostic should name the declared sort");
    }

    @Test
    void aStructReturnedWhereAPrimitiveIsDeclared() {
        rejectsAsDisjoint("""
                struct Point(x:Int, y:Int)
                function count():Int -> Point(1, 2)
                count()
                """);
    }

    @Test
    void anUnrelatedStructReturned() {
        rejectsAsDisjoint("""
                struct Point(x:Int, y:Int)
                struct Label(text:String)
                function tag():Label -> Point(1, 2)
                tag().text
                """);
    }

    @Test
    void aMatchArmIsJudgedOneLevelIn() {
        // The tail is every arm, not the syntactic last expression.
        rejectsAsDisjoint("""
                struct Point(x:Int, y:Int)
                function pick(n:Int):Point -> match n { [@>0] -> Point(1, 2)  [_] -> 0 }
                pick(1).x
                """);
    }

    @Test
    void aMethodReturnIsJudgedToo() {
        rejectsAsDisjoint("""
                struct Point(x:Int, y:Int)
                struct Label(text:String)
                method Point.name():Label -> 7
                Point(1, 2).name().text
                """);
    }

    // --- the desugars the old scope was narrowed for -----------------------------

    @Test
    void aDecompositionBinderReadsItsOwnField() {
        // Each binder is a projection, and the binders are not each other: `a` is the Int and
        // `b` the String. Reading either as the source record — which the borrowed span made
        // the lens do — is what kept this gate off structs entirely.
        assertEquals("1", run("""
                let d = {a = 1, b = "s"}
                let d.{a, b}
                a
                """));
    }

    @Test
    void aDecompositionBinderOverAStruct() {
        assertEquals("3", run("""
                struct Point(x:Int, y:Int)
                let p = Point(1, 2)
                let p.{x, y} x + y
                """));
    }

    @Test
    void aParamConversionClauseBindsItsCodomain() {
        // `bar` inside the body is the ProprietaryType the clause produces, not the MyStruct the
        // caller passed — the conversion's result, not its input.
        assertEquals("7", run("""
                struct MyStruct(a:Int, b:Int)
                struct ProprietaryType(z:Int)
                function g(bar:[MyStruct.{a,b} -> ProprietaryType{z=a+b}]):Int -> bar.z
                g(MyStruct(3, 4))"""));
    }

    // --- controls: every legitimate return still compiles -------------------------

    @Test
    void anExactStructReturn() {
        assertEquals("1", run("""
                struct Point(x:Int, y:Int)
                function origin():Point -> Point(1, 2)
                origin().x
                """));
    }

    @Test
    void aWidenToTheDeclaredBase() {
        // A sub-struct returned where its base is declared is a widen, not a miss.
        assertEquals("1", run("""
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                function somewhere():Point -> Point3D(1, 2, 3)
                somewhere().x
                """));
    }

    @Test
    void aTraitTheReturnedStructSatisfies() {
        assertEquals("4", run("""
                trait Sized {
                    size:[Method():Int]
                }
                struct Brick(n:Int)
                assign trait Brick:Sized {
                    size():Int -> 4
                }
                function make():Sized -> Brick(4)
                Brick(4).size()
                """));
    }

    @Test
    void anAliasOfTheReturnedStruct() {
        assertEquals("1", run("""
                struct Point(x:Int, y:Int)
                let Spot:Type[Point]
                function origin():Spot -> Point(1, 2)
                origin().x
                """));
    }

    @Test
    void anAnonymousLiteralAtANominalReturn() {
        // A construction, not an assignment: whether `{w = 3}` may build a Box is the
        // construction machinery's question. An untagged shape is deliberately NOT its tag
        // (two tags may share one shape), so reading this as an assignment would reject it.
        assertEquals("3", run("""
                let Box:Type[[{w:Int}]]
                let b:Box = {w = 3}
                b.w
                """));
    }

    @Test
    void aTypeVariableReturnAbstains() {
        // A type variable's binding is a call-site fact, so there is nothing to decide here.
        assertEquals("5", run("""
                function identity[type T](v:T):T -> v
                identity(5)
                """));
    }

    @Test
    void anUndeclaredReturnSortIsANameError() {
        // Not this gate's business: a name that resolves to nothing is reported as a name.
        PontifCompiler.CompileResult r = compiler.compile("""
                function f(x:Int):Nope -> x
                f(1)
                """, "ret.ptf");
        String err = assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "expected a compile rejection").error().text();
        assertTrue(err.contains("Unknown sort 'Nope'"),
                () -> "the declaration's own error should win; got: " + err);
    }
}

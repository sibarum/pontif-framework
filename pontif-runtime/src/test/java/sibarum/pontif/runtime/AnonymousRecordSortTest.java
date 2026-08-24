package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The BY-NAME face of an anonymous structural type: {@code [{property:String}]},
 * satisfied by the name-present brace literal {@code {property = "a string"}}.
 *
 * <p>The same {@code name:Sort} spelling means the narrow-in-place BINDER inside a
 * match/destructure pattern, so these tests pin both readings — the discriminator is
 * parse position, and the binder cases here are the regression guard for it.
 */
class AnonymousRecordSortTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "rec.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private String reject(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "rec.ptf");
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "expected a compile rejection").error().text();
    }

    // --- the form that must work ---------------------------------------------

    @Test
    void namedMember_isDeclaredAndRead() {
        assertEquals("\"a string\"", run("""
                let objectWithProp:[{property:String}] = {property = "a string"}
                objectWithProp.property
                """));
    }

    @Test
    void severalNamedMembers() {
        assertEquals("3", run("""
                let p:[{x:Int, y:Int}] = {x = 1, y = 2}
                p.x + p.y
                """));
    }

    @Test
    void refinedNamedMember() {
        assertEquals("7", run("""
                let p:[{n:[Int:@>0]}] = {n = 7}
                p.n
                """));
    }

    @Test
    void asAFunctionParameter() {
        assertEquals("5", run("""
                function widthOf(box:[{w:Int, h:Int}]):Int -> box.w
                widthOf({w = 5, h = 9})
                """));
    }

    @Test
    void nestedRecordMember() {
        assertEquals("4", run("""
                let outer:[{inner:[{v:Int}]}] = {inner = {v = 4}}
                outer.inner.v
                """));
    }

    @Test
    void intMemberPromotesToDecimal_likeAStructField() {
        // The primitive tower coerces at every value boundary, and a declared member
        // sort IS one — parity with `struct P(d:Decimal)` accepting `P(3)`.
        assertEquals("3.0", run("""
                let p:[{d:Decimal}] = {d = 3}
                p.d
                """));
    }

    // --- and must not lie ------------------------------------------------------

    @Test
    void memberSortMismatch_isRejected() {
        assertTrue(reject("""
                let p:[{property:String}] = {property = 1}
                p.property
                """).contains("property"), "the diagnostic should name the offending member");
    }

    @Test
    void missingMember_isRejected() {
        assertTrue(reject("""
                let p:[{x:Int, y:Int}] = {x = 1}
                p.x
                """).contains("y"), "the diagnostic should name the missing member");
    }

    @Test
    void extraMember_isRejected() {
        assertTrue(reject("""
                let p:[{x:Int}] = {x = 1, y = 2}
                p.x
                """).contains("y"), "the diagnostic should name the extra member");
    }

    // --- the reserved forms fail with a real explanation ----------------------

    @Test
    void mixedPositionalAndNamed_isRejectedWithGuidance() {
        String err = reject("""
                let m:[{Int, String, property:Decimal}] = {1, "s", 2.0}
                m._0
                """);
        assertTrue(err.contains("all-positional") && err.contains("all-named"),
                () -> "expected the mixed-body guidance; got: " + err);
    }

    @Test
    void namedBeforePositional_isAlsoRejected() {
        String err = reject("""
                let m:[{property:Decimal, Int}] = {2.0, 1}
                m._0
                """);
        assertTrue(err.contains("all-positional") && err.contains("all-named"),
                () -> "expected the mixed-body guidance; got: " + err);
    }

    @Test
    void methodMember_pointsAtTraits() {
        String err = reject("""
                let o:[{doStuff:[Method(Int):String]}] = {doStuff = 1}
                o.doStuff
                """);
        assertTrue(err.contains("trait") && err.contains("doStuff"),
                () -> "expected the trait redirection; got: " + err);
    }

    @Test
    void duplicateMember_isRejected() {
        String err = reject("""
                let p:[{x:Int, x:Int}] = {x = 1}
                p.x
                """);
        assertTrue(err.contains("Duplicate member"), () -> "got: " + err);
    }

    // --- regression: the SAME spelling is still the binder in pattern position -

    @Test
    void narrowInPlaceBinder_stillWorksInAMatchArm() {
        assertEquals("7", run("""
                struct Lit(value:Int)
                struct Add(left:Int, right:Int)
                let Expr:Type[Lit | Add]

                function combine(x:Expr, y:Expr):Int -> match {x, y} {
                  [{a:Lit, b:Lit}] -> a.value + b.value
                  [_]              -> 0
                }

                combine(Lit(3), Lit(4))
                """));
    }

    @Test
    void positionalTupleSort_isUnchanged() {
        assertEquals("3", run("""
                let t:[{String, Int}] = {"s", 3}
                t._1
                """));
    }
}

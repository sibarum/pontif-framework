package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The BY-NAME face of an anonymous structural type: {@code [{property:String}]},
 * satisfied by the name-present brace literal {@code {property = "a string"}}.
 *
 * <p>Three things are pinned here. That the form <em>works</em> — in every position a
 * type can appear. That it does not <em>lie</em> — a shape is a claim, judged like the
 * fields of a struct, not a decoration. And that the reserved forms fail with a real
 * explanation rather than half-parsing.
 *
 * <p>The same {@code name:Sort} spelling is the narrow-in-place BINDER inside a
 * pattern, so the binder cases at the bottom are the regression guard on the
 * discriminator: parse position, which is unambiguous because a pattern matches a
 * value and a type describes one.
 *
 * <p>Value cases run on BOTH engines and assert they agree — an anonymous shape is new
 * surface over the shared record substrate, and a divergence there would mean one
 * engine is reading the shape differently from the other.
 */
class AnonymousRecordSortTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Runs on every engine and asserts they agree; returns the shared result. */
    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "rec.ptf");
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
        PontifCompiler.CompileResult r = compiler.compile(src, "rec.ptf");
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "expected a compile rejection").error().text();
    }

    // --- the form works, in every position a type can appear -------------------

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
    void memberOrder_isIndependentOfTheDeclaredOrder() {
        // The literal is canonicalized into declared order, so writing the members the
        // other way round binds by NAME rather than by position.
        assertEquals("1", run("""
                let p:[{x:Int, y:Int}] = {y = 2, x = 1}
                p.y - p.x
                """));
    }

    @Test
    void refinedMember_provableValueIsAccepted() {
        assertEquals("7", run("""
                let p:[{n:[Int:@>0]}] = {n = 7}
                p.n
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
    void structTypedMember() {
        assertEquals("2", run("""
                struct P(x:Int)
                let r:[{p:P}] = {p = P(2)}
                r.p.x
                """));
    }

    @Test
    void tupleTypedMember() {
        // The two anonymous faces nest in each other — a record member may be a tuple.
        assertEquals("2", run("""
                let r:[{pair:[{Int, Int}]}] = {pair = {1, 2}}
                r.pair._1
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

    @Test
    void asAFunctionParameter() {
        assertEquals("5", run("""
                function widthOf(box:[{w:Int, h:Int}]):Int -> box.w
                widthOf({w = 5, h = 9})
                """));
    }

    @Test
    void asABareBraceParameter() {
        // The bracket-free spelling a parameter may use: `b:{w:Int}` == `b:[{w:Int}]`.
        assertEquals("8", run("""
                function widthOf(b:{w:Int}):Int -> b.w
                widthOf({w = 8})
                """));
    }

    @Test
    void asAFunctionReturnType() {
        assertEquals("4", run("""
                function mk(n:Int):[{v:Int}] -> {v = n}
                mk(4).v
                """));
    }

    @Test
    void asAStructFieldSort() {
        assertEquals("7", run("""
                struct S(p:[{x:Int}])
                let s = S({x = 7})
                s.p.x
                """));
    }

    @Test
    void viaATypeAlias() {
        assertEquals("3", run("""
                let Box:Type[[{w:Int}]]
                let b:Box = {w = 3}
                b.w
                """));
    }

    @Test
    void decomposition_bindsMembersByName() {
        assertEquals("3", run("""
                let d:[{a:Int, b:Int}] = {a = 1, b = 2}
                let d.{a, b}
                a + b
                """));
    }

    // --- and it does not lie ---------------------------------------------------

    @Test
    void memberSortMismatch_isRejected() {
        assertTrue(reject("""
                let p:[{property:String}] = {property = 1}
                p.property
                """).contains("property"), "the diagnostic should name the offending member");
    }

    @Test
    void refinedMember_violationIsRejected() {
        assertTrue(reject("""
                let p:[{n:[Int:@>0]}] = {n = 0-5}
                p.n
                """).contains("satisfy"), "a member refinement is proved, not assumed");
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

    @Test
    void nestedMemberViolation_isRejected() {
        // The judgment recurses — a lie two levels down is still a lie.
        assertTrue(reject("""
                let o:[{i:[{n:[Int:@>0]}]}] = {i = {n = 0-5}}
                o.i.n
                """).contains("satisfy"), "nested member sorts are judged too");
    }

    @Test
    void readingAnUndeclaredMember_isRejected() {
        String err = reject("""
                let p:[{x:Int}] = {x = 1}
                p.zzz
                """);
        assertTrue(err.contains("zzz") && err.contains("no field"), () -> "got: " + err);
    }

    @Test
    void aWrongShapedArgument_doesNotDispatch() {
        // The shape is part of the parameter's type, so a literal of another shape is
        // not a candidate — the call finds no overload rather than coercing.
        assertTrue(run("""
                function widthOf(b:[{w:Int}]):Int -> b.w
                widthOf({q = 1})
                """).contains("Dispatch failed"), "a wrong shape must not silently fit");
    }

    // --- the reserved forms fail with a real explanation -----------------------

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
    void aNestedMixedBody_isAlsoRejected() {
        String err = reject("""
                let r:[{inner:[{Int, a:Int}]}] = {inner = {1}}
                r.inner._0
                """);
        assertTrue(err.contains("all-positional") && err.contains("all-named"),
                () -> "expected the mixed-body guidance; got: " + err);
    }

    @Test
    void methodMember_pointsAtTraits() {
        assertContractRedirect("m:[Method(Int):String]", "Method");
    }

    @Test
    void actionMember_pointsAtTraits() {
        assertContractRedirect("m:[Action(Int)]", "Action");
    }

    @Test
    void conduitMember_pointsAtTraits() {
        assertContractRedirect("m:[Conduit(Int):Int]", "Conduit");
    }

    @Test
    void dispatchMember_pointsAtTraits() {
        assertContractRedirect("m:[Dispatch(Int):Int]", "Dispatch");
    }

    /**
     * Every call-contract face is rejected the same way. The four do not all parse to
     * the same sort shape, so this is the guard against a member that reads as a
     * contract in one spelling and silently as data in another.
     */
    private void assertContractRedirect(String member, String head) {
        String err = reject("let o:[{" + member + "}] = {m = 1}\n1\n");
        assertTrue(err.contains("trait") && err.contains("DATA members only"),
                () -> "expected the trait redirection for " + head + "; got: " + err);
    }

    @Test
    void duplicateMember_isRejected() {
        assertTrue(reject("""
                let p:[{x:Int, x:Int}] = {x = 1}
                p.x
                """).contains("Duplicate member"));
    }

    @Test
    void anEmptyBraceType_isRejected() {
        // `{}` is the empty-aggregate slice, still backlogged — pinned so the record
        // work is not mistaken for having landed it.
        assertTrue(reject("""
                let p:[{}] = {}
                1
                """).contains("empty"));
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
    void narrowInPlaceBinder_selectsTheMatchingArm() {
        // The binder TESTS as well as binds: a non-Lit pair must fall through.
        assertEquals("0", run("""
                struct Lit(value:Int)
                struct Add(left:Int, right:Int)
                let Expr:Type[Lit | Add]

                function combine(x:Expr, y:Expr):Int -> match {x, y} {
                  [{a:Lit, b:Lit}] -> a.value + b.value
                  [_]              -> 0
                }

                combine(Lit(3), Add(1, 2))
                """));
    }

    @Test
    void positionalTupleSort_isUnchanged() {
        assertEquals("3", run("""
                let t:[{String, Int}] = {"s", 3}
                t._1
                """));
    }

    @Test
    void dictionaryPromotionToANamedStruct_isUnchanged() {
        assertEquals("3", run("""
                struct P(x:Int, y:Int)
                let p:P = {x = 1, y = 2}
                p.x + p.y
                """));
    }
}

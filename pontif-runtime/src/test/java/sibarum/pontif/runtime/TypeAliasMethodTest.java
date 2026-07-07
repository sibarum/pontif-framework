package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Methods on type aliases (docs/type-aliases, docs/type-records). A `method Alias.m` dispatches on the
 * binding's DECLARED sort (the nominal identity), while the alias stays structurally transparent — the
 * first migration onto the three-records model: nominal (method) dispatch reads the Declared record,
 * everything else keeps the transparent Inferred sort.
 */
class TypeAliasMethodTest {

    private String run(String src) {
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt(src, "aliasmethod.ptf"), PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "should run; got " + r.text());
        return r.text();
    }

    private String runExpectingError(String src) {
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt(src, "aliasmethod.ptf"), PontifRunner.Engine.INTERPRETER);
        assertTrue(r.isError(), () -> "should have failed; got " + r.text());
        return r.text();
    }

    @Test
    void methodOnAlias_dispatchesOnTheDeclaredName() {
        // v was declared Vec3, so v.sum() links to Vec3.sum — even though the value {…} is an anonymous
        // tuple whose inferred sort carries no name.
        assertEquals("6.0", run("""
                type Vec3:[{3*Decimal}]
                method Vec3.sum():Decimal -> match this { [{a, b, c}] -> a + b + c }
                let v:Vec3 = {1.0, 2.0, 3.0}
                v.sum()
                """));
    }

    @Test
    void sameStructureAliases_dispatchToDistinctMethods() {
        // Vec3 and Color are the SAME structure; the declared name disambiguates their methods.
        assertEquals("3.0", run("""
                type Vec3:[{3*Decimal}]
                type Color:[{3*Decimal}]
                method Vec3.tag():Decimal  -> 1.0
                method Color.tag():Decimal -> 2.0
                let v:Vec3 = {1.0, 2.0, 3.0}
                let c:Color = {4.0, 5.0, 6.0}
                v.tag() + c.tag()
                """));
    }

    @Test
    void methodWithAliasTypedArgument() {
        // A dot product: the method takes another Vec3; both stay structurally transparent tuples.
        assertEquals("32.00", run("""
                type Vec3:[{3*Decimal}]
                method Vec3.dot(other:Vec3):Decimal ->
                    match this { [{a, b, c}] -> match other { [{x, y, z}] -> a * x + b * y + c * z } }
                let v:Vec3 = {1.0, 2.0, 3.0}
                let w:Vec3 = {4.0, 5.0, 6.0}
                v.dot(w)
                """));
    }

    @Test
    void methodOnAliasTypedParameter() {
        // The receiver is a PARAMETER declared Vec3 (a different receiver path than a top-level let).
        assertEquals("6.0", run("""
                type Vec3:[{3*Decimal}]
                method Vec3.sum():Decimal -> match this { [{a, b, c}] -> a + b + c }
                function total(v:Vec3):Decimal -> v.sum()
                let v:Vec3 = {1.0, 2.0, 3.0}
                total(v)
                """));
    }

    @Test
    void methodlessAlias_staysTransparentlyInterchangeable() {
        // A method-LESS alias is a pure abbreviation — interchangeable with its structural definition.
        assertEquals("6.0", run("""
                type ThreeTuple:[{3*Decimal}]
                let v:ThreeTuple = {1.0, 2.0, 3.0}
                let w:[{Decimal, Decimal, Decimal}] = v
                match w { [{a, b, c}] -> a + b + c }
                """));
    }

    @Test
    void methodsDropWhenReboundToTheStructuralTuple() {
        // Identity follows the declaration at each binding: rebinding a Vec3 into a bare {Decimal*3}
        // slot drops the Vec3 methods (docs/type-records.md), so t.sum() has no such method.
        String err = runExpectingError("""
                type Vec3:[{3*Decimal}]
                method Vec3.sum():Decimal -> match this { [{a, b, c}] -> a + b + c }
                let v:Vec3 = {1.0, 2.0, 3.0}
                let t:[{Decimal, Decimal, Decimal}] = v
                t.sum()
                """);
        assertTrue(err.contains("sum"), () -> "expected a no-such-method error mentioning sum; got " + err);
    }
}

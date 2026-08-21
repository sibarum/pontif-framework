package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The struct constructor body (`struct Name(fields) -> let this.name = expr …`).
 * Ruled semantics (James, 2026-08-19):
 * <ul>
 *   <li>the DEFAULT constructor always executes first — the body is a
 *       let-led function body (all-preamble, unit) that runs after it and
 *       sees every constructor field via {@code this.<field>};</li>
 *   <li>the body may only ADD fields (`let this.name = …` binds a NEW
 *       field) — a name colliding with a constructor field, an inherited
 *       field, or being supplied by a literal is a compile error;</li>
 *   <li>every added field is guaranteed never-undefined — materialized into
 *       the value at construction by ConstructionGate and judged against its
 *       declared (or inferred) sort exactly like a constructor argument.</li>
 * </ul>
 */
class StructConstructorExtensionTest {

    private static final String RECT = """
            struct Rect(w:Decimal, h:Decimal) ->
                let this.area:Decimal = this.w * this.h
            """;

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src, Engine engine) {
        return runner.run(compiler.compile(src, "t.ptf"), engine);
    }

    private CompileResult.Failed failed(String src) {
        return assertInstanceOf(CompileResult.Failed.class,
                compiler.compile(src, "t.ptf"),
                "expected a compile-time rejection");
    }

    @Test
    void extensionField_isMaterializedAndReadable_bothEngines() {
        for (Engine e : Engine.values()) {
            RunResult r = run(RECT + "Rect(3.0, 4.0).area", e);
            assertFalse(r.isError(), () -> "expected clean run; got: " + r.text());
            assertTrue(r.text().trim().startsWith("12"),
                    () -> "expected area 12; got: " + r.text());
        }
    }

    @Test
    void extensionField_mayReadEarlierExtensionField() {
        RunResult r = run("""
                struct Box(w:Decimal, h:Decimal) ->
                    let this.area:Decimal = this.w * this.h
                    let this.doubled:Decimal = this.area * 2.0
                Box(2.0, 3.0).doubled
                """, Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "expected clean run; got: " + r.text());
        assertTrue(r.text().trim().startsWith("12"),
                () -> "expected doubled 12; got: " + r.text());
    }

    @Test
    void extensionSort_isInferredWhenOmitted() {
        RunResult r = run("""
                struct Box(w:Decimal, h:Decimal) ->
                    let this.area = this.w * this.h
                Box(2.0, 3.0).area
                """, Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "expected clean run; got: " + r.text());
        assertTrue(r.text().trim().startsWith("6"),
                () -> "expected area 6; got: " + r.text());
    }

    @Test
    void bodyTerminates_atFirstNonThisLet() {
        // The `let r = …` after the body is a TOP-LEVEL let, not a body line.
        RunResult r = run(RECT + """
                let r = Rect(3.0, 4.0)
                r.area
                """, Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "expected clean run; got: " + r.text());
        assertTrue(r.text().trim().startsWith("12"),
                () -> "expected 12; got: " + r.text());
    }

    @Test
    void positionalLiteral_arityExcludesExtensionFields() {
        // Rect has 2 constructor fields; supplying 3 (as if area were one) fails.
        CompileResult.Failed f = failed(RECT + "Rect(3.0, 4.0, 12.0)");
        assertTrue(f.error().text().contains("2 positional arg(s)"),
                () -> "expected ctor arity 2; got: " + f.error().text());
    }

    @Test
    void byNameLiteral_supplyingExtensionField_isRejected() {
        CompileResult.Failed f = failed(RECT + "Rect{w=3.0, h=4.0, area=9.9}");
        assertTrue(f.error().text().contains("constructor-extension"),
                () -> "expected the cannot-supply error; got: " + f.error().text());
    }

    @Test
    void extensionName_collidingWithConstructorField_isRejected() {
        CompileResult.Failed f = failed("""
                struct P(x:Int) ->
                    let this.x:Int = 1
                42
                """);
        assertTrue(f.error().text().contains("reassigns"),
                () -> "expected the no-reassignment error; got: " + f.error().text());
    }

    @Test
    void extensionName_collidingWithInheritedField_isRejected() {
        CompileResult.Failed f = failed("""
                struct Base(x:Int, tag:Int)
                struct Sub:[Base:@.tag==7](x:Int) ->
                    let this.tag:Int = 9
                42
                """);
        assertTrue(f.error().text().contains("reassigns"),
                () -> "expected the no-inherited-reassignment error; got: " + f.error().text());
    }

    @Test
    void initializerReferencingUnknownField_isRejected() {
        CompileResult.Failed f = failed("""
                struct P(x:Int) ->
                    let this.y:Int = this.z + 1
                42
                """);
        assertTrue(f.error().text().contains("not bound yet"),
                () -> "expected the unknown-field error; got: " + f.error().text());
    }

    @Test
    void initializerDisjointFromDeclaredSort_isCompileError() {
        // Bare primitive sorts stay lenient at construction (same policy as
        // constructor args) — the guarantee bites on GATED (refined) sorts.
        CompileResult.Failed f = failed("""
                struct P(x:[Int:@>0]) ->
                    let this.y:[Int:@<0] = this.x
                let p = P(1)
                42
                """);
        assertTrue(f.error().text().contains("can never satisfy")
                        || f.error().text().contains("cannot be proved"),
                () -> "expected the sort-miss error; got: " + f.error().text());
    }

    @Test
    void emptyConstructorBody_isRejected() {
        CompileResult.Failed f = failed("""
                struct P(x:Int) ->
                42
                """);
        assertTrue(f.error().text().contains("at least one"),
                () -> "expected the empty-body error; got: " + f.error().text());
    }

    @Test
    void inheritedExtensionField_materializesOnSubStruct() {
        RunResult r = run("""
                struct Shape(w:Decimal, h:Decimal) ->
                    let this.area:Decimal = this.w * this.h
                struct Square:[Shape:@.w==side & @.h==side](side:Decimal)
                Square(3.0).area
                """, Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "expected clean run; got: " + r.text());
        assertTrue(r.text().trim().startsWith("9"),
                () -> "expected area 9; got: " + r.text());
    }

    @Test
    void positionalDestructure_usesConstructorArityOnly() {
        RunResult r = run(RECT + """
                function diag(r:[Rect(w, h)]):Decimal -> w + h
                diag(Rect(3.0, 4.0))
                """, Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "expected clean run; got: " + r.text());
        assertTrue(r.text().trim().startsWith("7"),
                () -> "expected 7; got: " + r.text());
    }

    @Test
    void constructorBody_coexistsWithMethodBlock() {
        RunResult r = run("""
                struct Rect(w:Decimal, h:Decimal) ->
                    let this.area:Decimal = this.w * this.h
                {
                    describe():Decimal -> this.area + 1.0
                }
                Rect(3.0, 4.0).describe()
                """, Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "expected clean run; got: " + r.text());
        assertTrue(r.text().trim().startsWith("13"),
                () -> "expected 13; got: " + r.text());
    }
}

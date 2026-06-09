package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2 — struct-extension declaration: {@code struct Name:[Base:rel](fields)}
 * parses, stores the is-a relationship as {@code Structural.baseSort}, and is
 * validated by {@code SortChecker} — a struct-base morphism must functionally
 * pin every base field (so the future demotion Name → Base is total). No
 * coercion yet; this slice is parse + register + validate.
 */
class StructExtensionTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    @Test
    void structExtension_parsesAndStoresBaseSort() throws Exception {
        IrModule m = AltParser.parseModule("""
                module m
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                """, "t.ptf");
        IrSort.Structural p3d = null;
        for (IrStmt s : m.statements()) {
            if (s instanceof IrStmt.TypeAlias ta && ta.name().equals("Point3D")
                    && ta.sort() instanceof IrSort.Structural st) {
                p3d = st;
            }
        }
        assertNotNull(p3d, "Point3D struct should be declared");
        assertNotNull(p3d.baseSort(), "Point3D should carry a baseSort");
        assertInstanceOf(IrSort.Refined.class, p3d.baseSort());
        assertEquals("Point", ((IrSort.Refined) p3d.baseSort()).name());
        assertTrue(p3d.members().containsKey("z"), "Point3D adds its own field z");
    }

    @Test
    void totalMorphism_compilesAndRuns() {
        // Every base field (x, y) is pinned → the demotion is total → accepted.
        String src = """
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                42""";
        for (Engine engine : Engine.values()) {
            RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("42", r.text(), engine.toString());
        }
    }

    @Test
    void positionalMorphism_compilesAndRuns() {
        // `[Point(x, y)]` is the positional spelling of the same demotion.
        String src = """
                struct Point(x:Int, y:Int)
                struct Point3D:[Point(x, y)](x:Int, y:Int, z:Int)
                42""";
        RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("42", r.text());
    }

    @Test
    void nonTotalMorphism_isRejected() {
        // @.y is left unpinned — the demotion isn't total, so it can't be a
        // morphism; rejected at compile time.
        String src = """
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x](x:Int, y:Int, z:Int)
                42""";
        RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), Engine.INTERPRETER);
        assertTrue(r.isError(), "a non-total morphism should be rejected");
        assertTrue(r.text().contains("does not pin base field"),
                () -> "got: " + r.text());
    }

    @Test
    void primitiveBase_isRejected_encapsulateInstead() {
        // A struct can't be-a a primitive — primitives are encapsulated as
        // fields (record-is-a-scalar is an open decision, deferred). The error
        // is explicit, not the incidental "Not a Decimal narrow".
        String src = """
                struct Complex:[Decimal:@==r](r:Decimal, i:Decimal)
                Complex(3.0, 4.0).r""";
        RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), Engine.INTERPRETER);
        assertTrue(r.isError(), "a struct can't be-a a primitive");
        assertTrue(r.text().contains("can only be encapsulated"), () -> "got: " + r.text());
    }

    // --- S3: demotion coercion ----------------------------------------------

    @Test
    void demotion_projectsTheMorphism() {
        // let b:Point = a runs the morphism: b is Point(2, 3); 2 + 3 = 5.
        String src = """
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                let a = Point3D(2, 3, 5)
                let b:Point = a
                b.x + b.y""";
        for (Engine engine : Engine.values()) {
            RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("5", r.text(), engine.toString());
        }
    }

    @Test
    void demotion_dropsTheUnmentionedField() {
        // z is gone after demotion — b.z is an error (clean forget, no tag).
        String src = """
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                let a = Point3D(2, 3, 5)
                let b:Point = a
                b.z""";
        RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), Engine.INTERPRETER);
        assertTrue(r.isError(), "b.z should error — z was dropped by the demotion");
    }

    @Test
    void promotionByAssignment_isRejected() {
        // let c:Point3D = b can't fabricate z — no auto-promotion.
        String src = """
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                let a = Point3D(2, 3, 5)
                let b:Point = a
                let c:Point3D = b
                42""";
        RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), Engine.INTERPRETER);
        assertTrue(r.isError(), "promotion by assignment can't synthesize z — must be rejected");
    }

    // --- S4: param-sort `.{}` destructuring ---------------------------------

    @Test
    void paramSortDestructure_bindsFieldsInBody() {
        // point:[Point.{x, y}] — x, y bind to point.x, point.y in the body.
        String src = """
                struct Point(x:Int, y:Int)
                function sumXY(point:[Point.{x, y}]):Int -> x + y
                sumXY(Point(2, 3))""";
        for (Engine engine : Engine.values()) {
            RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("5", r.text(), engine.toString());
        }
    }

    @Test
    void paramSortDestructure_withRename() {
        // `x -> px` renames the introduced local.
        String src = """
                struct Point(x:Int, y:Int)
                function getX(point:[Point.{x -> px}]):Int -> px
                getX(Point(7, 9))""";
        RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("7", r.text());
    }

    // --- S5: promotion via synthesis ----------------------------------------

    @Test
    void promotion_viaFunctionSynthesis() {
        // promote synthesizes Point3D(x, y, z) from the return construction pin;
        // point is destructured to x, y (S4) and z is a param.
        // promote(Point(2,3), 7) = Point3D(2,3,7); 2+3+7 = 12.
        String src = """
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                function promote(point:[Point.{x, y}], z:Int):Point3D{x, y, z};
                let p = promote(Point(2, 3), 7)
                p.x + p.y + p.z""";
        for (Engine engine : Engine.values()) {
            RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("12", r.text(), engine.toString());
        }
    }

    @Test
    void promotion_viaMethodSynthesis() {
        // method promote uses this.x/this.y + z. On Point(2,3), b.promote(11) =
        // Point3D(2,3,11); 2+3+11 = 16. The method form enables type inference.
        String src = """
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                method Point.promote(z:Int):Point3D{this.x, this.y, z};
                let b = Point(2, 3)
                let p = b.promote(11)
                p.x + p.y + p.z""";
        RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("16", r.text());
    }

    // --- S6: promotion via value synthesis (partial value ⊕ pin) ------------

    @Test
    void valueSynthesisPromotion_mergesValueAndPin() {
        // let p:[Point3D:@.z==0] = b — b (Point) supplies x, y; the pin supplies
        // z=0. Merged into Point3D(2, 3, 0); 2 + 3 + 0 = 5.
        String src = """
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                let b = Point(2, 3)
                let p:[Point3D:@.z==0] = b;
                p.x + p.y + p.z""";
        for (Engine engine : Engine.values()) {
            RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("5", r.text(), engine.toString());
        }
    }

    @Test
    void valueSynthesisPromotion_unspecifiedFieldErrors() {
        // The pin covers only x; b (Point) has no z → z is unspecified, rejected
        // (fabricate-never — the merge must cover every field).
        String src = """
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                let b = Point(2, 3)
                let p:[Point3D:@.x==9] = b;
                p.x""";
        RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), Engine.INTERPRETER);
        assertTrue(r.isError(), "z is unspecified — the merge must reject");
        assertTrue(r.text().contains("unspecified"), () -> "got: " + r.text());
    }

    // --- S8: monadic in-type pipeline (no requires) -------------------------

    @Test
    void inTypePipeline_synthesizesViaLetStage() {
        // The "monadic" return: a let-stage computes m via a function call, the
        // final pin returns m — equivalent to a `-> addUp(x,y)` body. No
        // `requires`: addUp resolves as an ordinary global function.
        String src = """
                struct Point(x:Int, y:Int)
                function addUp(a:Int, b:Int):Int -> a + b
                function combine(p:[Point.{x, y}]):[
                    let m:Int = addUp(x, y) ->
                    Int:@==m
                ];
                combine(Point(2, 3))""";
        for (Engine engine : Engine.values()) {
            RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("5", r.text(), engine.toString());
        }
    }

    @Test
    void inTypePipeline_multiStage() {
        // Two stages chain: a = dbl(10) = 20, b = a + 1 = 21, return b.
        String src = """
                function dbl(n:Int):Int -> n * 2
                function f(n:Int):[
                    let a:Int = dbl(n) ->
                    let b:Int = a + 1 ->
                    Int:@==b
                ];
                f(10)""";
        for (Engine engine : Engine.values()) {
            RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("21", r.text(), engine.toString());
        }
    }

    @Test
    void inTypePipeline_definesAndProves() {
        // One pin carries BOTH halves: `@==r` DEFINES the body
        // (let r = n*factorial(n-1)), `@>0` is the postcondition the gate PROVES
        // inductively (n>=1 times factorial(n-1)>=1). Synthesis + verification,
        // one form — the recursive factorial WITH its proven positivity.
        String src = """
                function factorial(n:[Int:0]):[Int:1];
                function factorial(n:[Int:@>0]):[let r:Int = n*factorial(n-1) -> [Int:@==r & @>0]];
                factorial(5)""";
        for (Engine engine : Engine.values()) {
            RunResult r = runner.run(compiler.compileAlt(src, "t.ptf"), engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("120", r.text(), engine.toString());
        }
    }
}

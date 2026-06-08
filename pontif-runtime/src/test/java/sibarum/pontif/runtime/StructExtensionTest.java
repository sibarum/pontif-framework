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
}

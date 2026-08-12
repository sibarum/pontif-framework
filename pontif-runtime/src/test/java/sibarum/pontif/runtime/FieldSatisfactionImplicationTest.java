package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trait ATTRIBUTE requirement satisfied by an existing struct field is now
 * checked with the shared {@link sibarum.pontif.core.symbolic.Refinements#imply}
 * kernel, not syntactic predicate equality. So a field whose refinement is
 * STRONGER than the requirement (and provably implies it) satisfies it — the
 * previous rule demanded an identical predicate.
 */
class FieldSatisfactionImplicationTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        CompileResult r = compiler.compileAlt(src, "fieldsat.ptf");
        CompileResult.Compiled c = assertInstanceOf(
                CompileResult.Compiled.class, r, () -> "expected compile success; got " + r);
        PontifRunner.RunResult rr = runner.run(c.program(), Engine.INTERPRETER);
        assertTrue(!rr.isError(), () -> "run error: " + rr.text());
        return rr.text();
    }

    private String reject(String src) {
        CompileResult r = compiler.compileAlt(src, "fieldsat.ptf");
        return ((CompileResult.Failed) assertInstanceOf(
                CompileResult.Failed.class, r, "expected a compile rejection")).error().text();
    }

    @Test void strongerFieldPredicate_satisfiesWeakerRequirement() {
        // field [Int:@>5] implies the requirement [Int:@>0] → satisfied (was rejected
        // by the old identical-predicate rule).
        assertEquals("9", run("""
                trait Heavyish{ weight:[Int:@>0] }
                struct Item(weight:[Int:@>5])
                assign trait Item:Heavyish { }
                Item(9).weight"""));
    }

    @Test void identicalFieldPredicate_stillSatisfies() {
        assertEquals("9", run("""
                trait Heavyish{ weight:[Int:@>0] }
                struct Item(weight:[Int:@>0])
                assign trait Item:Heavyish { }
                Item(9).weight"""));
    }

    @Test void nonImplyingFieldPredicate_isRejected() {
        // [Int:@!=0] does NOT imply [Int:@>0] (a negative value satisfies !=0 but not >0).
        String err = reject("""
                trait Heavyish{ weight:[Int:@>0] }
                struct Item(weight:[Int:@!=0])
                assign trait Item:Heavyish { }
                Item(9).weight""");
        assertTrue(err.contains("does not provably satisfy"),
                () -> "expected the fail-closed rejection; got: " + err);
    }
}

package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 3 of associated types (docs/associated-types.md): an impl binds the
 * trait's associated type with {@code type X = [Sort]}, and the binding is
 * substituted into the trait's dependent signatures and enforced — every
 * declared associated type must be bound exactly once, and a dependent method's
 * impl must conform to the substituted contract.
 */
class AssociatedTypeBindTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "bind.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compile(src, "bind.ptf"), "expected a compile rejection");
    }

    private static final String EXPR = """
            trait Expr{
              type T,
              simplify:[Method():Expr],
              evaluate:[Method():T]
            }
            """;

    @Test
    void boundImpl_evaluatesThroughTheConcreteType() {
        // type T = [Int] binds evaluate's contract to ():Int; the concrete
        // IntLit.evaluate() returns its Int value.
        assertEquals("5", run(EXPR + """
                struct IntLit(value:Int)
                assign trait IntLit:Expr {
                  type T = [Int]
                  simplify():Expr -> this
                  evaluate():Int  -> this.value
                }
                IntLit(5).evaluate()
                """));
    }

    @Test
    void twoImpls_bindTheAssociatedTypeDifferently() {
        // Each implementor chooses its own T — Int for IntLit, Bool for BoolLit —
        // and each evaluate() conforms to its own substituted contract.
        assertEquals("true", run(EXPR + """
                struct IntLit(value:Int)
                struct BoolLit(flag:Bool)
                assign trait IntLit:Expr {
                  type T = [Int]
                  simplify():Expr -> this
                  evaluate():Int  -> this.value
                }
                assign trait BoolLit:Expr {
                  type T = [Bool]
                  simplify():Expr  -> this
                  evaluate():Bool  -> this.flag
                }
                BoolLit(true).evaluate()
                """));
    }

    @Test
    void missingBinding_isRejected() {
        PontifCompiler.CompileResult.Failed f = rejects(EXPR + """
                struct IntLit(value:Int)
                assign trait IntLit:Expr {
                  simplify():Expr -> this
                  evaluate():Int  -> this.value
                }
                IntLit(5).evaluate()
                """);
        assertTrue(f.error().text().contains("missing associated-type binding 'T'"),
                () -> f.error().text());
    }

    @Test
    void bindingDisagreesWithMethodReturn_isRejected() {
        // T = [Int] makes evaluate's contract ():Int, but the impl returns Bool.
        PontifCompiler.CompileResult.Failed f = rejects(EXPR + """
                struct IntLit(value:Int)
                assign trait IntLit:Expr {
                  type T = [Int]
                  simplify():Expr -> this
                  evaluate():Bool -> true
                }
                IntLit(5).evaluate()
                """);
        assertTrue(f.error().text().contains("evaluate") && f.error().text().contains("Bool"),
                () -> f.error().text());
    }

    @Test
    void bindingUndeclaredAssociatedType_isRejected() {
        PontifCompiler.CompileResult.Failed f = rejects(EXPR + """
                struct IntLit(value:Int)
                assign trait IntLit:Expr {
                  type T = [Int]
                  type U = [Bool]
                  simplify():Expr -> this
                  evaluate():Int  -> this.value
                }
                IntLit(5).evaluate()
                """);
        assertTrue(f.error().text().contains("binds associated type 'U'"),
                () -> f.error().text());
    }
}

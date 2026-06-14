package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 6 of associated types (docs/associated-types.md §7.3) — the self-type
 * return {@code this.type}: a contract method that is <em>type-preserving</em>
 * (returns the implementor's own concrete type) is spelled
 * {@code copy:[Method():this.type]}. On a <em>concrete</em> receiver the result
 * keeps that concrete type (no downcast); on a <em>bare-trait</em> receiver it
 * existentializes to the owning trait. The impl must actually return its own
 * type — a sibling type is rejected (the type-preservation gate, which falls
 * out of substituting {@code this.type ↦ <implType>} into the contract).
 *
 * <p>Contrast {@code :TraitName} (slice 4), which promises only trait-membership
 * — the right return for a non-type-preserving method.
 */
class AssociatedTypeSelfTypeTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "self.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compileAlt(src, "self.ptf"), "expected a compile rejection");
    }

    private static final String EXPR = """
            let Expr:Type{
              copy:[Method():this.type],
              val:[Method():Int]
            }
            struct Lit(value:Int)
            assign trait Lit:Expr {
              copy():Lit -> this
              val():Int  -> this.value
            }
            """;

    @Test
    void typePreserving_concreteReceiver_keepsConcreteType() {
        // copy() on a concrete Lit returns Lit — so `.value` (a Lit-specific
        // field, NOT an Expr method) resolves. If copy() returned the bare trait
        // Expr, `.value` would not type. The 5 proves the result stayed Lit.
        assertEquals("5", run(EXPR + """
                Lit(5).copy().value
                """));
    }

    @Test
    void typePreserving_traitReceiver_isExistential() {
        // e:Expr is the bare trait. e.copy() existentializes to Expr — usable
        // through the trait's interface (.val() dispatches to Lit.val).
        assertEquals("7", run(EXPR + """
                function fetch(e:Expr):Int -> e.copy().val()
                fetch(Lit(7))
                """));
    }

    @Test
    void typePreservation_violated_isRejected() {
        // copy declared to return Add (a sibling Expr type), not the impl's own
        // type — `this.type` substitutes to Lit, so the contract requires Lit.
        PontifCompiler.CompileResult.Failed f = rejects("""
                let Expr:Type{
                  copy:[Method():this.type]
                }
                struct Lit(value:Int)
                struct Add(l:Int, r:Int)
                assign trait Lit:Expr {
                  copy():Add -> Add(1, 2)
                }
                Lit(5).copy()
                """);
        assertTrue(f.error().text().contains("copy") && f.error().text().contains("Lit"),
                () -> f.error().text());
    }
}

package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for trait DATA attributes (docs/univocal-arrows.md):
 * <ul>
 *   <li>{@code Type{…}} carries typed attribute members alongside methods;</li>
 *   <li>{@code assign trait} provides EXACTLY the unmet members via
 *       {@code name:Sort -> producer} arrows (a satisfier may supply an
 *       attribute with a struct field instead);</li>
 *   <li>a struct coerces to a trait it satisfies and the trait-view attribute
 *       access resolves to the field or the computed producer (the coercion is
 *       information-conserving, so it's free in both directions).</li>
 * </ul>
 */
class TraitAttributeTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        var r = runner.run(compiler.compileAlt(src, "trait-attr.ptf"), Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "expected success; got: " + r.text());
        return r.text();
    }

    private String reject(String src) {
        CompileResult r = compiler.compileAlt(src, "trait-attr.ptf");
        assertInstanceOf(CompileResult.Failed.class, r, "expected compile failure");
        return ((CompileResult.Failed) r).error().text();
    }

    // --- attribute provided by a computed producer --------------------------

    @Test
    void attributeProducer_accessibleOnInstance() {
        // Ipsum has no `weight` field; the impl provides it as a projection.
        assertEquals("1", run("""
                let Heavyish:Type{ weight:[Int:@>0] }
                struct Ipsum(name:Int)
                assign trait Ipsum:Heavyish {
                  weight:Int -> 1
                }
                let i = Ipsum(5)
                i.weight
                """));
    }

    @Test
    void attributeProducer_readsInstanceState() {
        // A producer is a projection over `this` (existence-only attribute, so
        // no refinement obligation — it just reads instance state).
        assertEquals("12", run("""
                let Labeled:Type{ tag:Int }
                struct Crate(base:Int)
                assign trait Crate:Labeled {
                  tag:Int -> this.base + 2
                }
                let c = Crate(10)
                c.tag
                """));
    }

    @Test
    void structUpcastToTrait_attributeReadThroughView() {
        // The struct coerces to the trait; the trait-view attribute resolves to
        // the producer (upcast + downcast are free — attributes are projections).
        assertEquals("1", run("""
                let Heavyish:Type{ weight:[Int:@>0] }
                struct Ipsum(name:Int)
                assign trait Ipsum:Heavyish {
                  weight:Int -> 1
                }
                let i = Ipsum(5)
                let h:Heavyish = i
                h.weight
                """));
    }

    // --- attribute satisfied by an existing field ---------------------------

    @Test
    void attributeSatisfiedByField_compilesAndReadsField() {
        // Lorem already brings `weight:[Int:@>0]`, so the impl provides only the
        // method — the field satisfies the attribute (no producer needed).
        assertEquals("9", run("""
                let Heavyish:Type{ ping:[Method():Int], weight:[Int:@>0] }
                struct Lorem(weight:[Int:@>0], name:Int)
                assign trait Lorem:Heavyish {
                  ping():Int -> 7
                }
                let l = Lorem(9, 3)
                l.weight
                """));
    }

    // --- failure modes ------------------------------------------------------

    @Test
    void missingAttribute_incomplete_rejected() {
        String err = reject("""
                let Heavyish:Type{ weight:[Int:@>0] }
                struct Sit(name:Int)
                assign trait Sit:Heavyish { }
                42
                """);
        assertTrue(err.toLowerCase().contains("weight")
                && err.toLowerCase().contains("missing"),
                () -> "expected an incomplete-attribute rejection; got: " + err);
    }

    @Test
    void fieldAndProducer_overAssignment_rejected() {
        String err = reject("""
                let Heavyish:Type{ weight:[Int:@>0] }
                struct Lorem(weight:[Int:@>0])
                assign trait Lorem:Heavyish {
                  weight:Int -> 9
                }
                42
                """);
        assertTrue(err.toLowerCase().contains("over-assignment")
                || err.toLowerCase().contains("already has"),
                () -> "expected an over-assignment rejection; got: " + err);
    }

    @Test
    void producerForUnknownAttribute_overAssignment_rejected() {
        String err = reject("""
                let Heavyish:Type{ weight:[Int:@>0] }
                struct Ipsum(name:Int)
                assign trait Ipsum:Heavyish {
                  weight:Int -> 1
                  bogus:Int -> 2
                }
                42
                """);
        assertTrue(err.toLowerCase().contains("bogus")
                && err.toLowerCase().contains("over-assignment"),
                () -> "expected an over-assignment rejection for the extra member; got: " + err);
    }

    @Test
    void fieldNotProvablyRefined_failClosed_rejected() {
        // Dolor's `weight:Int` isn't provably > 0, and no producer is given.
        String err = reject("""
                let Heavyish:Type{ weight:[Int:@>0] }
                struct Dolor(weight:Int)
                assign trait Dolor:Heavyish { }
                42
                """);
        assertTrue(err.toLowerCase().contains("weight"),
                () -> "expected a fail-closed rejection on the unrefined field; got: " + err);
    }

    @Test
    void producerViolatesRefinement_failClosed_rejected() {
        // The producer must satisfy the CONTRACT refinement [Int:@>0]; 0 doesn't.
        String err = reject("""
                let Heavyish:Type{ weight:[Int:@>0] }
                struct Ipsum(name:Int)
                assign trait Ipsum:Heavyish {
                  weight:Int -> 0
                }
                42
                """);
        assertTrue(err.toLowerCase().contains("weight")
                || err.toLowerCase().contains("prove")
                || err.contains("@"),
                () -> "expected a fail-closed rejection on the producer value; got: " + err);
    }
}

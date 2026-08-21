package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The existential-boundary consumption of associated types
 * (docs/associated-types.md §3.2) — the second half of slice 4 (the doc's
 * "R-interface use of T-typed values"), which the bound-at-bind commit left
 * open: a value of <em>static</em> trait type has an associated type that
 * is unknown statically but bounded. A contract method returning that associated
 * type is callable on the bare-trait receiver; its result flows out as a bounded
 * existential ({@code ∃T:R}), usable through {@code R}'s interface — so
 * {@code b.get().describe()} type-checks and dispatches to the concrete impl at
 * runtime. An <em>unbounded</em> associated type's existential stays opaque: it
 * can ride through the program but cannot be called into.
 *
 * <p>This is the consumption half that slices 2–4 (declaration, bind, bound-at-
 * bind) left open. Earlier slices only exercised <em>concrete</em> receivers,
 * where the associated type is statically known; here the receiver is the bare
 * trait, so the associated type genuinely is an existential.
 */
class AssociatedTypeExistentialTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "exist.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compile(src, "exist.ptf"), "expected a compile rejection");
    }

    /** A trait `Box` whose associated type `T` is bounded by `Showable`. */
    private static final String BOUNDED = """
            trait Showable{ describe:[Method():Int] }
            struct Tag(n:Int)
            assign trait Tag:Showable { describe():Int -> this.n }

            trait Box{
              type T:Showable,
              get:[Method():T]
            }
            struct TagBox(tag:Tag)
            assign trait TagBox:Box {
              type T = [Tag]
              get():Tag -> this.tag
            }
            """;

    @Test
    void boundedExistential_throughFunctionParam_usableViaTheBound() {
        // b:Box is the bare trait — T is statically unknown but bounded by
        // Showable. b.get() returns ∃T:Showable; .describe() is Showable's
        // interface, so the chained call type-checks and dispatches to
        // Tag.describe at runtime.
        assertEquals("7", run(BOUNDED + """
                function describeBox(b:Box):Int -> b.get().describe()
                describeBox(TagBox(Tag(7)))
                """));
    }

    @Test
    void boundedExistential_throughTopLevelLet_usableViaTheBound() {
        // Same, but the existential receiver is a let-bound value of trait type.
        assertEquals("9", run(BOUNDED + """
                let b:Box = TagBox(Tag(9))
                b.get().describe()
                """));
    }

    @Test
    void boundedExistential_resultBoundToTheTraitType() {
        // The existential result is bindable at its bound's type and used from
        // there — `∃T:Showable` conforms to `Showable`.
        assertEquals("4", run(BOUNDED + """
                let b:Box = TagBox(Tag(4))
                let s:Showable = b.get()
                s.describe()
                """));
    }

    @Test
    void unboundedExistential_isOpaque_methodCallRejected() {
        // `type T` with no bound: get()'s existential result has no interface,
        // so calling a method on it cannot resolve — the honest opaque case.
        PontifCompiler.CompileResult.Failed f = rejects("""
                trait Holder{
                  type T,
                  get:[Method():T]
                }
                struct IntHolder(v:Int)
                assign trait IntHolder:Holder {
                  type T = [Int]
                  get():Int -> this.v
                }
                function probe(h:Holder):Int -> h.get().describe()
                probe(IntHolder(3))
                """);
        assertTrue(f.error().text().contains("describe"), () -> f.error().text());
    }
}

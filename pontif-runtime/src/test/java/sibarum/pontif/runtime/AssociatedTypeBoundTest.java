package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 4 of associated types (docs/associated-types.md): a bounded associated
 * type {@code type X:R} requires an impl's binding {@code type X = [Foo]} to
 * supply a type that satisfies {@code R} — a refinement lifted to the type
 * universe, checked fail-closed against the declared trait-satisfaction relation.
 */
class AssociatedTypeBoundTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "bound.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private PontifCompiler.CompileResult.Failed rejects(String src) {
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class,
                compiler.compileAlt(src, "bound.ptf"), "expected a compile rejection");
    }

    private static final String SETUP = """
            let Showable:Type{ describe:[Method():Int] }
            struct Tag(n:Int)
            assign trait Tag:Showable { describe():Int -> this.n }

            let Box:Type{
              type T:Showable,
              get:[Method():T]
            }
            """;

    @Test
    void boundSatisfied_compilesAndRuns() {
        // Tag satisfies Showable, so binding `type T = [Tag]` is allowed.
        assertEquals("7", run(SETUP + """
                struct TagBox(tag:Tag)
                assign trait TagBox:Box {
                  type T = [Tag]
                  get():Tag -> this.tag
                }
                TagBox(Tag(7)).get().describe()
                """));
    }

    @Test
    void boundViolated_isRejected() {
        // Int does not satisfy Showable — the bound `type T:Showable` rejects it.
        PontifCompiler.CompileResult.Failed f = rejects(SETUP + """
                struct IntBox(v:Int)
                assign trait IntBox:Box {
                  type T = [Int]
                  get():Int -> this.v
                }
                IntBox(3).get()
                """);
        assertTrue(f.error().text().contains("satisfy 'Showable'"), () -> f.error().text());
    }
}

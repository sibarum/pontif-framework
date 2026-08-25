package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A declared sort may only name a type that exists — at a struct's fields as much as anywhere.
 *
 * <p>{@code struct Status(text:Str)} compiled. {@code Str} is not a type; nothing said so. The
 * base-type gate (docs/soundness-holes.md, family 3) closed the CONSEQUENCE — a call site passing
 * a value to that field is now judged — but it judges against a name the registry cannot resolve,
 * so it abstains, and the declaration that was actually wrong stayed silent.
 *
 * <p>The engine that answers "is this name a type?" already existed and was already correct:
 * {@code SortChecker.validateSortNames}, which knows primitives, declared structs and traits,
 * native constructors, builtin call-kind heads and in-scope type variables. It was simply never
 * asked about a struct's fields — the statement loop excluded structural declarations, believing
 * the fields were validated "via the struct's own path", when the only struct-specific path
 * validates the is-a base. So this is one engine reaching one more site, not a second checker
 * with its own opinion about what a type name is: the negative controls below are the proof, and
 * every one of them was already passing before the fields were included.
 */
class DeclaredSortNameTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Runs on every engine and asserts they agree; returns the shared result. */
    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "names.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        String first = null;
        for (PontifRunner.Engine e : PontifRunner.Engine.values()) {
            String out = runner.run(r, e).text();
            if (first == null) {
                first = out;
            } else {
                final String expected = first;
                assertEquals(expected, out, () -> "engines disagree on the same program");
            }
        }
        return first;
    }

    /** Asserts a rejection naming the undeclared type, and returns the message. */
    private String rejects(String src, String offendingName) {
        PontifCompiler.CompileResult r = compiler.compile(src, "names.ptf");
        String err = assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "expected a compile rejection").error().text();
        assertTrue(err.contains(offendingName),
                () -> "the diagnostic should name '" + offendingName + "'; got: " + err);
        return err;
    }

    // --- the hole ----------------------------------------------------------------

    @Test
    void structFieldNamingNothing_isRejected() {
        String err = rejects("""
                struct Status(text:Str)
                1
                """, "Str");
        assertTrue(err.toLowerCase().contains("unknown sort"),
                () -> "expected the unknown-sort vocabulary; got: " + err);
    }

    @Test
    void theSecondFieldIsJudgedToo() {
        rejects("""
                struct Pair(left:Int, right:Nope)
                1
                """, "Nope");
    }

    @Test
    void aRefinedFieldOverAnUndeclaredBase_isRejected() {
        rejects("""
                struct Bounded(n:[Whole:@>0])
                1
                """, "Whole");
    }

    @Test
    void aTypeArgumentOfAFieldIsJudged() {
        rejects("""
                requires pontif.core.{Stream}
                struct Bag(items:Stream[Widgit])
                1
                """, "Widgit");
    }

    @Test
    void aConstructorExtensionFieldIsJudged() {
        // `let this.label:Sort = …` is a declared field like any other (docs, struct
        // constructor extensions) — it carries a sort, so it carries a claim.
        rejects("""
                struct Reading(v:Int) ->
                    let this.label:Strng = "r"
                1
                """, "Strng");
    }

    @Test
    void theDiagnosticPointsAtTheDeclaration() {
        PontifCompiler.CompileResult r = compiler.compile("""
                struct Status(text:Str)
                1
                """, "names.ptf");
        var failed = assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "expected a compile rejection");
        assertTrue(failed.error().origin().isPresent(), "the error should carry an origin");
        assertEquals(1, failed.error().origin().get().span().start().line(),
                "the error belongs on the declaration line, not at a call site");
    }

    // --- negative controls: every name a field may legitimately carry -------------

    @Test
    void primitiveFields_compile() {
        assertEquals("3", run("""
                struct P(n:Int, d:Decimal, s:String, c:Char, b:Bool)
                P(3, 1.0, "s", 'c', true).n
                """));
    }

    @Test
    void aFieldNamingAnotherStruct_compiles() {
        assertEquals("7", run("""
                struct Inner(v:Int)
                struct Outer(i:Inner)
                Outer(Inner(7)).i.v
                """));
    }

    @Test
    void aSelfReferentialFieldSort_compiles() {
        // Resolution is by NAME — a recursive struct must not send the walk down its own body.
        assertEquals("2", run("""
                struct Node(v:Int, next:Node)
                struct Leaf(v:Int)
                2
                """));
    }

    @Test
    void aTypeParameterIsInScopeForItsOwnFields() {
        assertEquals("5", run("""
                struct Box[type T](value:T)
                Box(5).value
                """));
    }

    @Test
    void aFieldNamingATrait_compiles() {
        // The declaration is what is under test. Calling `size()` back THROUGH the
        // trait-typed field is a separate open facet (dispatch on a bare-trait receiver),
        // so the field is read, not dispatched on.
        assertEquals("4", run("""
                trait Sized {
                    size:[Method():Int]
                }
                struct Holder(item:Sized)
                struct Brick(n:Int)
                assign trait Brick:Sized {
                    size():Int -> 4
                }
                Brick(4).size()
                """));
    }

    @Test
    void aFieldNamingAnAlias_compiles() {
        assertEquals("9", run("""
                let Count:Type[[Int:@>=0]]
                struct Tally(n:Count)
                Tally(9).n
                """));
    }
}

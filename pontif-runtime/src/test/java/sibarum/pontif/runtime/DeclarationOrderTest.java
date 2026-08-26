package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A type is visible everywhere in the file that declares it.</b> Where a {@code struct},
 * {@code enum}, {@code trait} or sort alias sits in the file has no bearing on what its name means
 * above that point — the parser registers every declaration in a pre-pass before it parses a single
 * body ({@code PontifParser.prescanTypeDeclarations}).
 *
 * <p>Previously the catalog filled one declaration at a time as the parse reached them, so
 * {@code Point(1, 2)} was a struct literal only when {@code struct Point} appeared ABOVE it and
 * otherwise stayed a {@code Call} that {@code CallNameCheck} rejected as "Unknown function". The
 * indefensible consequence was {@link #memberBlockConstructsItsOwnType()}: a struct is never
 * declared before ITSELF, so the most ordinary method on an immutable struct — the one returning a
 * modified copy — could not be written in a member block at all.
 *
 * <p>Order-independence is not only about construction. It reaches every decision the parser alone
 * can make: an enum applied to a literal row is a case LOOKUP and never a construction
 * ({@link #enumLookupBeforeTheEnum()}), and a case named in a match pattern
 * ({@link #enumCaseInAMatchPatternBeforeTheEnum()}) did not previously even parse — which is why
 * this is fixed in the parser rather than repaired downstream.
 */
class DeclarationOrderTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** The value both engines agree on, or a failure naming the engine that disagreed. */
    private String value(String src) {
        RunResult interp = runner.run(compiler.compile(src, "order.ptf"), Engine.INTERPRETER);
        assertFalse(interp.isError(), () -> "interpreter: " + interp.text());
        RunResult truffle = runner.run(compiler.compile(src, "order.ptf"), Engine.TRUFFLE);
        assertFalse(truffle.isError(), () -> "truffle: " + truffle.text());
        assertEquals(interp.text(), truffle.text(), "engines disagree");
        return interp.text();
    }

    private String error(String src) {
        CompileResult result = compiler.compile(src, "order.ptf");
        CompileResult.Failed failed = assertInstanceOf(CompileResult.Failed.class, result,
                () -> "expected a compile error, but the program compiled");
        return failed.error().text();
    }

    // --- construction ------------------------------------------------------

    @Test
    void functionConstructsAStructDeclaredBelowIt() {
        assertEquals("2", value("""
                function useEarly():Early -> Early(2)
                struct Early(v:Int)
                useEarly().v
                """));
    }

    /**
     * The motivating case: the immutable-copy method. A struct is never declared before itself, so
     * this form was impossible in a member block — the workaround was a standalone
     * {@code method Box.dup} below the struct, saying the same thing somewhere else.
     */
    @Test
    void memberBlockConstructsItsOwnType() {
        assertEquals("\"tin\"", value("""
                struct Box(kind:String) {
                  dup():Box -> Box(this.kind)
                }
                Box("tin").dup().kind
                """));
    }

    @Test
    void memberBlockConstructsAStructDeclaredBelowIt() {
        assertEquals("7", value("""
                struct Box(kind:String) {
                  other():Other -> Other(7)
                }
                struct Other(v:Int)
                Box("tin").other().v
                """));
    }

    /** Neither of two structs can be declared first, so mutual reference is the general case. */
    @Test
    void twoStructsConstructEachOther() {
        assertEquals("3", value("""
                struct Celsius(deg:Int) {
                  toF():Fahrenheit -> Fahrenheit(this.deg)
                }
                struct Fahrenheit(deg:Int) {
                  toC():Celsius -> Celsius(this.deg)
                }
                Celsius(3).toF().toC().deg
                """));
    }

    /**
     * A constructor extension body and a member block on the same struct: the shape the block is
     * checked against is the one the extension body finished, and the block still constructs its
     * own type.
     */
    @Test
    void extensionFieldAndMemberBlockOnTheSameStruct() {
        assertEquals("10", value("""
                struct Span(lo:Int, hi:Int) ->
                    let this.width:Int = this.hi - this.lo
                  {
                    stretched():Span -> Span(this.lo, this.hi + this.width)
                  }
                Span(0, 5).stretched().width
                """));
    }

    // --- the decisions only the parser can make ----------------------------

    /** {@code E(literals…)} selects the case carrying that row; it never builds the sealed base. */
    @Test
    void enumLookupBeforeTheEnum() {
        assertEquals("\"tcp/ip\"", value("""
                function http():ResourceType -> ResourceType("tcp/ip")
                enum ResourceType(driver:String) {
                  DatabaseTable("postgres")
                  RemoteHttp("tcp/ip")
                }
                http().driver
                """));
    }

    @Test
    void enumCaseMemberBeforeTheEnum() {
        assertEquals("\"postgres\"", value("""
                function first():ResourceType -> ResourceType.DatabaseTable
                enum ResourceType(driver:String) {
                  DatabaseTable("postgres")
                  RemoteHttp("tcp/ip")
                }
                first().driver
                """));
    }

    @Test
    void enumCaseInAMatchPatternBeforeTheEnum() {
        assertEquals("2", value("""
                function rank(r:ResourceType):Int -> match r {
                  [ResourceType.DatabaseTable] -> 1
                  [ResourceType.RemoteHttp] -> 2
                }
                enum ResourceType(driver:String) {
                  DatabaseTable("postgres")
                  RemoteHttp("tcp/ip")
                }
                rank(ResourceType.RemoteHttp)
                """));
    }

    @Test
    void structPatternBeforeTheStruct() {
        assertEquals("5", value("""
                function unwrap(p:Later):Int -> match p {
                  [Later(v)] -> v
                }
                struct Later(v:Int)
                unwrap(Later(5))
                """));
    }

    @Test
    void sortAliasBeforeItsDeclaration() {
        assertEquals("3", value("""
                function keep(x:Small):Int -> x
                let Small:Type[Int:@ < 10]
                keep(3)
                """));
    }

    // --- what did NOT change -----------------------------------------------

    /** A name no declaration introduces is still an error, at its own call site. */
    @Test
    void anUndeclaredNameIsStillUnknown() {
        assertTrue(error("""
                function f():Int -> Nope(3)
                f()
                """).contains("Unknown function 'Nope'"));
    }

    /**
     * The pre-pass never reports an error of its own: a declaration it cannot read is simply not
     * pre-registered, and the real parse reports the genuine error at the genuine position.
     */
    @Test
    void aMalformedDeclarationIsReportedOnceByTheRealParse() {
        String message = error("""
                function f():Int -> 1
                struct Bad(v:)
                f()
                """);
        assertTrue(message.contains("order.ptf:2:14"),
                () -> "expected the error at the malformed field sort; got: " + message);
        assertEquals(1, message.split("Expected a sort", -1).length - 1,
                () -> "the scout must not report the error a second time; got: " + message);
    }

    // --- the showcase ------------------------------------------------------

    @Test
    void showcaseExampleRuns() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "..", "pontif-playground", "examples", "declaration-order.ptf"));
        assertEquals("\"brass/2 212.00 3,4 tcp/ip=3\"", value(src));
    }

    /**
     * Two declarations of one name is still one error, reported once. The pre-pass registers the
     * same names the real parse does, so it can neither hide the clash nor double-report it.
     */
    @Test
    void aRedeclaredStructIsStillOneError() {
        String message = error("""
                struct Dup(a:Int)
                struct Dup(a:Int, b:Int)
                Dup(4, 9).a
                """);
        assertEquals(1, message.split("Duplicate type alias", -1).length - 1,
                () -> "expected exactly one duplicate report; got: " + message);
    }
}

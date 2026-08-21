package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Stream war generics slice C: a function's [type A, type R] params must be in scope
 * inside a nested fragment's sorts — `&s:[ (el:A) -> d(el) ]` in a generic map.
 * Streams are the motivating use-case for generics (James).
 */
class StreamGenericsTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private CompileResult.Compiled compile(String src) {
        CompileResult r = compiler.compile(src, "m.ptf");
        return assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "should compile; got " + (r instanceof CompileResult.Failed f ? f.error().text() : r));
    }

    @Test void genericMap_definitionCompiles() {
        // The C deliverable: the generic map DEFINITION type-checks (type params reach
        // the fragment's param sort A). A trivial main isolates the definition from any
        // call-site dispatch concerns.
        compile("""
                requires pontif.core.{Stream}
                function map[type A, type R]( s:Stream[A], d:[Dispatch(A):R] ):[Stream[R]] ->
                  &s:[ (el:A) -> d(el) ]
                0""");
    }

    @Test void genericMap_withFragmentInBody_typeChecksWhenCalled() {
        // The whole generic map — including a call — type-checks (type params reach the
        // fragment's sorts in call position too, not just definition position). The
        // call's runtime dispatch (matching a tuple to Stream[A] + a metaref to
        // Dispatch(A)) is a SEPARATE gap, so we assert compilation, not evaluation.
        compile("""
                requires pontif.core.{Stream}
                function double(x:Int):Int -> x * 2
                function map[type A, type R]( s:Stream[A], d:[Dispatch(A):R] ):[Stream[R]] ->
                  &s:[ (el:A) -> d(el) ]
                map({1,2,3}, $double[Int])""");
    }
}

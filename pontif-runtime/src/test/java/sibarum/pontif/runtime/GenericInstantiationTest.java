package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Stream war §8b: explicit type-application of a generic function —
 * {@code map[Int,String](…)}. The type args are supplied (no inference); the parser
 * materializes a concrete specialization (param/return sorts substituted) under a
 * mangled name, and the call dispatches concretely. The conventional turbofish escape
 * hatch, and what makes a generic stream combinator runnable today.
 */
class GenericInstantiationTest {

    private final PontifCompiler compiler = new PontifCompiler();

    private Object run(String src) {
        CompileResult r = compiler.compileAlt(src, "m.ptf");
        CompileResult.Compiled c = assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "should compile; got " + (r instanceof CompileResult.Failed f ? f.error().text() : r));
        return new IrInterpreter(c.program().simplifier()).eval(c.program().module());
    }

    @Test void genericMap_explicitTypeApplication_intToString() {
        // James's canonical example: map[Int,String] over an Int stream with a toString
        // metaref → a String stream.
        assertEquals("(\"1\", \"2\", \"3\", \"4\")", String.valueOf(run("""
                module examples.stream
                requires pontif.core.{Stream, Nothing}
                let s:Stream[Int] = (1,2,3,4)
                function map[type A, type R]( inputStream:Stream[A], mapper:[Dispatch(A):R] ):[Stream[R]] ->
                  &inputStream:[ (element:A) -> mapper(element) ]
                function toString(i:Int):String -> ""+i
                map[Int,String](s, $toString[Int])""")));
    }

    @Test void genericMap_explicitTypeApplication_intToInt() {
        // Same generic map, instantiated at [Int,Int] with a doubling metaref.
        assertEquals("(2, 4, 6, 8)", String.valueOf(run("""
                requires pontif.core.{Stream}
                let s:Stream[Int] = (1,2,3,4)
                function map[type A, type R]( inputStream:Stream[A], mapper:[Dispatch(A):R] ):[Stream[R]] ->
                  &inputStream:[ (element:A) -> mapper(element) ]
                function double(i:Int):Int -> i * 2
                map[Int,Int](s, $double[Int])""")));
    }
}

package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The {@code {T*N}} / {@code {N*T}} tuple-repetition type-spec sugar: a homogeneous aggregate spelled
 * with a repetition operator, expanding to an N-element positional tuple sort (the count is whichever
 * operand is the integer, so the order is free). {@code {Decimal*3}} == {@code {Decimal,Decimal,Decimal}}.
 */
class TupleRepetitionSortTest {

    private String run(String src) {
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt(src, "reps.ptf"), PontifRunner.Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "should run; got " + r.text());
        return r.text();
    }

    @Test
    void repetitionIsAPositionalTuple() {
        assertEquals("6", run("""
                let v:[{Int*3}] = {1, 2, 3}
                match v { [{a, b, c}] -> a + b + c }
                """));
    }

    @Test
    void countOrderAgnostic_andInterchangeableWithTheExplicitTuple() {
        // {3*Int} == {Int*3} == {Int,Int,Int} — transparent, freely interchangeable.
        assertEquals("6", run("""
                let v:[{3*Int}] = {1, 2, 3}
                let w:[{Int, Int, Int}] = v
                match w { [{a, b, c}] -> a + b + c }
                """));
    }

    @Test
    void nestedRepetition() {
        // {2*{2*Int}} — a 2-tuple of 2-tuples (the mat-shape).
        assertEquals("10", run("""
                let m:[{2*{2*Int}}] = {{1, 2}, {3, 4}}
                match m { [{{a, b}, {c, d}}] -> a + b + c + d }
                """));
    }

    @Test
    void decimalTriple() {
        assertEquals("6.0", run("""
                let v:[{Decimal*3}] = {1.0, 2.0, 3.0}
                match v { [{a, b, c}] -> a + b + c }
                """));
    }
}

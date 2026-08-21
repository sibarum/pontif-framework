package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A method call whose receiver is a <em>field access</em> on a plainly struct-typed
 * value — {@code this.inner.bump()}. The receiver's type is the field's declared
 * sort, so method resolution finds the method. Regression for the gap where
 * {@code NarrowingInference.inferFieldAccess} only typed a field through a
 * refinement base and returned {@code null} for a bare struct base, leaving
 * {@code this.field.method()} as "cannot determine the type of the receiver".
 */
class FieldReceiverMethodTest {

    private String run(String src) {
        var r = new PontifRunner().run(
                new PontifCompiler().compile(src, "field-recv.ptf"), Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "expected success; got: " + r.text());
        return r.text();
    }

    @Test
    void methodCallOnFieldAccessReceiver_resolves() {
        assertEquals("42", run("""
                struct Inner(v:Int)
                method Inner.bump():Int -> this.v + 1
                struct Outer(inner:Inner)
                method Outer.go():Int -> this.inner.bump()
                Outer(Inner(41)).go()
                """));
    }

    @Test
    void patternBinderReceiver_resolves() {
        // `i` is bound by the destructure pattern, then used as a method receiver.
        // It must read as a value (`i.bump()`), not the qualified name `i.bump`.
        assertEquals("42", run("""
                struct Inner(v:Int)
                method Inner.bump():Int -> this.v + 1
                struct Outer(inner:Inner, c:Int)
                method Outer.go():Int ->
                  match this
                    [Outer(i, c)] -> i.bump() + c
                    [_] -> 0
                Outer(Inner(41), 0).go()
                """));
    }

    @Test
    void nestedFieldAccessReceiver_resolves() {
        // this.mid.inner.bump() — two field hops before the method.
        assertEquals("8", run("""
                struct Inner(v:Int)
                method Inner.dbl():Int -> this.v + this.v
                struct Mid(inner:Inner)
                struct Top(mid:Mid)
                method Top.run():Int -> this.mid.inner.dbl()
                Top(Mid(Inner(4))).run()
                """));
    }
}

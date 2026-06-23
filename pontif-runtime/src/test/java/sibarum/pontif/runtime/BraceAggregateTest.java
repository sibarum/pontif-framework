package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Brace-aggregates war (docs/brace-aggregates.md), slice 1: a positional aggregate
 * written in braces — {@code {e0, e1, …}} (arity ≥ 2) — lowers to the same
 * {@code _tuple} Record as the paren tuple, so it builds, projects, autoboxes to a
 * {@code Stream}, and concatenates identically. Additive: parens, dict literals
 * ({@code {a=1}}), and the block form ({@code {EXPR}}) all still work — they retire in
 * later slices.
 */
class BraceAggregateTest {

    private String run(String src) {
        PontifRunner.RunResult r = new PontifRunner().run(
                new PontifCompiler().compileAlt(src, "brace.ptf"), PontifRunner.Engine.INTERPRETER);
        assertTrue(!r.isError(), () -> "expected success; got " + r.text());
        return r.text();
    }

    @Test
    void bracePositionalTuple_builds() {
        assertEquals("{1, 2, 3}", run("module m\n{1, 2, 3}"));
    }

    @Test
    void bracePositionalTuple_projects() {
        assertEquals("10", run("module m\nlet t = {10, 20, 30}\nt._0"));
        assertEquals("20", run("module m\nlet t = {10, 20, 30}\nt._1"));
    }

    @Test
    void braceTuple_autoboxesToStream() {
        assertEquals("{1, 2, 3, 4}",
                run("module m\nrequires pontif.core.{Stream}\nlet s:Stream[Int] = {1, 2, 3, 4}\ns"));
    }

    @Test
    void braceTuple_concatenates() {
        assertEquals("{1, 2, 3, 4}",
                run("module m\nrequires pontif.core.{Stream}\n{1, 2} + {3, 4}"));
    }

    @Test
    void parenTuple_stillWorks_additive() {
        assertEquals("{1, 2, 3}", run("module m\n(1, 2, 3)"));
    }

    @Test
    void dictLiteral_stillWorks_additive() {
        assertEquals("1", run("module m\nlet d = {a = 1, b = 2}\nd.a"));
    }

    @Test
    void singleton_isOneElementAggregate() {
        // S2: `{x}` is a 1-element aggregate (the block role moved to parens).
        assertEquals("{5}", run("module m\n{5}"));
        assertEquals("5", run("module m\nlet t = {5}\nt._0"));
    }

    @Test
    void empty_isEmptyAggregate() {
        // S2: `{}` is the empty aggregate — autoboxes to an empty Stream.
        assertEquals("{}", run("module m\nrequires pontif.core.{Stream}\nlet e:Stream[Int] = {}\ne"));
    }

    @Test
    void nestedSingleton_wrapsAComposite() {
        // The case bare parens can't spell: `{{4,5}}` is ONE element that is the
        // aggregate {4,5} — no grouping collapse, no trailing comma.
        assertEquals("{{4, 5}}", run("module m\n{{4, 5}}"));
    }

    @Test
    void parenBlock_isTheBlockNow() {
        // S2: the grouping / let-chain block role lives in parens.
        assertEquals("6", run("module m\n( let y = 5  y + 1 )"));
    }

    // --- S3: tuple SORTS and destructure/match PATTERNS in braces -----------

    @Test
    void tupleSort_paramAndReturn() {
        assertEquals("{true, 3}", run(
                "module m\nfunction swap(p:[{Int, Bool}]):[{Bool, Int}] ->\n"
                        + "  let [{i, b}] = p\n  {b, i}\nswap({3, true})"));
    }

    @Test
    void bareTupleSort_param() {
        assertEquals("8", run(
                "module m\nfunction f(p:{Int, Int}):Int -> let [{a, b}] = p  a + b\nf({3, 5})"));
    }

    @Test
    void destructure_bracePattern() {
        assertEquals("3", run("module m\nlet [{a, b}] = {1, 2}\na + b"));
    }

    @Test
    void nestedTupleSort_andPattern() {
        assertEquals("6", run(
                "module m\nfunction g(p:[{{Int, Int}, Int}]):Int -> let [{{a, b}, c}] = p  a + b + c\n"
                        + "g({{1, 2}, 3})"));
    }

    @Test
    void match_bracePattern() {
        assertEquals("3", run("module m\nlet t = {1, 2}\nmatch t\n  [{a, b}] -> a + b"));
    }

    @Test
    void parenSort_stillWorks_additive() {
        assertEquals("8", run(
                "module m\nfunction f(p:[(Int, Int)]):Int -> let [(a, b)] = p  a + b\nf((3, 5))"));
    }
}

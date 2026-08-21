package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documents two facts about structural pattern matching that aren't obvious
 * from elsewhere in the test suite:
 *
 * <ol>
 *   <li>Partial patterns work — a pattern that lists fewer fields than the
 *       value still matches (subset semantics).
 *       {@link sibarum.pontif.core.symbolic.Refinements#satisfiesStructural}
 *       walks the pattern's member set, not the value's, so extra value
 *       fields are ignored. Combined with the parser's destructuring desugar
 *       (which binds only the fields listed in the pattern), this means
 *       partial destructuring is already supported — no underscore-wildcard
 *       syntax needed for the "I want to ignore a field" case.</li>
 *   <li>An <b>inline</b> struct name in a pattern is a shape label — match
 *       success for these is determined entirely by field shape.
 *       {@code (struct AnyName (x Int))} matches anything with a compatible
 *       {@code x} field. <b>Resolved by the claim rule (Slice 3):</b> a name
 *       bites iff it's a DECLARED nominal type (in the struct registry);
 *       inline S-expr structural sorts are never registered, so their labels
 *       stay cosmetic — there is no nominal type to falsely claim. Declared
 *       names are tested in {@code ClaimRuleTest}.</li>
 * </ol>
 */
class PartialPatternTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src) {
        return runner.run(compiler.compileSexpr(src, "t.ptf"), Engine.INTERPRETER);
    }

    @Test
    void patternListingOnlySomeFields_matchesAndBindsOnlyThose() {
        // Value has x AND y; pattern lists only x. Pattern should match
        // (subset), branch binds x, ignores y.
        String src = """
                (module m
                  ()
                  (let p (struct Point (x Int) (y Int)) (record (x 3) (y 4))
                    (match p
                      ((struct Point (x Int)) (* x 10)))))
                """;
        RunResult r = run(src);
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("30", r.text());
    }

    @Test
    void patternStructName_isCosmetic_matchesPurelyByShape() {
        // The struct's name field is currently just a label; structural
        // matching is by field shape, not by name. So a pattern named
        // "AnyName" matches a value declared as "Point" if the fields line
        // up. Whether this is the desired long-term behavior is captured in
        // a TODO; this test pins down the current semantics.
        String src = """
                (module m
                  ()
                  (let p (struct Point (x Int) (y Int)) (record (x 3) (y 4))
                    (match p
                      ((struct AnyName (x Int)) x))))
                """;
        RunResult r = run(src);
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("3", r.text());
    }

    @Test
    void patternFieldNotInValue_failsAtRuntime() {
        // Pattern lists a field that doesn't exist in the record.
        String src = """
                (module m
                  ()
                  (let p (struct Point (x Int) (y Int)) (record (x 3) (y 4))
                    (match p
                      ((struct Point (z Int)) z))))
                """;
        RunResult r = run(src);
        assertTrue(r.isError(), "expected error — pattern requires field 'z'");
    }

    @Test
    void partialPattern_inDispatch_works() {
        // Function declares a structural-sorted param with only one field;
        // pass a record with more fields — dispatch should accept it.
        String src = """
                (module m
                  ((defn justX ((p (struct Point (x Int)))) Int (field p x)))
                  (call justX (record (x 7) (y 99))))
                """;
        RunResult r = run(src);
        assertFalse(r.isError(), "expected success; got: " + r.text());
        assertEquals("7", r.text());
    }
}

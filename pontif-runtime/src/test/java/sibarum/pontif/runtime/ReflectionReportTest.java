package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reflected-source view: the program re-emitted with declared sorts replaced
 * by inferred narrowings, walked from a variable entrypoint with the
 * no-duplicate-edges discipline (each reachable function emitted once).
 */
class ReflectionReportTest {

    private static String reflect(String src, String entry) {
        ReflectionReport.Result r = ReflectionReport.fromAltSource(src, "t.ptf", null, entry);
        ReflectionReport.Result.Generated g =
                assertInstanceOf(ReflectionReport.Result.Generated.class, r,
                        () -> "expected a reflection; got " + r);
        return g.text();
    }

    @Test
    void return_narrowsTighterThanDeclared() {
        String out = reflect("function inc(x:[Int:@>=1]):Int -> x + 1\ninc(5)", null);
        System.out.println("=== inc ===\n" + out);
        // Entered from inc(5), shallow specialization pins x to ==5, so the return is
        // inferred as exactly ==6 (tighter than the declared Int — the engine computed
        // inc(5) at the type level).
        assertTrue(out.contains("(@ == 6)"), out);
        assertTrue(out.contains("# return was: Int"), out);
    }

    @Test
    void topLevelLet_getsInferredAnnotation() {
        String out = reflect("""
                struct Vec(x:Int, y:Int)
                function +(a:Vec, b:Vec):Vec -> Vec(a.x + b.x, a.y + b.y)
                let v = Vec(1, 2) + Vec(3, 4)
                v
                """, null);
        System.out.println("=== vec ===\n" + out);
        assertTrue(out.contains("struct Vec(x:Int, y:Int)"), out);
        assertTrue(out.contains("let v:Vec ="), out);
    }

    @Test
    void recursion_emittedOnce_asBackEdge() {
        String out = reflect("""
                function factorial(n:[Int:@>=0]):[Int:@>=1] -> match n {
                  [@==0] -> 1
                  [@>0]  -> n * factorial(n - 1)
                }
                factorial(5)
                """, null);
        System.out.println("=== factorial ===\n" + out);
        // factorial's definition appears exactly once (the recursive call is a back-edge
        // in the body text, not a re-expansion).
        int defs = out.split("function factorial\\(", -1).length - 1;
        assertTrue(defs == 1, "factorial should be emitted once, was " + defs + ":\n" + out);
        assertTrue(out.contains("match n {"), out);
        // Shallow specialization: entered from factorial(5), n is pinned to ==5.
        assertTrue(out.contains("(@ == 5)"), out);
    }

    @Test
    void variableEntrypoint_rootsAtNamedFunction() {
        String out = reflect("""
                function helper(x:[Int:@>=1]):Int -> x + 1
                function unused(y:Int):Int -> y - 1
                helper(3)
                """, "helper");
        System.out.println("=== entry=helper ===\n" + out);
        assertTrue(out.contains("entrypoint: helper"), out);
        assertTrue(out.contains("function helper("), out);
        // `unused` is not reachable from helper → not emitted.
        assertTrue(!out.contains("function unused("), out);
    }
}

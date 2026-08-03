package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step-2 first slice: the per-function classification dossier
 * ({@link ClassificationReport}) — read-only assembly of the halting, algebraic,
 * and receipt dimensions the subsystem already derives, one entry per function.
 */
class ClassificationReportTest {

    private static ClassificationReport.Result.Generated gen(String src, String name) {
        ClassificationReport.Result r = ClassificationReport.fromAltSource(src, name);
        return assertInstanceOf(ClassificationReport.Result.Generated.class, r,
                () -> "expected a generated classification; got " + r);
    }

    @Test
    void algebraicIsAutoDiscovered_withoutAnnotation() {
        // `poly` is arithmetic-only → algebraic, discovered WITHOUT any
        // `assign proof f:Algebraic`. `sign` uses a match/comparison → not
        // algebraic. The classifier figures out both on its own.
        var g = gen("""
                module m
                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                function sign(n:Int):Int -> match n
                  [@<0] -> -1
                  [_]   -> 1
                poly(3.0)
                """, "auto.ptf");
        System.out.println(g.text());
        var poly = g.classifications().stream().filter(c -> c.name().equals("poly")).findFirst().orElseThrow();
        var sign = g.classifications().stream().filter(c -> c.name().equals("sign")).findFirst().orElseThrow();
        assertTrue(poly.algebraic(), () -> "poly should auto-classify algebraic:\n" + g.text());
        assertTrue(!sign.algebraic(), () -> "sign (match) should not be algebraic:\n" + g.text());
    }

    @Test
    void algebraicDiscoveryIsTransitive_acrossCalls() {
        // `outer` calls `inner`; both arithmetic → both algebraic. If `inner`
        // weren't algebraic, the fixpoint would drop `outer` too.
        var g = gen("""
                module m
                function inner(x:Decimal):Decimal -> x * x
                function outer(x:Decimal):Decimal -> inner(x) + 1.0
                outer(2.0)
                """, "chain.ptf");
        var names = g.classifications().stream().filter(ClassificationReport.FunctionClassification::algebraic)
                .map(ClassificationReport.FunctionClassification::name).toList();
        assertTrue(names.contains("inner") && names.contains("outer"),
                () -> "both inner and outer should be algebraic:\n" + g.text());
    }

    @Test
    void receiptsAndHalting_areReported() {
        // factorial: a real return obligation that discharges (proved), and it
        // grounds on the base case so NoHalt does NOT claim divergence.
        var g = gen("""
                module m
                function factorial(n:[Int:@>=0]):[Int:@>=1] -> match n
                  [@==0] -> 1
                  [@>0 ] -> n * factorial(n-1)
                factorial(5)
                """, "fac.ptf");
        System.out.println(g.text());
        var fac = g.classifications().stream().filter(c -> c.name().equals("factorial")).findFirst().orElseThrow();
        assertTrue(fac.receipts().startsWith("proved"),
                () -> "factorial's r_0 >= 1 obligation should be proved:\n" + g.text());
        assertTrue(fac.divergence().isEmpty(),
                () -> "factorial grounds on the base case — no divergence proof:\n" + g.text());
    }

    @Test
    void divergentFunction_isReportedNonHalting() {
        // An ungrounded self-loop: NoHalt's greatest fixpoint proves it never
        // completes, and the dossier surfaces that beside the other dimensions.
        var g = gen("""
                module m
                function loop(n:Int):Int -> loop(n)
                loop(3)
                """, "loop.ptf");
        System.out.println(g.text());
        var loop = g.classifications().stream().filter(c -> c.name().equals("loop")).findFirst().orElseThrow();
        assertTrue(loop.divergence().isPresent(),
                () -> "loop(n) -> loop(n) should be proved non-halting:\n" + g.text());
        // Recursive → not algebraic: the acyclicity rule excludes it even though
        // its body is arithmetic (a self-call would otherwise read as an
        // algebraic call). The dossier stays honest across dimensions.
        assertTrue(!loop.algebraic(),
                () -> "a recursive body must not classify algebraic:\n" + g.text());
    }
}

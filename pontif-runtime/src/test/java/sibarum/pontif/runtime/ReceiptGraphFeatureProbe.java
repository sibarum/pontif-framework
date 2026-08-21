package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DIAGNOSTIC RUNNER (temporary) — pushes each post-refactor language feature
 * through the receipt-graph pipeline ({@link ReceiptGraphReport#fromPontifSource})
 * and reports the outcome per probe: GENERATED (with a degeneracy note if the
 * graph is empty / the feature's function is missing a node), FAILED (a
 * parse/compile error the report caught), or THREW (an uncaught throwable from
 * the drafter/issuer — the loud breakage the no-default switches are supposed
 * to produce).
 *
 * <p>This is not a characterization suite; it is the data-collection step for
 * the receipt-graph overhaul. Run it, read the printed table, then codify the
 * findings into real tests. It always "passes" — its job is the console dump.
 */
class ReceiptGraphFeatureProbe {

    /** Probe programs, keyed by the feature they exercise. */
    private static Map<String, String> probes() {
        Map<String, String> p = new LinkedHashMap<>();

        // 0. Baseline — known-good, anchors the run.
        p.put("00-baseline-square", """
                module m
                function square(x:Int):[Int:@>=0] -> x * x
                square(3)
                """);

        // 1. Dispatch overloads discriminated by refinement (CallSig era).
        p.put("01-dispatch-overloads", """
                module m
                function clamp(x:[Int:@<0]):[Int:@>=0] -> 0
                function clamp(x:[Int:@>=0]):[Int:@>=0] -> x
                clamp(-4)
                """);

        // 2. Algebraic classification + metareference AST surface.
        p.put("02-algebraic-assign-proof", """
                module m
                requires pontif.algebra.{Algebraic, eval}
                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic
                eval($poly[Decimal].ast, 3.0)
                """);

        // 3. A dispatch metareference sitting in a body position.
        p.put("03-metaref-in-body", """
                module m
                requires pontif.algebra.{Algebraic, eval}
                function poly(x:Decimal):Decimal -> x*x + 1.0
                assign proof poly:Algebraic
                function useIt(v:Decimal):Decimal -> eval($poly[Decimal].ast, v)
                useIt(2.0)
                """);

        // 4. Conservation proof on the module — does the RECEIPT drafter cope
        //    with a program carrying a std.conservation proof statement?
        p.put("04-conservation-proof", """
                module m
                requires std.conservation.{DataConservative}
                struct Source(name:Int, age:Int)
                struct Target(fullName:Int, years:Int)
                function translate(s:Source):Target -> Target(s.name, s.age)
                proof translate = DataConservative()
                translate(Source(1, 2))
                """);

        // 5. Brace aggregate (anonymous tuple) + match with brace arms.
        p.put("05-brace-aggregate", """
                module m
                function swap(p:[{Int, Bool}]):[{Bool, Int}] ->
                  match p { [{a, b}] -> {b, a} }
                swap({1, true})
                """);

        // 6. Brace-arm match with a bracketed default and a refined return.
        p.put("06-brace-match-default", """
                module m
                function pos(x:Int):[Int:@>=0] -> match x {
                  [@>0] -> x
                  _ -> 0
                }
                pos(5)
                """);

        // 7. Set literal + refined stream query terminal.
        p.put("07-stream-query", """
                module m
                function bigs():Stream[Int] ->
                  let s = {1, 2, 3, 4}
                  &s:[Int:@ > 1].all()
                bigs()
                """);

        // 8. Iterative fold (indexRange form, per the known fold gotchas).
        p.put("08-fold-sum", """
                module m
                function sumTo(n:[Int:@>=0]):Int ->
                  fold(indexRange(0, n), 0, (acc:Int, i:Int) -> acc + i)
                sumTo(5)
                """);

        // 9. assign-proof case-split (the isSparse piecewise obligation).
        p.put("09-assign-proof-split", """
                module m
                function isSparse(x:Int):[Int] -> (x-3)*(x+5)
                assign proof isSparse(x:Int):[
                  (match x
                    [@>=3]  -> this(x)
                    [@<=-6] -> this(x)
                    [_]     -> this(x)
                  ) ->
                  [Int:@ >= -16]
                ]
                isSparse(5)
                """);

        // 10. Instance method + match on the method-call scrutinee.
        p.put("10-method-call-scrutinee", """
                module m
                struct P(a:Int, b:Int)
                method P.id():P -> P(this.a, this.b)
                function f(p:P):Int -> match p.id()
                  [P(x, y)] -> x + y
                f(P(1, 2))
                """);

        // 11. Self-referential struct (recursive data) with a recursive fn.
        p.put("11-recursive-struct", """
                module m
                struct Nil()
                struct Cons(head:Int, tail:[Nil|Cons])
                function len(xs:[Nil|Cons]):[Int:@>=0] -> match xs
                  [Nil()] -> 0
                  [Cons(h, t)] -> 1 + len(t)
                len(Cons(1, Nil()))
                """);

        return p;
    }

    @Test
    void dumpFeatureOutcomes() {
        StringBuilder table = new StringBuilder();
        table.append("\n=== RECEIPT-GRAPH FEATURE PROBE ===\n\n");
        for (Map.Entry<String, String> e : probes().entrySet()) {
            String name = e.getKey();
            String outcome;
            try {
                ReceiptGraphReport.Result r =
                        ReceiptGraphReport.fromPontifSource(e.getValue(), name + ".ptf");
                if (r instanceof ReceiptGraphReport.Result.Generated g) {
                    outcome = classifyGenerated(g.text());
                } else {
                    ReceiptGraphReport.Result.Failed f = (ReceiptGraphReport.Result.Failed) r;
                    outcome = "FAILED   | " + oneLine(f.error());
                }
            } catch (Throwable t) {
                outcome = "THREW    | " + t.getClass().getSimpleName() + ": " + oneLine(String.valueOf(t.getMessage()));
            }
            table.append(String.format("%-28s %s%n", name, outcome));
        }
        table.append("\n(GENERATED = report produced; check the empty/degenerate note.\n")
                .append(" FAILED = parse/compile error caught by the report.\n")
                .append(" THREW = uncaught throwable from the drafter/issuer — hard breakage.)\n");
        System.out.println(table);
    }

    private static String classifyGenerated(String text) {
        boolean anyRoot = text.contains("(") && text.contains(") : r_0")
                || text.contains(") : r_0")
                || text.matches("(?s).*\\w+\\(\\w+_0.*");
        boolean hasObligation = text.contains("  :  r_0");
        boolean notDischarged = text.contains("NOT DISCHARGED");
        boolean nothingToProve = text.contains("nothing to prove");
        // A degenerate graph: the report generated but has no function node at
        // all (the feature's declaration was dropped by the drafter).
        boolean degenerate = !text.contains(") : r_0:");
        StringBuilder note = new StringBuilder("GENERATED");
        if (degenerate) note.append(" [DEGENERATE: no function node drafted]");
        if (hasObligation) note.append(notDischarged ? " [obligation: NOT DISCHARGED]" : " [obligation: discharged]");
        else if (nothingToProve && !degenerate) note.append(" [no obligation]");
        return note.toString();
    }

    private static String oneLine(String s) {
        if (s == null) return "(null)";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > 140 ? flat.substring(0, 140) + "…" : flat;
    }
}

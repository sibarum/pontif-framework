package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the parameter-refinement-as-hypothesis fix: the
 * overloaded-style factorial constrains via the parameter sort
 * ({@code n:[Int:@>0]}) rather than a match-arm guard. The issuer must
 * assume {@code n_0 > 0} from the parameter to discharge
 * {@code n_0 * r_1 >= 1}.
 */
class OverloadedFactorialDischargeTest {

    @Test
    void recursiveOverload_dischargesViaParameterRefinement() {
        String src = """
                module factorial
                function factorial(n:[Int:0])  :[Int:@>=1] -> 1
                function factorial(n:[Int:@>0]):[Int:@>=1] -> n * factorial(n-1)
                factorial(5)
                """;
        ReceiptGraphReport.Result r = ReceiptGraphReport.fromAltSource(src, "factorial.ptf");
        assertInstanceOf(ReceiptGraphReport.Result.Generated.class, r,
                () -> "Expected Generated; got " + r);
        String text = ((ReceiptGraphReport.Result.Generated) r).text();
        System.out.println(text);

        // Base overload: r_0 == 1 satisfies r_0 >= 1 (constant + integer bridge).
        // Recursive overload: n_0 > 0 (from the param) and r_1 >= 1 (IH) give
        // n_0 * r_1 >= 1 via POSITIVE * POSITIVE. Both must discharge.
        assertTrue(text.contains("-> discharged"),
                () -> "Expected at least one discharge:\n" + text);
        assertTrue(!text.contains("NOT DISCHARGED"),
                () -> "Both overloads should discharge r_0 >= 1 now:\n" + text);
    }
}

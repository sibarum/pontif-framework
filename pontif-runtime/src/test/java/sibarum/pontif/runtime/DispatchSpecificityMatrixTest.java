package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The overload-specificity spec, as an executable matrix.
 *
 * <p>Lattice ({@code A ≺ B} = "A strictly more specific than B"):
 * <pre>
 *   trait T1                     trait T2
 *   struct S1  (impl T1, T2)     S1 ≺ T1, S1 ≺ T2
 *   struct S2  (impl T2)         S2 ≺ T2
 *   struct S3 : S1               S3 ≺ S1  (⇒ S3 ≺ T1, S3 ≺ T2)
 *   T1 and T2 are INCOMPARABLE.
 * </pre>
 *
 * <p>Rule: candidates = overloads whose parameter the argument satisfies; the
 * winner is the unique ≺-minimum parameter; no unique minimum ⇒ AMBIGUOUS.
 * Each overload returns a distinct value so the winner is observable; a call
 * with no unique winner errors with "ambiguous" (the {@link #AMB} marker).
 */
class DispatchSpecificityMatrixTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    /** Marker for "no unique most-specific overload — must be a dispatch error". */
    private static final String AMB = "<ambiguous>";

    /** The type lattice + the five candidate overloads. Each test picks a subset by name. */
    private static final String LATTICE = """
            trait T1{ t1:[Method():Int] }
            trait T2{ t2:[Method():Int] }
            struct S1(v:Int)
            assign trait S1:T1 { t1():Int -> 1 }
            assign trait S1:T2 { t2():Int -> 2 }
            struct S2(v:Int)
            assign trait S2:T2 { t2():Int -> 2 }
            struct S3:S1(v:Int)
            """;

    private static final String F_T1 = "function f(x:T1):Int -> 1\n";
    private static final String F_T2 = "function f(x:T2):Int -> 2\n";
    private static final String F_S1 = "function f(x:S1):Int -> 11\n";
    private static final String F_S2 = "function f(x:S2):Int -> 22\n";
    private static final String F_S3 = "function f(x:S3):Int -> 33\n";

    /** Runs LATTICE + overloads + `f(arg)`, returning the winner's value or {@link #AMB}. */
    private String dispatch(String overloads, String argExpr) {
        String src = LATTICE + overloads + "f(" + argExpr + ")";
        CompileResult r = compiler.compile(src, "spec.ptf");
        if (r instanceof CompileResult.Failed f) {
            return isAmbiguity(f.error().text()) ? AMB : "COMPILE_ERR: " + f.error().text();
        }
        PontifRunner.RunResult rr = runner.run(((CompileResult.Compiled) r).program(), Engine.INTERPRETER);
        if (rr.isError()) return isAmbiguity(rr.text()) ? AMB : "RUNTIME_ERR: " + rr.text();
        return rr.text();
    }

    private static boolean isAmbiguity(String msg) {
        String m = msg.toLowerCase();
        return m.contains("ambiguous") || m.contains("overlap");
    }

    // ===== {f(T1), f(T2)} — two unrelated traits =============================

    @Test void traitPair_S1_isAmbiguous() { assertEquals(AMB, dispatch(F_T1 + F_T2, "S1(0)")); }
    @Test void traitPair_S2_picksT2()     { assertEquals("2", dispatch(F_T1 + F_T2, "S2(0)")); }
    @Test void traitPair_S3_isAmbiguous() { assertEquals(AMB, dispatch(F_T1 + F_T2, "S3(0)")); }

    // ===== {f(S1), f(T1)} — struct vs a trait it implements ==================

    @Test void structVsTrait_S1_picksStruct() { assertEquals("11", dispatch(F_S1 + F_T1, "S1(0)")); }
    @Test void structVsTrait_S3_picksStruct() { assertEquals("11", dispatch(F_S1 + F_T1, "S3(0)")); }

    // ===== {f(S1), f(S3)} — the struct-inheritance case ======================

    @Test void structPair_S1_picksS1() { assertEquals("11", dispatch(F_S1 + F_S3, "S1(0)")); }
    @Test void structPair_S3_picksS3() { assertEquals("33", dispatch(F_S1 + F_S3, "S3(0)")); }

    // ===== {f(S1), f(T2)} ====================================================

    @Test void s1OrT2_S1_picksStruct() { assertEquals("11", dispatch(F_S1 + F_T2, "S1(0)")); }
    @Test void s1OrT2_S2_picksT2()     { assertEquals("2",  dispatch(F_S1 + F_T2, "S2(0)")); }
    @Test void s1OrT2_S3_picksStruct() { assertEquals("11", dispatch(F_S1 + F_T2, "S3(0)")); }

    // ===== the COMPILE-TIME consequence ======================================
    //
    // Everything above observes the winner by its VALUE, which the runtime dispatcher decides.
    // The audit of duplicated type-system engines flagged that the compile-time side
    // (StaticDispatch.pickMostSpecific) still calls the specificity kernel without the
    // nominal-ancestry hook the runtime passes, and predicted it would therefore abstain —
    // safely, since abstention never misroutes, but abstaining means the winner's RETURN SORT
    // is unknown to inference, and a claim against it cannot then be judged.
    //
    // These pin the consequence rather than the mechanism: when the overloads differ in return
    // type, the compiler must know which one wins well enough to reject a lie about it. It does.
    // The flagged gap is real in the code and has no observable effect here — worth knowing
    // before anyone threads a registry through to close it.

    /** Overloads differing in return type: the specific one wins and returns String. */
    private static final String RETURN_SPLIT = """
            function g(x:T1):Int -> 1
            function g(x:S1):String -> "s"
            """;

    @Test
    void theSpecificOverloadsReturnSortReachesALetClaim() {
        assertEquals("COMPILE_ERR", firstWord(compileOnly(
                LATTICE + RETURN_SPLIT + "let r:Int = g(S1(0))\nr")));
    }

    @Test
    void theSpecificOverloadsReturnSortReachesAReturnGate() {
        assertEquals("COMPILE_ERR", firstWord(compileOnly(
                LATTICE + RETURN_SPLIT + "function h():Int -> g(S1(0))\nh()")));
    }

    @Test
    void theSpecificOverloadsReturnSortReachesAConstructorArgument() {
        assertEquals("COMPILE_ERR", firstWord(compileOnly(
                LATTICE + RETURN_SPLIT + "struct Holder(n:Int)\nHolder(g(S1(0))).n")));
    }

    @Test
    void theHonestClaimAgainstThatSameReturnSortCompiles() {
        // The control: the gate is judging, not blanket-rejecting a call it cannot resolve.
        assertEquals("\"s\"", compileOnly(LATTICE + RETURN_SPLIT + "let r:String = g(S1(0))\nr"));
    }

    /** Compiles and runs, or returns {@code "COMPILE_ERR: …"}. */
    private String compileOnly(String src) {
        CompileResult r = compiler.compile(src, "spec.ptf");
        if (r instanceof CompileResult.Failed f) return "COMPILE_ERR: " + f.error().text();
        return runner.run(((CompileResult.Compiled) r).program(), Engine.INTERPRETER).text();
    }

    private static String firstWord(String s) {
        int colon = s.indexOf(':');
        return colon < 0 ? s : s.substring(0, colon);
    }
}

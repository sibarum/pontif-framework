package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.DefaultRules;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.TruffleLowering;
import sibarum.pontif.ir.TruffleProgram;
import sibarum.pontif.parser.Parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParserFactorialTest {

    private static Simplifier simplifier() throws Exception {
        return new Simplifier(DefaultRules.production());
    }

    private static Object runInterpreter(String src) throws Exception {
        IrModule module = Parser.parseModule(src, "factorial.ptf");
        Simplifier simp = simplifier();
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    private static Object runTruffle(String src) throws Exception {
        IrModule module = Parser.parseModule(src, "factorial.ptf");
        Simplifier simp = simplifier();
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        TruffleProgram program = new TruffleLowering(compiler).lower(compiled);
        return program.run();
    }

    // --- The headline demo: recursive factorial through match + dispatch ---

    private static final String FACTORIAL_VIA_MATCH = """
            (module factorial
              ((defn factorial ((n (refined Int (>= self 0)))) Int
                 (match n
                   ((refined Int (== self 0)) 1)
                   ((refined Int (> self 0)) (* n (call factorial (- n 1)))))))
              (call factorial 5))
            """;

    @Test
    void factorial_viaMatch_interpreter_yields120() throws Exception {
        assertEquals(120L, runInterpreter(FACTORIAL_VIA_MATCH));
    }

    @Test
    void factorial_viaMatch_truffle_yields120() throws Exception {
        assertEquals(120L, runTruffle(FACTORIAL_VIA_MATCH));
    }

    // --- Alternate shape: recursion via multi-dispatch overloads on refined sorts ---
    // Two declarations with the same name; the dispatcher picks the one whose
    // refinement closes under the call site. No in-body match required.

    private static final String FACTORIAL_VIA_DISPATCH = """
            (module factorial
              ((defn factorial ((n (refined Int (== self 0)))) Int 1)
               (defn factorial ((n (refined Int (> self 0)))) Int
                 (* n (call factorial (- n 1)))))
              (call factorial 6))
            """;

    @Test
    void factorial_viaDispatch_interpreter_yields720() throws Exception {
        assertEquals(720L, runInterpreter(FACTORIAL_VIA_DISPATCH));
    }

    @Test
    void factorial_viaDispatch_truffle_yields720() throws Exception {
        assertEquals(720L, runTruffle(FACTORIAL_VIA_DISPATCH));
    }

    // --- Smaller programs (sanity) ---

    @Test
    void module_withSimpleArithmetic_runsOnBothPaths() throws Exception {
        String src = "(module simple () (+ 1 (* 2 3)))";
        assertEquals(7L, runInterpreter(src));
        assertEquals(7L, runTruffle(src));
    }

    @Test
    void module_withLetBinding_runsOnBothPaths() throws Exception {
        String src = "(module letDemo () (let x Int 10 (+ x 5)))";
        assertEquals(15L, runInterpreter(src));
        assertEquals(15L, runTruffle(src));
    }

    @Test
    void module_withCallAndLetTogether_runsOnBothPaths() throws Exception {
        String src = """
                (module sq
                  ((defn sq ((n Int)) Int (* n n)))
                  (let x Int 7 (call sq x)))
                """;
        assertEquals(49L, runInterpreter(src));
        assertEquals(49L, runTruffle(src));
    }

    // --- Mutual recursion: even? / odd? via dispatch overloads ---

    private static final String EVEN_ODD = """
            (module eo
              ((defn isEven ((n (refined Int (== self 0)))) Int 1)
               (defn isEven ((n (refined Int (> self 0)))) Int (call isOdd (- n 1)))
               (defn isOdd ((n (refined Int (== self 0)))) Int 0)
               (defn isOdd ((n (refined Int (> self 0)))) Int (call isEven (- n 1))))
              (call isEven 4))
            """;

    @Test
    void mutuallyRecursiveEvenOdd_interpreter_yieldsTruthy() throws Exception {
        // isEven(4) → isOdd(3) → isEven(2) → isOdd(1) → isEven(0) = 1
        assertEquals(1L, runInterpreter(EVEN_ODD));
    }

    @Test
    void mutuallyRecursiveEvenOdd_truffle_yieldsTruthy() throws Exception {
        assertEquals(1L, runTruffle(EVEN_ODD));
    }
}

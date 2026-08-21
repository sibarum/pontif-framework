package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sort-transforms slice 1 — TRAIT-OWNED RETURN SHELLS (docs/sort-transforms.md).
 *
 * <p>A trait method may declare its return as a clause-chain shell `[C -> … -> D]`.
 * Callers see the terminus {@code D}; the impl's kernel returns the domain {@code C};
 * {@code TraitDefaultExpansion} wraps every impl/default kernel with the shell, so the
 * output transform is injected by the trait and the impl cannot change it. Forward-only:
 * the kernel's result is wrapped; arg-shells, let-tunneling, and emit are later slices.
 *
 * <p>The plain-function substrate (S2 return-clauses, S7 param-conversions) is proven
 * separately by {@code ClauseChainTest}; this exercises the trait dimension.
 */
class TraitReturnShellTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        CompileResult r = compiler.compile(src, "trait-shell.ptf");
        CompileResult.Compiled c = assertInstanceOf(
                CompileResult.Compiled.class, r, () -> "expected compile success; got " + r);
        PontifRunner.RunResult rr = runner.run(c.program(), Engine.INTERPRETER);
        assertFalse(rr.isError(), () -> "run error: " + rr.text());
        return rr.text();
    }

    private String reject(String src) {
        CompileResult r = compiler.compile(src, "trait-shell.ptf");
        return ((CompileResult.Failed) assertInstanceOf(
                CompileResult.Failed.class, r, "expected a compile rejection")).error().text();
    }

    // 1+2. Abstract kernel + shell — the impl returns the domain C (Int), the shell
    //      scales it to the terminus D; the wrap is mandatory and observable
    //      (kernel alone would be 5; with the shell it is 50).
    @Test
    void abstractKernel_wrappedByShell() {
        assertEquals("50", run("""
                trait Scaled{ compute(n:Int):[Int -> @ * 10 -> Int] }
                struct T(x:Int)
                assign trait T:Scaled {
                  compute(n:Int):Int -> n + 1
                }
                let t = T(0)
                t.compute(4)"""));
    }

    // 3. A DEFAULT body + a shell: the default kernel (n+1=5) is wrapped too (→ 50).
    @Test
    void defaultKernel_wrappedByShell() {
        assertEquals("50", run("""
                trait Scaled{ compute(n:Int):[Int -> @ * 10 -> Int] -> n + 1 }
                struct T(x:Int)
                assign trait T:Scaled {
                }
                let t = T(0)
                t.compute(4)"""));
    }

    // 4. The shell fires through a bare trait-typed parameter (trait-view dispatch),
    //    not just a direct struct receiver.
    @Test
    void shellThroughTraitTypedParam() {
        assertEquals("50", run("""
                trait Scaled{ compute(n:Int):[Int -> @ * 10 -> Int] }
                struct T(x:Int)
                assign trait T:Scaled {
                  compute(n:Int):Int -> n + 1
                }
                function useScaled(s:Scaled):Int -> s.compute(4)
                useScaled(T(0))"""));
    }

    // 5. A shell whose terminus is a DIFFERENT type than the kernel (Int kernel,
    //    String terminus) — the impl returns Int, callers get a String.
    @Test
    void shellChangesReturnType() {
        assertEquals("\"42\"", run("""
                trait Shown{ compute(n:Int):[Int -> @ + "" -> String] }
                struct T(x:Int)
                assign trait T:Shown {
                  compute(n:Int):Int -> n + 1
                }
                T(0).compute(41)"""));
    }

    // 6. The kernel must return the shell's DOMAIN — a kernel returning the wrong
    //    base type is rejected (the option-(a) check inside TraitDefaultExpansion).
    @Test
    void kernelMustReturnShellDomain() {
        String err = reject("""
                trait Scaled{ compute(n:Int):[Int -> @ * 10 -> Int] }
                struct T(x:Int)
                assign trait T:Scaled {
                  compute(n:Int):String -> "x"
                }
                T(0)""");
        assertTrue(err.contains("shell expects the kernel to produce Int"),
                () -> "kernel/shell domain mismatch should be rejected; got: " + err);
    }
}

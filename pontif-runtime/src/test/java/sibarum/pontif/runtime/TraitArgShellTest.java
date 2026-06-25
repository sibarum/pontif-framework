package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sort-transforms slice 2 — TRAIT-OWNED ARGUMENT SHELLS (docs/sort-transforms.md).
 *
 * <p>A trait method parameter may be a clause-chain shell `[A -> … -> B]`: the caller
 * passes the domain {@code A} (what dispatch keys on), the impl's kernel sees the codomain
 * {@code B}, and {@code TraitDefaultExpansion} rewrites the registered param to {@code A}
 * and rebinds {@code let p = shell(p)} before the body — input adaptation the trait owns
 * and the impl can't change. Mirror of the return shell (slice 1); composes with it and
 * with default bodies.
 */
class TraitArgShellTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String run(String src) {
        CompileResult r = compiler.compileAlt(src, "trait-argshell.ptf");
        CompileResult.Compiled c = assertInstanceOf(
                CompileResult.Compiled.class, r, () -> "expected compile success; got " + r);
        PontifRunner.RunResult rr = runner.run(c.program(), Engine.INTERPRETER);
        assertFalse(rr.isError(), () -> "run error: " + rr.text());
        return rr.text();
    }

    private String reject(String src) {
        CompileResult r = compiler.compileAlt(src, "trait-argshell.ptf");
        return ((CompileResult.Failed) assertInstanceOf(
                CompileResult.Failed.class, r, "expected a compile rejection")).error().text();
    }

    // 1+2. Abstract kernel + arg shell — the caller passes 4, the trait's shell delivers
    //      5 to the kernel (mandatory and observable: the kernel sees 5, not 4).
    @Test
    void abstractKernel_seesShelledArgument() {
        assertEquals("15", run("""
                trait Adder{ add(n:[Int -> @ + 1 -> Int]):Int }
                struct Base(b:Int)
                assign trait Base:Adder {
                  add(n:Int):Int -> this.b + n
                }
                let x = Base(10)
                x.add(4)"""));
    }

    // 3. A DEFAULT body + an arg shell: the default kernel sees the codomain too.
    @Test
    void defaultKernel_seesShelledArgument() {
        assertEquals("15", run("""
                trait Adder{ add(n:[Int -> @ + 1 -> Int]):Int -> this.b + n }
                struct Base(b:Int)
                assign trait Base:Adder {
                }
                let x = Base(10)
                x.add(4)"""));
    }

    // 4. The shell fires through a bare trait-typed parameter (trait-view dispatch).
    @Test
    void shellThroughTraitTypedParam() {
        assertEquals("15", run("""
                trait Adder{ add(n:[Int -> @ + 1 -> Int]):Int }
                struct Base(b:Int)
                assign trait Base:Adder {
                  add(n:Int):Int -> this.b + n
                }
                function useAdder(a:Adder):Int -> a.add(4)
                useAdder(Base(10))"""));
    }

    // 5. A type-CHANGING arg shell: the caller passes an Int, the kernel sees a String.
    @Test
    void shellChangesArgumentType() {
        assertEquals("\"42!\"", run("""
                trait Shown{ tell(n:[Int -> @ + "" -> String]):String }
                struct Box(prefix:Int)
                assign trait Box:Shown {
                  tell(n:String):String -> n + "!"
                }
                Box(0).tell(42)"""));
    }

    // 6. The kernel must declare the shell's CODOMAIN — a wrong kernel param is rejected.
    @Test
    void kernelMustDeclareShellCodomain() {
        String err = reject("""
                trait Shown{ tell(n:[Int -> @ + "" -> String]):String }
                struct Box(label:String)
                assign trait Box:Shown {
                  tell(n:Int):String -> this.label
                }
                Box("x=")""");
        assertTrue(err.contains("argument shell delivers String"),
                () -> "kernel/shell codomain mismatch should be rejected; got: " + err);
    }

    // 7. Argument shell AND return shell on one method — both fire, composed.
    @Test
    void argAndReturnShellsCompose() {
        // caller passes 4 → arg shell +1 = 5 → kernel this.b + 5 = 15 → return shell ×10 = 150.
        assertEquals("150", run("""
                trait Both{ go(n:[Int -> @ + 1 -> Int]):[Int -> @ * 10 -> Int] }
                struct Base(b:Int)
                assign trait Base:Both {
                  go(n:Int):Int -> this.b + n
                }
                Base(10).go(4)"""));
    }

    // 8. An impl may not redefine the shell — the argument shell belongs on the trait.
    @Test
    void implCannotOwnTheArgShell() {
        String err = reject("""
                trait Adder{ add(n:[Int -> @ + 1 -> Int]):Int }
                struct Base(b:Int)
                assign trait Base:Adder {
                  add(n:[Int -> @ + 1 -> Int]):Int -> this.b + n
                }
                Base(10)""");
        assertTrue(err.contains("belongs on the TRAIT method"),
                () -> "impl-side arg shell should be rejected; got: " + err);
    }
}

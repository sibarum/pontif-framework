package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.pontif.runtime.PontifRunner.Engine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The statement kinds the resolver passes used to skip.
 *
 * <p>Four rewriting passes — name resolution, destructure resolution, struct-literal rewriting,
 * method/operator resolution — each ended their statement switch in {@code default -> stmt}, under
 * a comment naming the kinds that "carry no expression". Those lists were written before
 * {@code assign proof} and {@code conductor} existed and were wrong ever since; because
 * {@code default} is not exhaustive, nothing made them wrong out loud
 * (docs/parser-linker-refactor.md item 3, which predicted exactly this).
 *
 * <p>Three real defects were behind that one clause:
 *
 * <ul>
 *   <li>a return proof in a REQUIRED module kept its bare target name while the function it
 *       proves became {@code mod/f}, so a valid program was rejected outright with "assign proof
 *       references unknown function";</li>
 *   <li>a conductor state initializer calling an imported function reached the runtime unresolved
 *       — "No function named 'startAt' is declared";</li>
 *   <li>a conductor state initializer containing a method call failed to compile with the
 *       internal-sounding "MethodResolver must eliminate MethodCall before IrCompiler" — the pass
 *       meant to eliminate it never looked inside a conductor.</li>
 * </ul>
 *
 * <p>The switches are exhaustive now, so the next statement kind cannot be added without every one
 * of these sites either handling it or saying in writing that it needs nothing.
 */
class StatementKindResolutionTest {

    @TempDir
    Path dir;

    private String run(String entry) throws Exception {
        Files.writeString(dir.resolve("entry.ptf"), entry);
        var r = new PontifCompiler().compile(entry, "entry.ptf", dir);
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        var out = new PontifRunner().run(r, Engine.INTERPRETER);
        assertFalse(out.isError(), () -> "expected a clean run; got: " + out.text());
        return out.text();
    }

    /** Runs and returns what the program PRINTED — a conductor's state is observed by emitting it. */
    private String runOut(String entry) throws Exception {
        java.io.PrintStream origOut = System.out;
        java.io.PrintStream origErr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(
                    new java.io.ByteArrayOutputStream(), true, java.nio.charset.StandardCharsets.UTF_8));
            run(entry);
            return captured.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    private void module(String name, String source) throws Exception {
        Files.writeString(dir.resolve(name + ".ptf"), source);
    }

    @Test
    void aReturnProofInARequiredModuleStillBindsToItsFunction() throws Exception {
        // The proof grants [Int:@>=-16] for a function declaring only [Int]. Nothing about that
        // depends on which module it lives in — but the proof's target name was left bare while
        // the function was FQN'd, so binding failed and the program was refused.
        module("lib", """
                module lib
                exports @.{isSparse}

                function isSparse(x:Int):[Int] -> (x-3)*(x+5)
                assign proof isSparse(x:Int):[
                  (match x
                    [@>=3]  -> this(x)
                    [@<=-6] -> this(x)
                    [_]     -> this(x)
                  ) ->
                  [Int:@ >= -16]
                ]
                """);
        assertEquals("9", run("""
                module app
                requires lib.{isSparse}

                isSparse(4)
                """));
    }

    @Test
    void aConductorStateInitializerCallsAnImportedFunction() throws Exception {
        // The initializer is COMPILED (IrCompiler builds the conductor's state seed from it), so
        // an unresolved call in it is a runtime failure, not a dormant one.
        module("seed", """
                module seed
                exports @.{startAt}

                function startAt():Int -> 41
                """);
        assertEquals("42", runOut("""
                module app
                requires seed.{startAt}
                requires pontif.events.{StdOut}

                struct Tick(n:Int)
                conductor Counter {
                  n:Cell[Int](startAt()),
                  onTick(e:Tick) -> let this.n.apply([(x:Int) -> x + 1])  emit StdOut("" + this.n.next)  e
                }
                spawn Counter
                main ( emit Tick(1)  0 )
                """));
    }

    @Test
    void aConductorStateInitializerUsesAMethodCall() throws Exception {
        // Same initializer, a method call instead of an import: the resolver that turns
        // `Vec(20, 21).total()` into a dispatch call has to visit it, or compilation dies on the
        // MethodCall placeholder it left behind.
        assertEquals("42", runOut("""
                module app
                requires pontif.events.{StdOut}

                struct Tick(n:Int)
                struct Vec(x:Int, y:Int)
                method Vec.total():Int -> this.x + this.y

                conductor Counter {
                  n:Cell[Int](Vec(20, 21).total()),
                  onTick(e:Tick) -> let this.n.apply([(x:Int) -> x + 1])  emit StdOut("" + this.n.next)  e
                }
                spawn Counter
                main ( emit Tick(1)  0 )
                """));
    }

    @Test
    void aConductorStateInitializerConstructsAnImportedStruct() throws Exception {
        // The struct-literal rewriter's turn: an imported struct's constructor parses as a Call
        // and must be rewritten to a Record, inside a conductor as anywhere else.
        module("shapes", """
                module shapes
                exports @.{Vec}

                struct Vec(x:Int, y:Int)
                """);
        assertEquals("21", runOut("""
                module app
                requires shapes.{Vec}
                requires pontif.events.{StdOut}

                struct Tick(n:Int)
                conductor Counter {
                  n:Cell[Int](Vec(20, 21).x),
                  onTick(e:Tick) -> let this.n.apply([(x:Int) -> x + 1])  emit StdOut("" + this.n.next)  e
                }
                spawn Counter
                main ( emit Tick(1)  0 )
                """));
    }
}

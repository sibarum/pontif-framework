package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.PontifParser;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CallNameCheck (the upstream diagnostic fix): an unresolved free/static call name fails with a
 * clear, correctly-located "Unknown function" error BEFORE operator/method resolution — instead of
 * the misleading "Cannot determine the type of the receiver of method '…'" it used to cascade into
 * when the unresolved call fed an operator/method (the un-imported TractionCD.of case).
 */
class UnresolvedCallDiagnosticTest {

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String compileError(String src) {
        CompileResult r = compiler.compile(src, "t.ptf");
        return r instanceof CompileResult.Failed f ? f.error().text() : null;
    }

    @Test
    void unknownCall_feedingOperatorAndMethod_namesTheCall_notTheReceiver() {
        // The shape that used to misreport: an unresolved call (Foo.of) as an operand of `/`,
        // whose result is the receiver of `.dbl()`.
        String err = compileError("""
                struct T(v:Decimal)
                function /(a:T, b:T):T -> T(a.v / b.v)
                method T.dbl():T -> T(this.v * 2.0)
                ( Foo.of(1.0) / Foo.of(2.0) ).dbl()""");
        assertTrue(err != null && err.contains("Foo.of"),
                () -> "should name the unresolved call 'Foo.of'; got: " + err);
        assertTrue(err.contains("not in scope") || err.contains("Unknown function"),
                () -> "should be the clear unknown-call message; got: " + err);
        assertFalse(err.contains("Cannot determine the type of the receiver"),
                () -> "must NOT cascade into the misleading receiver error; got: " + err);
    }

    @Test
    void unimportedCall_inLinkedProject_namesTheCall() {
        Map<String, String> src = new LinkedHashMap<>();
        src.put("lib", """
                module lib
                exports @.{T}
                struct T(v:Decimal)
                method T.dbl():T -> T(this.v * 2.0)
                """);
        src.put("app", """
                module app
                requires lib.{T}
                ( Bar.of(1.0) ).dbl()
                """);   // Bar is imported from nowhere
        Map<String, IrModule> mods = new LinkedHashMap<>();
        src.forEach((n, s) -> {
            try { mods.put(n, PontifParser.parseModule(s, n + ".ptf")); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        CompileResult r = compiler.compileProject(mods, "app");
        assertInstanceOf(CompileResult.Failed.class, r, "expected a link failure for the unknown call");
        String err = ((CompileResult.Failed) r).error().text();
        assertTrue(err.contains("Bar.of"), () -> "should name 'Bar.of'; got: " + err);
        assertFalse(err.contains("Cannot determine the type of the receiver"),
                () -> "must not cascade into the receiver error; got: " + err);
    }

    @Test
    void bareFieldMethodCall_hintsThisNotRequires() {
        // Classic slip: `left.walk()` inside a member body instead of `this.left.walk()`. A bare
        // field isn't in local scope, so `left.walk` is read as a `module.function` call and fails
        // here — the message should point at `this`, not suggest a `requires` import.
        String err = compileError("""
                trait Expr {
                  walk:[Method():Stream[Expr]]
                }
                struct AddOp(left:Expr, right:Expr)
                assign trait AddOp:Expr {
                  walk():Stream[Expr] -> left.walk() + right.walk() + {this}
                }""");
        assertTrue(err != null && err.contains("left.walk"),
                () -> "should name the unresolved call; got: " + err);
        assertTrue(err != null && err.contains("field of the enclosing type")
                        && err.contains("this.left.walk"),
                () -> "should hint at `this.left.walk`; got: " + err);
        assertFalse(err.contains("imported with `requires`"),
                () -> "must NOT suggest a requires import for a same-type field; got: " + err);
    }

    // --- non-regression: legitimate deferrals must NOT be flagged ---

    @Test
    void paramCallable_invokedAsCall_isNotFlagged() {
        // d is a callable parameter, invoked d(d(x)) — a local binding, not a free function.
        CompileResult r = compiler.compile("""
                function inc(x:Int):Int -> x + 1
                function twice(d:[Dispatch(Int):Int], x:Int):Int -> d(d(x))
                twice($inc[Int], 5)""", "t.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "param-callable invocation should compile; got: "
                        + (r instanceof CompileResult.Failed f ? f.error().text() : r));
        assertTrue(runner.run(r, Engine.INTERPRETER).text().equals("7"));
    }

    @Test
    void operatorAndMethodOnUserType_compile() {
        // A user operator result fed to a user method — both resolve; no false positive.
        CompileResult r = compiler.compile("""
                struct T(v:Decimal)
                function T.of(x:Decimal):T -> T(x)
                function /(a:T, b:T):T -> T(a.v / b.v)
                method T.dbl():T -> T(this.v * 2.0)
                ( T.of(6.0) / T.of(2.0) ).dbl().v""", "t.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "operator+method on a user type should compile; got: "
                        + (r instanceof CompileResult.Failed f ? f.error().text() : r));
    }
}

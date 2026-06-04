package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the README's "A taste of the language" snippet evaluates as advertised. */
class ReadmeSnippetTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "readme.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void readmeSnippet_evaluatesTo25() throws Exception {
        String src = """
                struct Point(x:Int, y:Int)

                let Sized:Type{
                  magnitude:[Function():Int]
                }

                assign trait Point:Sized {
                  magnitude():Int -> self.x * self.x + self.y * self.y
                }

                function describe(d:Sized):Int -> d.magnitude()

                describe(Point(3, 4))
                """;
        assertEquals(25L, run(src));
    }

    @Test
    void readmeNarrowingSnippet_evaluatesTo124() throws Exception {
        String src = """
                function factorial(n:[Int:0])  :Int -> 1
                function factorial(n:[Int:@>0]):Int -> n * factorial(n-1)

                function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1

                function sign(n:Int):Int -> match n {
                  [@<0 ] -> -1
                  [@==0] ->  0
                  [@>0 ] ->  1
                }

                factorial(5) + inc(4) + sign(-7)
                """;
        assertEquals(124L, run(src));
    }

    @Test
    void readmeDecimalSnippet_growsAndComparesApprox() throws Exception {
        String src = """
                struct Account(balance:[Decimal:@>=0], rate:Decimal)

                function grow(a:Account):Decimal -> a.balance * (1.0 + a.rate)

                let acct = Account(100.0, 0.05)
                grow(acct) ~= 105.0
                """;
        assertEquals(true, run(src));
    }

    @Test
    void readmeDestructuringSnippet_invertsAndBinds() throws Exception {
        String src = """
                struct Ternion(z:Decimal, n:Decimal, w:Decimal)

                method Ternion.inv():Ternion ->
                  match self {
                    [Ternion(z, 0, w)] -> Ternion(w, 0, z+1)
                    [Ternion(z, n, w)] -> Ternion(w, 1.0/n, z)
                  }

                let [Ternion(first, second, third)] = Ternion(2, 0, 5).inv()
                first + third
                """;
        Object result = run(src);
        assertEquals(0, new java.math.BigDecimal("8")
                .compareTo((java.math.BigDecimal) result));
    }

    @Test
    void readmeSumTypeSnippet_dispatchesOnUnion() throws Exception {
        String src = """
                struct Circle(r:Decimal)
                struct Rect(w:Decimal, h:Decimal)

                function area(s:[Circle|Rect]):Decimal -> match s {
                  [Circle(r)]  -> 3.14 * r * r
                  [Rect(w, h)] -> w * h
                }

                area(Rect(3.0, 4.0))
                """;
        Object result = run(src);
        assertEquals(0, new java.math.BigDecimal("12")
                .compareTo((java.math.BigDecimal) result));
    }

    // --- Conservation receipts (need the linker + gates: PontifCompiler path) ---

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String runGated(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "readme.ptf");
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    @Test
    void readmeConservationSnippet_conservativeTranslationCompilesAndRuns() {
        assertEquals("3", runGated("""
                requires std.conservation.{DataConservative}

                struct Source(name:Int, age:Int, email:Int)
                struct Target(fullName:Int, years:Int, contact:Int)

                function translate(s:Source):Target ->
                  {fullName = s.name, years = s.age + 1, contact = s.email}

                proof translate = DataConservative()

                translate(Source(1, 2, 3)).years
                """));
    }

    @Test
    void readmeConservationSnippet_droppedAttributeRejects_withTheReceipt() {
        // The README's "delete contact from Target" direction: the same proof
        // now fails, and the error carries the ledger.
        PontifCompiler.CompileResult r = compiler.compileAlt("""
                requires std.conservation.{DataConservative}

                struct Source(name:Int, age:Int, email:Int)
                struct Target(fullName:Int, years:Int)

                function translate(s:Source):Target ->
                  {fullName = s.name, years = s.age + 1}

                proof translate = DataConservative()

                translate(Source(1, 2, 3)).years
                """, "readme.ptf");
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                PontifCompiler.CompileResult.Failed.class, r);
        String err = ((PontifCompiler.CompileResult.Failed) r).error().text();
        org.junit.jupiter.api.Assertions.assertTrue(
                err.contains("Conservation proof") && err.contains("translate"), () -> err);
        org.junit.jupiter.api.Assertions.assertTrue(err.contains("UNTOUCHED"), () -> err);
        org.junit.jupiter.api.Assertions.assertTrue(err.contains("s_0.email"), () -> err);
    }

    @Test
    void readmeConservationSnippet_swapWitnessesReversibility() {
        assertEquals("1", runGated("""
                requires std.conservation.{Reversible}

                function swap(p:[(Int, Bool)]):[(Bool, Int)] ->
                  match p { [(a, b)] -> (b, a) }

                proof swap = Reversible()

                let [(x, y)] = swap((1, true)) y
                """));
    }
}

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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins every {@code ```pontif } snippet in {@code README.md}: each block here is
 * the README's code verbatim (minus the {@code # → …} comments), and the README
 * compiles or this build fails. Blocks appear in README order.
 *
 * <p>Two harnesses: {@link #run} drives the bare IR path (parse → simplify →
 * compile → interpret) for self-contained value snippets; {@link #runGated} drives
 * the full {@code PontifCompiler} (linker + the return-verification, conservation,
 * and construction gates) for snippets using {@code module}/{@code requires},
 * {@code proof}, synthesis {@code ;}, or sort aliases.
 */
class ReadmeSnippetTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "readme.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private String runGated(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "readme.ptf");
        assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                () -> "expected compile success; got: "
                        + ((PontifCompiler.CompileResult.Failed) r).error().text());
        return runner.run(r, PontifRunner.Engine.INTERPRETER).text();
    }

    private PontifCompiler.CompileResult.Failed rejectGated(String src) {
        PontifCompiler.CompileResult r = compiler.compileAlt(src, "readme.ptf");
        return assertInstanceOf(PontifCompiler.CompileResult.Failed.class, r,
                "expected a compile rejection");
    }

    // --- Read it top to bottom (the opener) ---------------------------------

    @Test
    void readmeOpener_evaluatesTo150() {
        assertEquals("150", runGated("""
                module ledger
                requires std.stream.{Element, Leaf}

                struct Account(balance:[Int:@>=0])

                method Account.deposit(n:Int):Account -> match n {
                  [@>0]  -> Account(this.balance + n)
                  [@<=0] -> this
                }

                function totalIn(q:[Element|Leaf]):Int -> match q {
                  [Element] -> q.head + totalIn(q.rest)
                  [Leaf]    -> 0
                }

                Account(0).deposit(totalIn(Element(100, Element(50, Leaf())))).balance
                """));
    }

    // --- Functions, overloads, and proven returns ---------------------------

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

    // --- Structs and methods -------------------------------------------------

    @Test
    void readmeMethodSnippet_evaluatesTo25() throws Exception {
        String src = """
                struct Vec(x:Int, y:Int)

                method Vec.norm():Int -> this.x * this.x + this.y * this.y

                Vec(3, 4).norm()
                """;
        assertEquals(25L, run(src));
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

    // --- Traits: DATA attributes + bidirectional coercion --------------------

    @Test
    void readmeTraitProducerSnippet_evaluatesTo1() {
        assertEquals("1", runGated("""
                let Heavyish:Type{ weight:[Int:@>0] }

                struct Ipsum(name:Int)

                assign trait Ipsum:Heavyish {
                  weight:Int -> 1
                }

                let i = Ipsum(5)
                i.weight
                """));
    }

    @Test
    void readmeTraitCoercionSnippet_roundTripsTo5() {
        assertEquals("5", runGated("""
                let Heavyish:Type{ weight:[Int:@>0] }

                struct Ipsum(name:Int)

                assign trait Ipsum:Heavyish {
                  weight:Int -> 1
                }

                let i = Ipsum(5)
                let h:Heavyish = i
                let back:Ipsum = h
                back.name
                """));
    }

    // --- Type extension (the univocal construct) -----------------------------

    @Test
    void readmeSubtypesSnippet_demoteAndPromote() {
        assertEquals("5", runGated("""
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)

                let p = Point3D(2, 3, 5)
                let flat:Point = p
                let back:[Point3D:@.z==0] = flat;
                flat.x + flat.y + back.z
                """));
    }

    // --- Operator overloading ------------------------------------------------

    @Test
    void readmeOperatorOverloadSnippet_evaluatesTo225() throws Exception {
        String src = """
                struct Money(cents:Int)

                function +(a:Money, b:Money):Money -> Money(a.cents + b.cents)

                (Money(150) + Money(75)).cents
                """;
        assertEquals(225L, run(src));
    }

    // --- Proofs and synthesis ------------------------------------------------

    @Test
    void readmeUnprovableReturnSnippet_isRejected() {
        PontifCompiler.CompileResult.Failed f = rejectGated("""
                function f(x:Int):[Int:@>=-16] -> (x-3)*(x+5)
                f(0)
                """);
        assertTrue(f.error().text().contains("Cannot prove the declared return refinement of 'f'"),
                () -> f.error().text());
    }

    @Test
    void readmeAssignProofSnippet_provesByRegion_evaluatesTo105() {
        assertEquals("105", runGated("""
                function isSparse(x:Int):[Int] -> (x-3)*(x+5)

                assign proof isSparse(x:Int):[
                  (match x
                    [@>=3]  -> this(x)
                    [@<=-6] -> this(x)
                    [_]     -> this(x)
                  ) ->
                  [Int:@ >= -16]
                ]

                isSparse(10)
                """));
    }

    @Test
    void readmeSynthesisSnippet_destructuredReturnRefinement_evaluatesTo25() {
        assertEquals("25", runGated("""
                struct Vec(x:Int, y:Int)

                function normSq(v:[Vec.{x, y}]):[
                  let s:Int = x ^ 2 + y ^ 2 ->
                  Int:@==s
                ];

                normSq(Vec(3, 4))
                """));
    }

    @Test
    void readmeFunctionSynthesisSnippet_evaluatesTo12() {
        assertEquals("12", runGated("""
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)

                function promote(point:[Point.{x, y}], z:Int):Point3D{x, y, z};

                promote(Point(2, 3), 7).x + promote(Point(2, 3), 7).y + promote(Point(2, 3), 7).z
                """));
    }

    @Test
    void readmeMetareferenceSnippet_evaluatesTo7() throws Exception {
        String src = """
                function inc(x:Int):Int -> x + 1
                function twice(d:[Dispatch(Int):Int], x:Int):Int -> d(d(x))

                twice($inc[Int], 5)
                """;
        assertEquals(7L, run(src));
    }

    @Test
    void readmeReusableSortSnippet_evaluatesTo6() {
        assertEquals("6", runGated("""
                let Positive:Type[[Int:@>0]]

                function step(n:Positive):Positive -> n + 1

                step(5)
                """));
    }

    // --- Conservation receipts (the second ledger) ---------------------------

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
        PontifCompiler.CompileResult.Failed f = rejectGated("""
                requires std.conservation.{DataConservative}

                struct Source(name:Int, age:Int, email:Int)
                struct Target(fullName:Int, years:Int)

                function translate(s:Source):Target ->
                  {fullName = s.name, years = s.age + 1}

                proof translate = DataConservative()

                translate(Source(1, 2, 3)).years
                """);
        String err = f.error().text();
        assertTrue(err.contains("Conservation proof") && err.contains("translate"), () -> err);
        assertTrue(err.contains("UNTOUCHED"), () -> err);
        assertTrue(err.contains("s_0.email"), () -> err);
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

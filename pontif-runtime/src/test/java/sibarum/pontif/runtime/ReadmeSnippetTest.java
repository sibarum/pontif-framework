package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.types.StringValue;
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

    /** A gated run plus whatever the program wrote to stdout (for the {@code emit StdOut} snippet). */
    private record StdoutRun(String value, String stdout) {}

    private StdoutRun runCapturingStdout(String src) {
        java.io.PrintStream orig = System.out;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
            String value = runGated(src);
            return new StdoutRun(value, buf.toString(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            System.setOut(orig);
        }
    }

    // --- Read it top to bottom (the opener) ---------------------------------

    @Test
    void readmeOpener_evaluatesTo150() {
        assertEquals("150", runGated("""
                module ledger

                struct Account(balance:[Int:@>=0])
                struct Txns(amount:Int, rest:[Txns|Done])
                struct Done()

                method Account.deposit(n:Int):Account -> match n {
                  [@>0]  -> Account(this.balance + n)
                  [@<=0] -> this
                }

                function totalIn(ts:[Txns|Done]):Int -> match ts {
                  [Txns] -> ts.amount + totalIn(ts.rest)
                  [Done] -> 0
                }

                Account(0).deposit(totalIn(Txns(100, Txns(50, Done())))).balance
                """));
    }

    // --- Functions, overloads, and proven returns ---------------------------

    @Test
    void readmeNarrowingSnippet_evaluatesTo124() {
        // Gated: factorial's [Int:@>=1] and inc's [Int:@>1] are real return
        // obligations — this drives the return-verification gate, not just eval.
        assertEquals("124", runGated("""
                function factorial(n:[Int:0])  :[Int:@>=1] -> 1
                function factorial(n:[Int:@>0]):[Int:@>=1] -> n * factorial(n-1)

                function inc(x:[Int:@>=1]):[Int:@>1] -> x + 1

                function sign(n:Int):Int -> match n {
                  [@<0 ] -> -1
                  [@==0] ->  0
                  [@>0 ] ->  1
                }

                factorial(5) + inc(4) + sign(-7)
                """));
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

    @Test
    void readmeTuplePatternSnippet_evaluatesTo11() throws Exception {
        String src = """
                function score(p:[{Int, Int}]):Int -> match p {
                  [{0, 0}] -> 0
                  [{0, y}] -> y
                  [{x, y}] -> x * y
                }

                score({0, 5}) + score({2, 3})
                """;
        assertEquals(11L, run(src));
    }

    // --- Traits: methods (the primary example) -------------------------------

    @Test
    void readmeTraitMethodSnippet_evaluatesTo1350() {
        assertEquals("1350", runGated("""
                trait Payable{ weeklyPay:[Method():Int] }

                struct Hourly(rate:Int, hours:Int)
                struct Commissioned(base:Int, sales:Int, cut:Int)

                assign trait Hourly:Payable {
                  weeklyPay():Int -> this.rate * this.hours
                }
                assign trait Commissioned:Payable {
                  weeklyPay():Int -> this.base + this.sales * this.cut
                }

                function payroll(w:Payable):Int -> w.weeklyPay()

                payroll(Hourly(20, 40)) + payroll(Commissioned(300, 10, 25))
                """));
    }

    // --- Traits: DATA attributes + bidirectional coercion --------------------

    @Test
    void readmeTraitProducerSnippet_evaluatesTo12() {
        assertEquals("12", runGated("""
                trait Boxed{ area:[Int:@>0] }

                struct Rect(w:[Int:@>0], h:[Int:@>0])

                assign trait Rect:Boxed {
                  area:Int -> this.w * this.h
                }

                let r = Rect(4, 3)
                r.area
                """));
    }

    @Test
    void readmeTraitCoercionSnippet_roundTripsTo4() {
        assertEquals("4", runGated("""
                trait Boxed{ area:[Int:@>0] }

                struct Rect(w:[Int:@>0], h:[Int:@>0])

                assign trait Rect:Boxed {
                  area:Int -> this.w * this.h
                }

                let r = Rect(4, 3)
                let b:Boxed = r
                let back:Rect = b
                back.w
                """));
    }

    // --- Traits: logic in the sorts (return shell + function arg/return) ------

    @Test
    void readmeTraitReturnShellSnippet_evaluatesTo1800() {
        assertEquals("1800", runGated("""
                trait Billed{ charge(qty:Int):[Int -> @ * 100 -> Int] }

                struct Plan(price:Int)

                assign trait Plan:Billed {
                  charge(qty:Int):Int -> this.price * qty
                }

                Plan(9).charge(2)
                """));
    }

    @Test
    void readmeTraitArgAndReturnShellSnippet_evaluatesTo2400() {
        assertEquals("2400", runGated("""
                trait Ordered{ bill(order:[Int -> @ * 12 -> Int]):[Int -> @ * 100 -> Int] }

                struct Bakery(unitPrice:Int)

                assign trait Bakery:Ordered {
                  bill(order:Int):Int -> this.unitPrice * order
                }

                Bakery(2).bill(1)
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

    @Test
    void readmeExplicitCastSnippet_rendersIntToString() throws Exception {
        Object result = run("""
                let n:String = (String:12)
                "n=" + n
                """);
        assertInstanceOf(StringValue.class, result);
        assertEquals("n=12", ((StringValue) result).content());
    }

    // --- Type parameters — generics without erasure --------------------------

    @Test
    void readmeTypeParamCarrierSnippet_evaluatesTo10() {
        assertEquals("10", runGated("""
                struct Box[type T](value:T)
                function open(b:Box[Int]):Int -> b.value
                function id[type E](x:E):E -> x

                id(open(Box(7))) + id(3)
                """));
    }

    @Test
    void readmeParametricTraitSnippet_evaluatesTo42() {
        assertEquals("42", runGated("""
                trait Container[type E]{ get:[Method():E] }

                struct Box[type T](value:T)

                assign trait Box[type T]:Container[T] {
                  get():T -> this.value
                }

                Box(42).get()
                """));
    }

    @Test
    void readmeParametricIsaBaseSnippet_evaluatesTo9() {
        assertEquals("9", runGated("""
                struct Literal[type T](value:T)
                struct IntLit:[Literal[Int]:@.value==value](value:Int)

                IntLit(9).value
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

    // --- Reflecting a function into its AST (pontif.algebra, default-installed) --

    @Test
    void readmeAlgebraEvalMethodSnippet_matchesTheDirectCall() {
        assertEquals("true", runGated("""
                requires pontif.algebra.{Algebraic}

                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic

                $poly[Decimal].eval(3.0) == poly(3.0)
                """));
    }

    @Test
    void readmeAlgebraAstSnippet_inspectableWithMatch_evaluatesTo1() {
        assertEquals("1", runGated("""
                requires pontif.algebra.{AlgExpr, Add}

                function poly(x:Decimal):Decimal -> x*x + 2.0*x + 1.0
                assign proof poly:Algebraic

                let e:AlgExpr = $poly[Decimal].ast
                match e {
                  [Add(_, _)] -> 1
                  [_]         -> 0
                }
                """));
    }

    @Test
    void readmeAlgebraEvalAtSnippet_multiVariable_matchesTheDirectCall() {
        assertEquals("true", runGated("""
                requires pontif.algebra.{evalAt}

                function f(x:Decimal, y:Decimal):Decimal -> x*y + x
                assign proof f:Algebraic

                evalAt($f[Decimal, Decimal].ast, {x = 3.0, y = 4.0}) == f(3.0, 4.0)
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

                function swap(p:[{Int, Bool}]):[{Bool, Int}] ->
                  match p { [{a, b}] -> {b, a} }

                proof swap = Reversible()

                let [{x, y}] = swap({1, true}) y
                """));
    }

    // --- The math library (pontif.math + pontif.math.ext, default-installed) --

    @Test
    void readmeMathSnippet_mixesGlslAndExtInteger() {
        assertEquals("22.0", runGated("""
                requires pontif.math.{sqrt, clamp}
                requires pontif.math.ext.{gcd, choose}

                sqrt(9.0) + clamp(9.0, 0.0, 5.0) + gcd(12, 8) + choose(5, 2)
                """));
    }

    @Test
    void readmeMathSnippet_transcendentalPrecisionIsHonest() {
        assertEquals("1.4142135623730951", runGated("""
                requires pontif.math.{sqrt}
                sqrt(2.0)
                """));
    }

    // --- Actions and events (pontif.events, emit + action) -------------------

    @Test
    void readmeEventsSnippet_emitFansOutToMatchingActions() {
        StdoutRun r = runCapturingStdout("""
                requires pontif.events.{Event, StdOut}

                struct Tick(n:Int)
                assign trait Tick:Event{}

                action log(e:Tick)              -> emit StdOut("tick ")  e
                action alarm(e:[Tick:@.n > 10]) -> emit StdOut("BIG")    e

                main ( emit Tick(42)  0 )
                """);
        assertEquals("0", r.value());          // main's trailing expr; emit is write-only
        assertEquals("tick BIG", r.stdout());   // log fires for every Tick, alarm only when @.n > 10
    }
}

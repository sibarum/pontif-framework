package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.runtime.PontifCompiler.CompileResult;
import sibarum.pontif.runtime.PontifRunner.Engine;
import sibarum.pontif.runtime.PontifRunner.RunResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim rule at construction sites (the {@code ConstructionGate}),
 * judging each constructor argument against its declared field sort under
 * the §1d no-unproven-runtime-check law (roadmap §1d, ratified 2026-07-18):
 * <ul>
 *   <li><b>provable fit</b> — passes with NO runtime check (the proof
 *       discharged it),</li>
 *   <li><b>anything else (provable miss OR undecidable overlap)</b> —
 *       a compile-time error. The compiler must prove the runtime will
 *       succeed; an unprovable fit is not silently stamped for the runtime
 *       (the old third verdict). The only sanctioned deferral is the
 *       parametric-{@code Stream} element check ({@code [!!]}).</li>
 * </ul>
 * Pins the §1d ruling on the {@code Lift(base:[[Int:0]|Omega])} examples,
 * on both engines.
 */
class ConstructionGateTest {

    private static final String LIFT = """
            struct Omega()
            struct Lift(base:[[Int:0]|Omega])
            """;

    /** Struct-only unions for the union-arg-vs-union-field trichotomy (Case C). */
    private static final String UNIONS = """
            struct A()
            struct B()
            struct C()
            struct D()
            struct WrapAB(inner:[A|B])
            struct WrapABC(inner:[A|B|C])
            """;

    private final PontifCompiler compiler = new PontifCompiler();
    private final PontifRunner runner = new PontifRunner();

    private RunResult run(String src, Engine engine) {
        return runner.run(compiler.compile(src, "t.ptf"), engine);
    }

    private CompiledModule compiled(String src) {
        CompileResult result = compiler.compile(src, "t.ptf");
        CompileResult.Compiled ok = assertInstanceOf(CompileResult.Compiled.class, result,
                () -> "expected a clean compile; got: "
                        + ((CompileResult.Failed) result).error().text());
        return ok.program().module();
    }

    @org.junit.jupiter.api.Test
    void effectiveSortCallRouting_fourCases() {
        // Only bark(Dog) exists (no fallback) — so this is pure static resolution on the EFFECTIVE
        // sort, no specificity/runtime dispatch involved. James's four cases:
        String p = """
                trait Animal{ noise:[Method():String] }
                struct Dog()
                struct Cat()
                assign trait Dog:Animal { noise():String -> "woof" }
                assign trait Cat:Animal { noise():String -> "meow" }
                function bark(d:Dog):String -> "bark!"
                """;
        // 1: dog:Animal = Dog()  → effective Dog → should ROUTE.
        RunResult c1 = run(p + "let dog:Animal = Dog()\nbark(dog)", Engine.INTERPRETER);
        // 2: bark(a) with a:Animal param → should COMPILE-FAIL.
        CompileResult c2 = compiler.compile(
                p + "function speak(a:Animal):String -> bark(a)\n42", "t.ptf");
        // 4: cat:Animal = Cat() → effective Cat, not-a Dog → should COMPILE-FAIL.
        CompileResult c4 = compiler.compile(p + "let cat:Animal = Cat()\nbark(cat)", "t.ptf");
        // Assert the EXPECTED behavior; failures reveal current-vs-expected.
        assertFalse(c1.isError(), () -> "case 1 (effective Dog) should route; got: " + c1.text());
        assertInstanceOf(CompileResult.Failed.class, c2, "case 2 (Animal param) should compile-fail");
        assertInstanceOf(CompileResult.Failed.class, c4, "case 4 (effective Cat) should compile-fail");
    }

    private void assertCompileError(String src) {
        CompileResult result = compiler.compile(src, "t.ptf");
        CompileResult.Failed failed = assertInstanceOf(CompileResult.Failed.class, result,
                "expected a compile-time rejection");
        assertTrue(failed.error().text().contains("can never satisfy"),
                () -> "Expected the disjoint-construction error; got: " + failed.error().text());
    }

    /** §1d: an undecidable (overlap / outside-the-kernel) construction fit is a compile
     *  error — "cannot be proved to satisfy" — not a silently stamped runtime check. */
    private void assertUnprovableConstruction(String src) {
        CompileResult result = compiler.compile(src, "t.ptf");
        CompileResult.Failed failed = assertInstanceOf(CompileResult.Failed.class, result,
                "expected a compile-time rejection (§1d: no silent runtime stamp)");
        assertTrue(failed.error().text().contains("cannot be proved to satisfy"),
                () -> "Expected the unprovable-construction error; got: " + failed.error().text());
    }

    // --- provable miss → compile error --------------------------------------

    @Test
    void literalDisjointFromUnionField_isCompileError() {
        // 1 narrows to [Int:@==1] — disjoint from the @==0 branch AND from Omega.
        assertCompileError(LIFT + "let z = Lift(1)\n42");
    }

    @Test
    void declaredVarDisjointFromUnionField_isCompileError() {
        // A param declared [Int:@>1] provably misses both union branches.
        assertCompileError(LIFT + """
                function mk(noOverlap:[Int:@>1]):Lift -> Lift(noOverlap)
                mk(5)
                """);
    }

    @Test
    void letBoundLiteralDisjoint_isCompileError() {
        // The let's VALUE narrowing ([Int:@==5]) decides, not just the
        // declared sort — still provably disjoint.
        assertCompileError(LIFT + "let five = 5\nlet z = Lift(five)\n42");
    }

    // --- genuine overlap → compile error (§1d), not a runtime stamp ----------

    @Test
    void overlappingParamAtUnionField_isCompileError() {
        // [Int:@<=1] overlaps the @==0 branch without implying it, and misses the
        // Omega branch — an undecidable fit. §1d: no silent runtime stamp; the
        // construction is a compile error inside mk's own body, whether or not mk
        // is ever called. (Formerly this compiled and stamped a runtime check.)
        assertUnprovableConstruction(LIFT + """
                function mk(overlap:[Int:@<=1]):Lift -> Lift(overlap)
                mk(0)
                """);
    }

    @Test
    void provenParamAtUnionField_compilesAndRuns() {
        // The §1d way to make an overlapping construction legal: narrow the value
        // so the fit is PROVEN. [Int:@==0] implies the @==0 branch → FITS,
        // discharged with no runtime check; mk(0).base runs to 0 on both engines.
        String src = LIFT + """
                function mk(zero:[Int:@==0]):Lift -> Lift(zero)
                mk(0).base
                """;
        for (Engine engine : Engine.values()) {
            RunResult r = run(src, engine);
            assertFalse(r.isError(), () -> engine + " got: " + r.text());
            assertEquals("0", r.text(), engine.toString());
        }
    }

    @Test
    void provenParamAtUnionField_isNotStamped() throws Exception {
        // The discriminating half: the proven fit carries NO runtime check — §1d
        // leaves the record clean (the retired third verdict would have stamped it).
        CompiledModule module = compiled(LIFT + """
                function mk(zero:[Int:@==0]):Lift -> Lift(zero)
                mk(0)
                """);
        IrExpr.Record construction = findConstruction(module, "Lift");
        assertNotNull(construction, "expected the Lift construction in mk's body");
        assertTrue(construction.runtimeChecks().isEmpty(),
                () -> "a proven fit must not be stamped; got: " + construction.runtimeChecks());
    }

    // --- provable fit → passes with NO runtime check -------------------------

    @Test
    void provableFit_passes_withNoRuntimeCheck() throws Exception {
        RunResult r = run(LIFT + "let z = Lift(0)\nz.base", Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "got: " + r.text());
        assertEquals("0", r.text());

        // The discriminating half of the ruling: the fit was PROVEN, so the
        // record carries no runtime check at all. (A top-level let lowers to a
        // 0-arg function, so the construction lives in that function's body.)
        IrExpr.Record construction = findConstruction(
                compiled(LIFT + "let z = Lift(0)\n42"), "Lift");
        assertNotNull(construction, "expected the Lift construction");
        assertTrue(construction.runtimeChecks().isEmpty(),
                () -> "provable fit must not be stamped; got: " + construction.runtimeChecks());
    }

    @Test
    void structBranchFit_passes_withNoRuntimeCheck() throws Exception {
        RunResult r = run(LIFT + "let z = Lift(Omega())\n42", Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "got: " + r.text());

        IrExpr.Record construction = findConstruction(
                compiled(LIFT + "let z = Lift(Omega())\n42"), "Lift");
        assertNotNull(construction, "expected the Lift construction");
        assertTrue(construction.runtimeChecks().isEmpty(),
                () -> "a named-record argument fits its own branch; got: "
                        + construction.runtimeChecks());
    }

    @Test
    void structWidenAtConstruction_fits_withNoRuntimeCheck() throws Exception {
        // Phase-1 delegation delta (roadmap §4.3): a field declared at a BASE struct, constructed
        // with a DERIVED struct, is a proven widen — Point3D is-a Point. The retired hand-rolled base
        // leg compared base NAMES ("Point3D" != "Point", both concrete) and wrongly ruled this
        // DISJOINT (a compile error); the Assignability nominal leg rules it a widen → FITS with no
        // runtime check. Pins the engine as the single decider for the nominal question.
        String widen = """
                struct Point(x:Int, y:Int)
                struct Point3D:[Point:@.x==x & @.y==y](x:Int, y:Int, z:Int)
                struct Holder(p:Point)
                """;
        RunResult r = run(widen + "let h = Holder(Point3D(1, 2, 3))\nh.p.x", Engine.INTERPRETER);
        assertFalse(r.isError(), () -> "widen-at-construction must compile and run; got: " + r.text());
        assertEquals("1", r.text());

        IrExpr.Record construction = findConstruction(
                compiled(widen + "let h = Holder(Point3D(1, 2, 3))\n42"), "Holder");
        assertNotNull(construction, "expected the Holder construction");
        assertTrue(construction.runtimeChecks().isEmpty(),
                () -> "a proven widen must not be stamped; got: " + construction.runtimeChecks());
    }

    // --- Case C: a UNION argument at a UNION field (the classify reorder) -----
    // classify() tests `arg instanceof Union` BEFORE `field instanceof Union`, so a
    // union arg is decomposed branch-by-branch against the field union. This is the
    // only arg/field combination the ordering changes, and it is the whole reason
    // reflexive/subset union subsumption (U ⊑ U) is provable at construction — the
    // AlgExpr-returned-into-an-AlgExpr-field scenario. The two FITS tests turn red if
    // the reorder is reverted (they revert to a spurious "cannot be proved" error);
    // the two error tests pin the soundness edges the reorder must NOT breach.

    @Test
    void unionArgEqualsUnionField_fitsReflexively() {
        // arg [A|B] into field [A|B]: each arg branch (A, B) is a member of the field
        // union → allFit → FITS. Pre-reorder this asked "does the whole [A|B] fit a
        // single field branch?" for every branch → UNKNOWN → a spurious compile error.
        CompileResult r = compiler.compile(
                UNIONS + "function w(x:[A|B]):WrapAB -> WrapAB(x)\nw(A())", "u.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "reflexive union subsumption at construction must compile; got: " + errorText(r));
    }

    @Test
    void unionArgSubsetOfUnionField_fits() {
        // arg [A|B] into field [A|B|C]: a proper subset — both arg branches are members
        // of the field union → FITS.
        CompileResult r = compiler.compile(
                UNIONS + "function w(x:[A|B]):WrapABC -> WrapABC(x)\nw(A())", "u.ptf");
        assertInstanceOf(CompileResult.Compiled.class, r,
                () -> "subset union subsumption must compile; got: " + errorText(r));
    }

    @Test
    void unionArgFullyDisjointFromUnionField_isCompileError() {
        // arg [C|D] into field [A|B]: EVERY arg branch is disjoint from the whole field
        // union → allDisjoint → DISJOINT → "can never satisfy". The reorder must not
        // turn a provable miss into a wrong accept.
        assertCompileError(UNIONS + "function w(x:[C|D]):WrapAB -> WrapAB(x)\nw(C())");
    }

    @Test
    void unionArgPartiallyCoveredByUnionField_isUnprovable() {
        // arg [A|C] into field [A|B]: A fits, C is disjoint → neither allFit nor
        // allDisjoint → UNKNOWN → §1d "cannot be proved to satisfy". A wrong FITS here
        // would unsoundly admit a value that might be a C.
        assertUnprovableConstruction(UNIONS + "function w(x:[A|C]):WrapAB -> WrapAB(x)\nw(A())");
    }

    private static String errorText(CompileResult r) {
        return r instanceof CompileResult.Failed f ? f.error().text() : "(compiled clean)";
    }

    // --- the README flagship, now honest -------------------------------------

    @Test
    void decimalRefinedField_provableFitCompiles_missIsCompileError() {
        // A Decimal-literal fit IS decided at compile time (Refinements kernel):
        // Account(100.0, …) discharges [Decimal:@==100.0] ⊑ [Decimal:@>=0]. A miss
        // (-5.0) is DISJOINT from [Decimal:@>=0]; the Decimal predicate kernel now
        // decides this (dense open/closed intervals, like Int), so §1d rejects it
        // at compile time as "can never satisfy" rather than the old "cannot be
        // proved" fallback.
        String accounts = """
                struct Account(balance:[Decimal:@>=0], rate:Decimal)
                """;
        for (Engine engine : Engine.values()) {
            RunResult ok = run(accounts + "let a = Account(100.0, 0.05)\na.rate", engine);
            assertFalse(ok.isError(), () -> engine + " got: " + ok.text());
        }
        assertCompileError(accounts + "let a = Account(-5.0, 0.05)\na.rate");
    }

    /** The named construction anywhere in the module — function bodies first, then main. */
    private static IrExpr.Record findConstruction(CompiledModule module, String typeName) {
        for (var fn : module.functions().values()) {
            IrExpr.Record found = firstRecord(fn.body());
            if (found != null && typeName.equals(found.typeName())) return found;
        }
        IrExpr.Record found = firstRecord(module.main());
        return found != null && typeName.equals(found.typeName()) ? found : null;
    }

    /** Depth-first search for the first named Record construction in an expression. */
    private static IrExpr.Record firstRecord(IrExpr e) {
        if (e == null) return null;
        return switch (e) {
            case IrExpr.Record r -> r.typeName() != null ? r : firstInMembers(r);
            case IrExpr.LetIn l -> {
                IrExpr.Record v = firstRecord(l.value());
                yield v != null ? v : firstRecord(l.body());
            }
            case IrExpr.BinOp op -> {
                IrExpr.Record l = firstRecord(op.left());
                yield l != null ? l : firstRecord(op.right());
            }
            case IrExpr.Call c -> {
                IrExpr.Record found = null;
                for (IrExpr a : c.args()) {
                    found = firstRecord(a);
                    if (found != null) break;
                }
                yield found;
            }
            case IrExpr.Match m -> {
                IrExpr.Record s = firstRecord(m.scrutinee());
                if (s != null) yield s;
                IrExpr.Record found = null;
                for (IrExpr.MatchBranch b : m.branches()) {
                    found = firstRecord(b.result());
                    if (found != null) break;
                }
                yield found;
            }
            case IrExpr.FieldAccess fa -> firstRecord(fa.base());
            default -> null;
        };
    }

    private static IrExpr.Record firstInMembers(IrExpr.Record r) {
        for (IrExpr v : r.members().values()) {
            IrExpr.Record found = firstRecord(v);
            if (found != null) return found;
        }
        return null;
    }
}

package sibarum.pontif.types;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.CallKinds;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrSort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolated lab tests for the {@link Assignability} is-a relation and assignment rule
 * (docs/type-records.md). Nothing in the compiler pipeline calls the engine yet; these pin the
 * target-state behavior before any call site migrates onto it.
 */
class AssignabilityTest {

    private static final IrSort DECIMAL = IrSort.named("Decimal");
    private static final IrSort INT = IrSort.named("Int");
    private static final IrSort BOOL = IrSort.named("Bool");

    /** The anonymous tuple `{3*Decimal}` — `_tuple` with positional members `_0.._2` (ordered). */
    private static IrSort tuple3() {
        Map<String, IrSort> m = new LinkedHashMap<>();
        m.put("_0", DECIMAL);
        m.put("_1", DECIMAL);
        m.put("_2", DECIMAL);
        return IrSort.structural("_tuple", m);
    }

    private static IrSort tuple2() {
        Map<String, IrSort> m = new LinkedHashMap<>();
        m.put("_0", DECIMAL);
        m.put("_1", DECIMAL);
        return IrSort.structural("_tuple", m);
    }

    /** Catalog: Vec3 and Color are nominal tags over `{3*Decimal}`; AnyNumber is a transparent union.
     *  Vec3 and Color both satisfy the trait Showable. */
    private static AssignabilityContext ctx() {
        TypeCatalog cat = new TypeCatalog();
        cat.register("Vec3", new TypeInfo.Alias(tuple3()));
        cat.register("Color", new TypeInfo.Alias(tuple3()));
        cat.register("AnyNumber", new TypeInfo.Alias(IrSort.union(List.of(DECIMAL, INT))));
        cat.register("Showable", new TypeInfo.Trait(IrSort.trait("Showable", Map.of())));
        return AssignabilityContext.of(cat, Map.of(
                "Vec3", java.util.Set.of("Showable"),
                "Color", java.util.Set.of("Showable")));
    }

    private static IrSort named(String n) {
        return IrSort.named(n);
    }

    // --- is-a --------------------------------------------------------------

    @Test
    void reflexive() {
        assertTrue(Assignability.isA(named("Vec3"), named("Vec3"), ctx()));
        assertTrue(Assignability.isA(tuple3(), tuple3(), ctx()));
    }

    @Test
    void nominalTagWidensToItsStructure() {
        assertTrue(Assignability.isA(named("Vec3"), tuple3(), ctx()));   // Vec3 is-a {3*Decimal}
    }

    @Test
    void bareStructureIsNotItsNominalTag() {
        assertFalse(Assignability.isA(tuple3(), named("Vec3"), ctx()));  // {3*Decimal} is-NOT-a Vec3
    }

    @Test
    void siblingsAreUnrelated() {
        assertFalse(Assignability.isA(named("Vec3"), named("Color"), ctx()));
        assertFalse(Assignability.isA(named("Color"), named("Vec3"), ctx()));
    }

    @Test
    void unionMembership() {
        assertTrue(Assignability.isA(INT, named("AnyNumber"), ctx()));       // Int is-a AnyNumber
        assertTrue(Assignability.isA(DECIMAL, named("AnyNumber"), ctx()));
        assertFalse(Assignability.isA(BOOL, named("AnyNumber"), ctx()));
    }

    @Test
    void tupleShapeIsInvariantOnArity() {
        assertFalse(Assignability.isA(tuple2(), tuple3(), ctx()));
        assertFalse(Assignability.isA(tuple3(), tuple2(), ctx()));
    }

    // --- parametric type-args (invariant — roadmap §4.5 item 2) ------------

    /** An applied {@code Box[arg]}. */
    private static IrSort boxOf(IrSort arg) {
        return new IrSort.Named("Box", List.of(arg), Origin.NONE);
    }

    @Test
    void parametric_sameArgs_isExact() {
        assertTrue(Assignability.isA(boxOf(INT), boxOf(INT), ctx()));
        assertEquals(Assignability.Assignment.EXACT,
                Assignability.assign(boxOf(INT), boxOf(INT), ctx()));
    }

    @Test
    void parametric_differentConcreteArgs_isIllegal() {
        // Invariance: Box[Int] is not usable where Box[Bool] is required, and no cast retags one
        // instantiation as another. (The pre-item-2 engine was type-arg-blind and returned EXACT.)
        assertFalse(Assignability.isA(boxOf(INT), boxOf(BOOL), ctx()));
        assertEquals(Assignability.Assignment.ILLEGAL,
                Assignability.assign(boxOf(INT), boxOf(BOOL), ctx()));
    }

    @Test
    void parametric_typeVariableArg_isASlot() {
        // A type-variable arg (an undeclared bare Named) is a slot bound by the derivation machinery,
        // so Box[Int] is-a Box[T] — this keeps a Box[T] struct field usable by a Box[Int] argument.
        assertTrue(Assignability.isA(boxOf(INT), boxOf(named("T")), ctx()));
    }

    @Test
    void parametric_bareVsApplied_widensByName() {
        // A bare Box (arity 0) is the existential "Box of anything"; Box[Int] widens to it — the
        // invariance arm fires only on equal, non-empty arity, so this keeps its name-only behavior.
        assertTrue(Assignability.isA(boxOf(INT), named("Box"), ctx()));
    }

    // --- assign ------------------------------------------------------------

    @Test
    void assignExactAndWiden() {
        assertEquals(Assignability.Assignment.EXACT,
                Assignability.assign(named("Vec3"), named("Vec3"), ctx()));
        assertEquals(Assignability.Assignment.WIDEN,
                Assignability.assign(named("Vec3"), tuple3(), ctx()));   // let t:{3*Decimal} = v
    }

    @Test
    void assignNeedsCast_bareTupleToTag_andCousins() {
        assertEquals(Assignability.Assignment.NEEDS_CAST,
                Assignability.assign(tuple3(), named("Vec3"), ctx()));   // let v:Vec3 = {..} -> construct/cast
        assertEquals(Assignability.Assignment.NEEDS_CAST,
                Assignability.assign(named("Vec3"), named("Color"), ctx()));  // let c:Color = v -> Color:v
    }

    @Test
    void assignIllegal_whenStructuresIncompatible() {
        assertEquals(Assignability.Assignment.ILLEGAL,
                Assignability.assign(named("Vec3"), INT, ctx()));
    }

    @Test
    void numericTowerCoerces_butStructsDoNot() {
        // Int -> Decimal: the lossless auto-conversion — COERCE (concrete changes), not WIDEN, not a cast.
        assertEquals(Assignability.Assignment.COERCE,
                Assignability.assign(INT, DECIMAL, ctx()));
        // The reverse is not a lossless tower step.
        assertEquals(Assignability.Assignment.ILLEGAL,
                Assignability.assign(DECIMAL, INT, ctx()));
        // COERCE never applies to structs — same-structure siblings still NEEDS_CAST.
        assertEquals(Assignability.Assignment.NEEDS_CAST,
                Assignability.assign(named("Vec3"), named("Color"), ctx()));
    }

    // --- construct ---------------------------------------------------------

    @Test
    void constructTagFromMatchingArgs() {
        Assignability.Made m = Assignability.construct("Vec3", List.of(DECIMAL, DECIMAL, DECIMAL), ctx());
        Assignability.Made.Ok ok = assertInstanceOf(Assignability.Made.Ok.class, m);
        assertEquals("Vec3", ((IrSort.Named) ok.concreteType()).name());   // value's concrete type is Vec3
    }

    @Test
    void constructRejectsWrongArityAndWrongFieldType() {
        assertInstanceOf(Assignability.Made.Rejected.class,
                Assignability.construct("Vec3", List.of(DECIMAL, DECIMAL), ctx()));
        assertInstanceOf(Assignability.Made.Rejected.class,
                Assignability.construct("Vec3", List.of(DECIMAL, BOOL, DECIMAL), ctx()));
    }

    @Test
    void constructRejectsNonConstructibleName() {
        assertInstanceOf(Assignability.Made.Rejected.class,
                Assignability.construct("Nope", List.of(DECIMAL), ctx()));
    }

    // --- cast --------------------------------------------------------------

    @Test
    void castBetweenSiblings_producesTarget() {
        Assignability.Made m = Assignability.cast(named("Color"), named("Vec3"), ctx());  // Color:v
        Assignability.Made.Ok ok = assertInstanceOf(Assignability.Made.Ok.class, m);
        assertEquals("Color", ((IrSort.Named) ok.concreteType()).name());
    }

    @Test
    void castBareTupleToTag_producesTag() {
        assertInstanceOf(Assignability.Made.Ok.class,
                Assignability.cast(named("Vec3"), tuple3(), ctx()));   // Vec3:{...}
    }

    @Test
    void castRejectsIncompatibleStructures() {
        assertInstanceOf(Assignability.Made.Rejected.class,
                Assignability.cast(INT, named("Vec3"), ctx()));
    }

    // --- trait satisfaction ------------------------------------------------

    @Test
    void nominalTagSatisfiesImplementedTrait() {
        assertTrue(Assignability.isA(named("Vec3"), named("Showable"), ctx()));    // let s:Showable = v
        assertTrue(Assignability.isA(named("Color"), named("Showable"), ctx()));
        assertEquals(Assignability.Assignment.WIDEN,
                Assignability.assign(named("Vec3"), named("Showable"), ctx()));
    }

    @Test
    void bareStructureDoesNotSatisfyTheTrait() {
        assertFalse(Assignability.isA(tuple3(), named("Showable"), ctx()));   // a bare tuple implements nothing
    }

    // --- refinement-precise leaf subsumption -------------------------------

    /** {@code [Int:@ <op> bound]}. */
    private static IrSort refinedInt(IrExpr.Op op, long bound) {
        IrExpr pred = new IrExpr.BinOp(op,
                new IrExpr.SelfRef(Origin.NONE),
                new IrExpr.Lit(bound, Origin.NONE), Origin.NONE);
        return new IrSort.Refined("Int", pred, Origin.NONE);
    }

    @Test
    void refinementPreciseSubsumption() {
        IrSort gt0 = refinedInt(IrExpr.Op.GT, 0);   // [Int:@>0]
        IrSort ge0 = refinedInt(IrExpr.Op.GE, 0);   // [Int:@>=0]
        assertTrue(Assignability.isA(gt0, ge0, ctx()));    // @>0 implies @>=0
        assertFalse(Assignability.isA(ge0, gt0, ctx()));   // @>=0 does NOT imply @>0 (0 is a counterexample)
        assertTrue(Assignability.isA(gt0, gt0, ctx()));    // reflexive
        assertTrue(Assignability.isA(gt0, INT, ctx()));    // widen to bare Int drops the refinement
        assertFalse(Assignability.isA(INT, gt0, ctx()));   // bare Int can't prove @>0
    }

    // --- intersection ------------------------------------------------------

    @Test
    void intersectionSubtyping() {
        TypeCatalog cat = new TypeCatalog();
        cat.register("Drawable", new TypeInfo.Trait(IrSort.trait("Drawable", Map.of())));
        cat.register("Serial", new TypeInfo.Trait(IrSort.trait("Serial", Map.of())));
        cat.register("Widget",
                new TypeInfo.Struct((IrSort.Structural) IrSort.structural("Widget", Map.of("id", INT))));
        cat.register("Icon",
                new TypeInfo.Struct((IrSort.Structural) IrSort.structural("Icon", Map.of("id", INT))));
        AssignabilityContext c = AssignabilityContext.of(cat, Map.of(
                "Widget", java.util.Set.of("Drawable", "Serial"),
                "Icon", java.util.Set.of("Drawable")));
        IrSort both = IrSort.intersection(List.of(named("Drawable"), named("Serial")));

        assertTrue(Assignability.isA(named("Widget"), both, c));   // satisfies both -> is-a the intersection
        assertFalse(Assignability.isA(named("Icon"), both, c));    // satisfies only one -> is-NOT-a
        assertTrue(Assignability.isA(both, named("Drawable"), c)); // an intersection is-a one of its branches
    }

    // --- function sorts (Method / Dispatch) --------------------------------

    private static IrSort method(IrSort param, IrSort ret) {
        return new IrSort.CallSig(IrSort.CallSig.METHOD, List.of(param), ret, Origin.NONE);   // [Method(param):ret]
    }

    private static IrSort dispatch(IrSort key, IrSort ret) {
        return new IrSort.CallSig(IrSort.CallSig.DISPATCH, List.of(key), ret, Origin.NONE);   // [Dispatch(key):ret]
    }

    @Test
    void methodSortSubtyping() {
        IrSort gt0 = refinedInt(IrExpr.Op.GT, 0);
        assertTrue(Assignability.isA(method(INT, INT), method(INT, INT), ctx()));   // reflexive
        // Covariant return: a method returning [Int:@>0] is-a one returning Int.
        assertTrue(Assignability.isA(method(INT, gt0), method(INT, INT), ctx()));
        assertFalse(Assignability.isA(method(INT, INT), method(INT, gt0), ctx()));  // return not covariant
        // A Method never cross-assigns with a Dispatch.
        assertFalse(Assignability.isA(method(INT, INT), dispatch(INT, INT), ctx()));
    }

    @Test
    void dispatchSortSubtyping() {
        IrSort gt0 = refinedInt(IrExpr.Op.GT, 0);
        assertTrue(Assignability.isA(dispatch(INT, INT), dispatch(INT, INT), ctx()));    // reflexive
        assertTrue(Assignability.isA(dispatch(INT, gt0), dispatch(INT, INT), ctx()));    // covariant return
        assertFalse(Assignability.isA(dispatch(INT, INT), dispatch(DECIMAL, INT), ctx())); // different keys
    }

    // --- AlgebraicDispatch: the trait-view intersection (roadmap §5) --------

    @Test
    void algebraicDispatchIsAViewOfDispatch() {
        // `[[Dispatch(Decimal):Decimal] & Algebraic]` — the trait-view stamped on a
        // metareference proven algebraic. It widens FREELY to the bare Dispatch (a
        // some-branch is-a) but the bare Dispatch is NOT algebraic, and it carries the
        // Algebraic marker — so `.ast` (a member of Algebraic) is reachable only
        // through the algebraic view, never fabricated on a plain dispatch.
        IrSort disp = dispatch(DECIMAL, DECIMAL);
        IrSort algebraic = IrSort.intersection(List.of(disp, named("Algebraic")));

        assertTrue(Assignability.isA(algebraic, disp, ctx()));               // view widen
        assertFalse(Assignability.isA(disp, algebraic, ctx()));              // plain ≠ algebraic
        assertTrue(Assignability.isA(algebraic, algebraic, ctx()));          // reflexive
        assertTrue(Assignability.isA(algebraic, named("Algebraic"), ctx())); // carries the marker
    }

    // --- ACID TEST: a NEW callable type via pure capability DATA (no type-system edit) ---

    private static IrSort widget(IrSort key, IrSort ret) {
        return new IrSort.CallSig("Widget", List.of(key), ret, Origin.NONE);   // [Widget(key):ret]
    }

    @Test
    void newCallableType_parsesSubtypesSatisfies_viaCapabilityDataOnly() throws Exception {
        // docs/dispatch-method-elimination.md §3 (E1 acid test). "Widget" is not a builtin
        // call-kind head; it becomes dispatch-style ONLY through the capability DATA in the
        // context (as if a user wrote `assign trait Widget : dispatch-style`). No edit to
        // Assignability, Refinements, or the parser is required — the head name is data and
        // the call-kind behavior is looked up from the trait-impl view. (Parsing of an
        // arbitrary `Widget(Int):Int` head is proven in AltParserSortTest.)
        AssignabilityContext c = AssignabilityContext.of(new TypeCatalog(), Map.of(
                "Widget", java.util.Set.of(CallKinds.DISPATCH_STYLE)));
        IrSort gt0 = refinedInt(IrExpr.Op.GT, 0);

        // SUBTYPE — the dispatch-style rule (exact keys + covariant return) fires because
        // Widget is-a dispatch-style, selected by capability, never by name.
        assertTrue(Assignability.isA(widget(INT, INT), widget(INT, INT), c));      // reflexive
        assertTrue(Assignability.isA(widget(INT, gt0), widget(INT, INT), c));      // covariant return
        assertFalse(Assignability.isA(widget(INT, INT), widget(DECIMAL, INT), c)); // different keys
        // A dispatch-style Widget never cross-assigns a function-style Method.
        assertFalse(Assignability.isA(method(INT, INT), widget(INT, INT), c));

        // SATISFY — the compiled core sort is dispatch-shaped (value-satisfied by a
        // metareference, not a lambda). Method is the sole function-style head, so every
        // other callable head compiles to the dispatch value rule (§2) — matching Widget's
        // declared capability, again with no Refinements change.
        assertTrue(IrCompiler.compileSort(widget(INT, INT)).isDispatch());
        assertFalse(IrCompiler.compileSort(method(INT, INT)).isDispatch());
    }
}

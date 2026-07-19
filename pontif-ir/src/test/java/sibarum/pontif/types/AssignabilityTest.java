package sibarum.pontif.types;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
}

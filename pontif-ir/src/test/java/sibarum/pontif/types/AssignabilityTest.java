package sibarum.pontif.types;

import org.junit.jupiter.api.Test;

import java.util.Map;

import sibarum.pontif.ir.IrSort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** The anonymous tuple `{3*Decimal}` — `_tuple` with positional members `_0.._2`. */
    private static IrSort tuple3() {
        return IrSort.structural("_tuple", Map.of("_0", DECIMAL, "_1", DECIMAL, "_2", DECIMAL));
    }

    private static IrSort tuple2() {
        return IrSort.structural("_tuple", Map.of("_0", DECIMAL, "_1", DECIMAL));
    }

    /** Catalog: Vec3 and Color are nominal tags over `{3*Decimal}`; AnyNumber is a transparent union. */
    private static AssignabilityContext ctx() {
        TypeCatalog cat = new TypeCatalog();
        cat.register("Vec3", new TypeInfo.Alias(tuple3()));
        cat.register("Color", new TypeInfo.Alias(tuple3()));
        cat.register("AnyNumber", new TypeInfo.Alias(IrSort.union(java.util.List.of(DECIMAL, INT))));
        return AssignabilityContext.of(cat);
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
}

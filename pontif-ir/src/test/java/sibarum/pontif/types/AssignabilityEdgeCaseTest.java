package sibarum.pontif.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sibarum.pontif.ir.IrSort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge-case tests for {@link Assignability} — contrived, ill-founded, and unsound-boundary inputs
 * surfaced by the type-system audit.
 *
 * <p>Two classes of test live here:
 * <ul>
 *   <li><b>Regression</b> — the recursion-guard fix (a cyclic transparent alias or nominal base is
 *       ill-founded and now terminates with a sound {@code false}, instead of overflowing the stack).</li>
 *   <li><b>Characterization (BUG)</b> — a soundness hole the audit found but that is intentionally
 *       left unfixed pending a semantics decision. The assertion pins the CURRENT (wrong) behavior so
 *       the suite stays green and the defect can't drift silently; the moment it is fixed this test
 *       fails and must be flipped to the {@code assertFalse}/desired form noted inline.</li>
 * </ul>
 */
class AssignabilityEdgeCaseTest {

    private static AssignabilityContext ctxWith(String name, TypeInfo info) {
        TypeCatalog cat = new TypeCatalog();
        cat.register(name, info);
        return AssignabilityContext.of(cat);
    }

    // --- Regression: cyclic aliases must terminate (findings 1 & 2) ----------

    @Test
    @Timeout(5)
    void cyclicTransparentAlias_terminatesAsNotIsA() {
        // type A : A  — an ill-founded transparent alias. Previously recursed forever (StackOverflowError).
        AssignabilityContext ctx = ctxWith("A", new TypeInfo.Alias(IrSort.named("A")));
        assertFalse(Assignability.isA(IrSort.named("A"), IrSort.named("Int"), ctx));
    }

    @Test
    @Timeout(5)
    void cyclicUnionAlias_terminatesAsNotIsA() {
        // type A : A | Int  — the cycle rides a union branch. Previously StackOverflowError.
        AssignabilityContext ctx = ctxWith("A", new TypeInfo.Alias(
                IrSort.union(List.of(IrSort.named("A"), IrSort.named("Int")))));
        assertFalse(Assignability.isA(IrSort.named("A"), IrSort.named("Bool"), ctx));
    }

    @Test
    @Timeout(5)
    void mutuallyCyclicAliases_terminate() {
        // type A : B ; type B : A
        TypeCatalog cat = new TypeCatalog();
        cat.register("A", new TypeInfo.Alias(IrSort.named("B")));
        cat.register("B", new TypeInfo.Alias(IrSort.named("A")));
        AssignabilityContext ctx = AssignabilityContext.of(cat);
        assertFalse(Assignability.isA(IrSort.named("A"), IrSort.named("Int"), ctx));
    }

    @Test
    @Timeout(5)
    void reflexiveTransparentAlias_stillIsA() {
        // Guard must NOT break the reflexive case: type A : Int, then A is-a A.
        AssignabilityContext ctx = ctxWith("A", new TypeInfo.Alias(IrSort.named("Int")));
        assertTrue(Assignability.isA(IrSort.named("A"), IrSort.named("A"), ctx));
    }

    @Test
    @Timeout(5)
    void diamondAlias_stillResolves() {
        // Guard uses per-path backtracking, so a diamond (two branches sharing a base) is unaffected:
        // type Foo : Baz ; type Bar : Baz ; type W : Foo | Bar ; W is-a Baz.
        TypeCatalog cat = new TypeCatalog();
        cat.register("Baz", new TypeInfo.Alias(IrSort.named("Int")));
        cat.register("Foo", new TypeInfo.Alias(IrSort.named("Baz")));
        cat.register("Bar", new TypeInfo.Alias(IrSort.named("Baz")));
        cat.register("W", new TypeInfo.Alias(IrSort.union(List.of(IrSort.named("Foo"), IrSort.named("Bar")))));
        AssignabilityContext ctx = AssignabilityContext.of(cat);
        assertTrue(Assignability.isA(IrSort.named("W"), IrSort.named("Int"), ctx));
    }

    // --- Regression: sameType now compares structural member sorts (finding 1 of the fix set) ----

    @Test
    void sameNameStruct_differingFieldSorts_isNotIsA() {
        // P{x:Int} vs P{x:Bool}: sameType now compares field SORTS, so the reflexive shortcut no
        // longer fires and isA falls to structurallySubsumes, which rejects Int-as-Bool. (Was an
        // unsound `true` before the fix.)
        Map<String, IrSort> mi = new LinkedHashMap<>(); mi.put("x", IrSort.named("Int"));
        Map<String, IrSort> mb = new LinkedHashMap<>(); mb.put("x", IrSort.named("Bool"));
        AssignabilityContext ctx = AssignabilityContext.of(new TypeCatalog());
        assertFalse(Assignability.isA(IrSort.structural("P", mi), IrSort.structural("P", mb), ctx));
    }

    @Test
    void sameNameStruct_identicalFieldSorts_stillIsA() {
        // The fix must not over-reject: two truly identical shapes are still is-a (reflexive).
        Map<String, IrSort> a = new LinkedHashMap<>(); a.put("x", IrSort.named("Int"));
        Map<String, IrSort> b = new LinkedHashMap<>(); b.put("x", IrSort.named("Int"));
        AssignabilityContext ctx = AssignabilityContext.of(new TypeCatalog());
        assertTrue(Assignability.isA(IrSort.structural("P", a), IrSort.structural("P", b), ctx));
    }
}

package sibarum.pontif.types;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 0 of the {@code Assignability} migration (docs/type-records.md): the
 * {@link AssignabilityContext#fromModule} adapter — the seam a real call site will use to ask the
 * engine about live code. These tests pin that the adapter feeds {@link Assignability} the same facts
 * the compiler's own registries hold, in particular the <em>closed</em> trait-satisfaction relation
 * (a type implementing a sub-trait is-a its base trait), matching
 * {@code sibarum.pontif.core.symbolic.TraitRegistry#satisfies}.
 *
 * <p>Unlike {@link AssignabilityTest} (a hand-built catalog), this drives the adapter over a
 * {@link IrModule} exactly as {@code TypeCatalog.fromModule} sees it — where a struct's shape is
 * registered under its own name — so it also guards the sibling-recursion hazard that representation
 * introduces.
 */
class AssignabilityContextTest {

    private static final IrSort INT = IrSort.named("Int");
    private static final IrSort BOOL = IrSort.named("Bool");
    private static final IrSort DECIMAL = IrSort.named("Decimal");

    /** struct Donald(name:Int) and struct Daisy(name:Int) — same shape, sibling tags. */
    private static IrSort.Structural struct(String name) {
        Map<String, IrSort> members = new LinkedHashMap<>();
        members.put("name", INT);
        return new IrSort.Structural(name, members, Origin.NONE);
    }

    /** trait Bird — a root trait. */
    private static IrSort.Trait birdTrait() {
        return new IrSort.Trait("Bird", Map.of(), Origin.NONE);
    }

    /** trait Duck : Bird — a sub-trait, for the transitive-satisfaction leg. */
    private static IrSort.Trait duckTrait() {
        Map<String, IrSort.CallSig> methods = new LinkedHashMap<>();
        methods.put("quack", new IrSort.CallSig(IrSort.CallSig.METHOD, List.of(), INT, Origin.NONE));
        return new IrSort.Trait("Duck", methods, Map.of(), Map.of(), Map.of(), Map.of(),
                "Bird", Origin.NONE);
    }

    private static IrStmt.FunctionDecl donaldQuackImpl() {
        return new IrStmt.FunctionDecl("Donald.quack",
                List.of(new IrParam("self", struct("Donald"))), INT, IrExpr.lit(42), Origin.NONE);
    }

    /**
     * A module: two sibling structs, a Bird→Duck trait chain, {@code Donald} implements {@code Duck},
     * and a transparent union alias {@code AnyNumber:[Decimal|Int]}.
     */
    private static AssignabilityContext ctx() {
        IrModule module = new IrModule("m", List.of(
                new IrStmt.TypeAlias("Donald", struct("Donald"), Origin.NONE),
                new IrStmt.TypeAlias("Daisy", struct("Daisy"), Origin.NONE),
                new IrStmt.TypeAlias("Bird", birdTrait(), Origin.NONE),
                new IrStmt.TypeAlias("Duck", duckTrait(), Origin.NONE),
                new IrStmt.TypeAlias("AnyNumber", IrSort.union(List.of(DECIMAL, INT)), Origin.NONE),
                new IrStmt.TraitImpl("Donald", "Duck", List.of(donaldQuackImpl()), Origin.NONE)),
                IrExpr.lit(0));
        return AssignabilityContext.fromModule(module);
    }

    // --- the catalog leg ----------------------------------------------------

    @Test
    void reflexiveAndConstructible() {
        AssignabilityContext c = ctx();
        assertTrue(Assignability.isA(IrSort.named("Donald"), IrSort.named("Donald"), c));
        assertInstanceOf(Assignability.Made.Ok.class,
                Assignability.construct("Donald", List.of(INT), c));
    }

    @Test
    void transparentUnionAliasMembership() {
        AssignabilityContext c = ctx();
        assertTrue(Assignability.isA(INT, IrSort.named("AnyNumber"), c));
        assertTrue(Assignability.isA(DECIMAL, IrSort.named("AnyNumber"), c));
        assertFalse(Assignability.isA(BOOL, IrSort.named("AnyNumber"), c));
    }

    // --- the closed trait leg (the reason fromModule exists) ----------------

    @Test
    void directImplSatisfiesTrait() {
        assertTrue(Assignability.isA(IrSort.named("Donald"), IrSort.named("Duck"), ctx()));
        assertEquals(Assignability.Assignment.WIDEN,
                Assignability.assign(IrSort.named("Donald"), IrSort.named("Duck"), ctx()));
    }

    @Test
    void transitiveImplSatisfiesBaseTrait() {
        // Donald implements Duck, and Duck : Bird, so Donald is-a Bird — the closure fromModule bakes in.
        assertTrue(Assignability.isA(IrSort.named("Donald"), IrSort.named("Bird"), ctx()));
    }

    @Test
    void nonImplementerDoesNotSatisfyTrait() {
        assertFalse(Assignability.isA(IrSort.named("Daisy"), IrSort.named("Duck"), ctx()));
        assertFalse(Assignability.isA(IrSort.named("Daisy"), IrSort.named("Bird"), ctx()));
    }

    // --- the sibling-recursion regression guard -----------------------------

    @Test
    void siblingsAreUnrelatedAndTerminate() {
        // A struct's shape is registered under its own name by fromModule; a naive nominal-base widen
        // loops. This must terminate and report the siblings as unrelated.
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            AssignabilityContext c = ctx();
            assertFalse(Assignability.isA(IrSort.named("Donald"), IrSort.named("Daisy"), c));
            assertFalse(Assignability.isA(IrSort.named("Daisy"), IrSort.named("Donald"), c));
        });
    }
}

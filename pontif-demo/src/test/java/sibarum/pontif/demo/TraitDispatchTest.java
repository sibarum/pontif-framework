package sibarum.pontif.demo;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.ArithmeticRules;
import sibarum.pontif.core.symbolic.DispatchResult;
import sibarum.pontif.core.symbolic.DispatchTable;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.HypothesisRules;
import sibarum.pontif.core.symbolic.RefinementRules;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.TraitRegistry;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the trait-dispatch slice 1 (runtime): {@link TraitRegistry} +
 * the trait-fallback rule in {@link DispatchTable#resolve}.
 *
 * <p>No surface syntax yet — exercised entirely via hand-built dispatch
 * tables, SymExpr.Record args carrying nominal type names, and direct
 * registry mutation.
 */
class TraitDispatchTest {

    private static final Simplifier SIMPLIFIER = new Simplifier(allRules());

    private static List<RewriteRule> allRules() {
        List<RewriteRule> all = new ArrayList<>();
        all.addAll(HypothesisRules.all());
        all.addAll(RefinementRules.all());
        all.addAll(ArithmeticRules.all());
        return all;
    }

    private static final Sort POINT_STRUCT = Sort.structural("Point",
            Map.of("x", Sort.of("Int"), "y", Sort.of("Int")));

    private static SymExpr pointRecord(String typeName, long x, long y) {
        Map<String, SymExpr> members = new LinkedHashMap<>();
        members.put("x", SymExpr.lit(x));
        members.put("y", SymExpr.lit(y));
        return SymExpr.record(typeName, members);
    }

    // --- TraitRegistry basics -----------------------------------------------

    @Test
    void registry_satisfiesAfterRegister() {
        TraitRegistry r = new TraitRegistry();
        assertFalse(r.satisfies("Duck", "MallardDuck"));
        r.register("Duck", "MallardDuck");
        assertTrue(r.satisfies("Duck", "MallardDuck"));
    }

    @Test
    void registry_multipleSatisfiersPerTrait() {
        TraitRegistry r = new TraitRegistry();
        r.register("Duck", "MallardDuck");
        r.register("Duck", "RubberDuck");
        assertTrue(r.satisfies("Duck", "MallardDuck"));
        assertTrue(r.satisfies("Duck", "RubberDuck"));
        assertEquals(2, r.satisfiersOf("Duck").size());
    }

    @Test
    void registry_unregisteredTrait_yieldsFalse() {
        TraitRegistry r = new TraitRegistry();
        assertFalse(r.satisfies("Duck", "MallardDuck"));
        assertEquals(0, r.satisfiersOf("Duck").size());
    }

    @Test
    void registry_nullInputs_yieldFalse() {
        TraitRegistry r = new TraitRegistry();
        r.register("Duck", "MallardDuck");
        assertFalse(r.satisfies(null, "MallardDuck"));
        assertFalse(r.satisfies("Duck", null));
        assertFalse(r.satisfies(null, null));
    }

    @Test
    void registry_rejectsEmptyKeys() {
        TraitRegistry r = new TraitRegistry();
        assertThrows(IllegalArgumentException.class, () -> r.register("", "T"));
        assertThrows(IllegalArgumentException.class, () -> r.register("T", ""));
        assertThrows(IllegalArgumentException.class, () -> r.register(null, "T"));
    }

    // --- DispatchTable fallback rule ----------------------------------------

    @Test
    void traitFallback_fires_whenTraitSatisfiedAndConcreteImplExists() {
        // Set up: register Point.quack as the only impl. Call site uses
        // Duck.quack with a Point-typed record. Without trait fallback this
        // would NoMatch; with fallback registered, it resolves to Point.quack.
        TraitRegistry registry = new TraitRegistry();
        registry.register("Duck", "Point");
        DispatchTable table = new DispatchTable(registry);

        FunctionDecl pointQuack = FunctionDecl.declaration(
                "Point.quack",
                List.of(new FunctionDecl.Param("self", POINT_STRUCT)),
                Sort.of("Audio"));
        table.register(pointQuack);

        DispatchResult result = table.resolve(
                "Duck.quack",
                List.of(pointRecord("Point", 3, 4)),
                SIMPLIFIER);

        DispatchResult.Resolved resolved = assertInstanceOf(
                DispatchResult.Resolved.class, result);
        assertEquals("Point.quack", resolved.decl().name());
    }

    @Test
    void traitFallback_skipped_whenConcreteTypeNotRegistered() {
        // Point is NOT registered as Duck-satisfier — call should NoMatch
        // exactly as if the fallback didn't exist.
        TraitRegistry registry = new TraitRegistry();
        DispatchTable table = new DispatchTable(registry);

        FunctionDecl pointQuack = FunctionDecl.declaration(
                "Point.quack",
                List.of(new FunctionDecl.Param("self", POINT_STRUCT)),
                Sort.of("Audio"));
        table.register(pointQuack);

        DispatchResult result = table.resolve(
                "Duck.quack",
                List.of(pointRecord("Point", 0, 0)),
                SIMPLIFIER);

        assertInstanceOf(DispatchResult.NoMatch.class, result);
    }

    @Test
    void traitFallback_skipped_whenRecordHasNoTypeName() {
        // Anonymous record (typeName=null) can't satisfy any trait.
        TraitRegistry registry = new TraitRegistry();
        registry.register("Duck", "Point");
        DispatchTable table = new DispatchTable(registry);

        FunctionDecl pointQuack = FunctionDecl.declaration(
                "Point.quack",
                List.of(new FunctionDecl.Param("self", POINT_STRUCT)),
                Sort.of("Audio"));
        table.register(pointQuack);

        DispatchResult result = table.resolve(
                "Duck.quack",
                List.of(pointRecord(null, 0, 0)),
                SIMPLIFIER);

        assertInstanceOf(DispatchResult.NoMatch.class, result);
    }

    @Test
    void traitFallback_skipped_whenFirstArgNotARecord() {
        // First arg is a primitive Lit — no concrete-type info to consult.
        TraitRegistry registry = new TraitRegistry();
        registry.register("Trait", "Anything");
        DispatchTable table = new DispatchTable(registry);

        DispatchResult result = table.resolve(
                "Trait.method",
                List.of(SymExpr.lit(42)),
                SIMPLIFIER);

        assertInstanceOf(DispatchResult.NoMatch.class, result);
    }

    @Test
    void traitFallback_skipped_whenNoDotInCallName() {
        // Bare "quack" — no dotted form. Fallback can't extract a trait name.
        TraitRegistry registry = new TraitRegistry();
        registry.register("Duck", "Point");
        DispatchTable table = new DispatchTable(registry);

        DispatchResult result = table.resolve(
                "quack",
                List.of(pointRecord("Point", 0, 0)),
                SIMPLIFIER);

        assertInstanceOf(DispatchResult.NoMatch.class, result);
    }

    @Test
    void traitFallback_doesNotOverrideDirectMatch() {
        // Direct lookup succeeds — the fallback must not be consulted.
        TraitRegistry registry = new TraitRegistry();
        registry.register("Duck", "Point");
        DispatchTable table = new DispatchTable(registry);

        FunctionDecl duckQuack = FunctionDecl.declaration(
                "Duck.quack",
                List.of(new FunctionDecl.Param("self", POINT_STRUCT)),
                Sort.of("Audio"));
        table.register(duckQuack);

        FunctionDecl pointQuack = FunctionDecl.declaration(
                "Point.quack",
                List.of(new FunctionDecl.Param("self", POINT_STRUCT)),
                Sort.of("Audio"));
        table.register(pointQuack);

        DispatchResult result = table.resolve(
                "Duck.quack",
                List.of(pointRecord("Point", 0, 0)),
                SIMPLIFIER);

        DispatchResult.Resolved resolved = assertInstanceOf(
                DispatchResult.Resolved.class, result);
        // Direct lookup wins — must NOT have been redirected to Point.quack.
        assertEquals("Duck.quack", resolved.decl().name());
    }

    // --- Trait-typed parameter matching (slice 3) ---------------------------

    @Test
    void traitParam_acceptsSatisfyingStructArg() {
        // function describe(d:Duck):Audio
        // describe(donald) where Donald satisfies Duck → resolves
        TraitRegistry registry = new TraitRegistry();
        registry.register("Duck", "Point");
        DispatchTable table = new DispatchTable(registry);

        FunctionDecl describe = FunctionDecl.declaration(
                "describe",
                List.of(new FunctionDecl.Param("d", Sort.of("Duck"))),
                Sort.of("Audio"));
        table.register(describe);

        DispatchResult result = table.resolve(
                "describe",
                List.of(pointRecord("Point", 1, 2)),
                SIMPLIFIER);

        DispatchResult.Resolved resolved = assertInstanceOf(
                DispatchResult.Resolved.class, result);
        assertEquals("describe", resolved.decl().name());
    }

    @Test
    void traitParam_rejectsNonSatisfyingStructArg() {
        // Point is NOT in Duck's satisfier set — describe(point) should fail.
        TraitRegistry registry = new TraitRegistry();
        registry.declareTrait("Duck");  // declared but no satisfiers
        DispatchTable table = new DispatchTable(registry);

        FunctionDecl describe = FunctionDecl.declaration(
                "describe",
                List.of(new FunctionDecl.Param("d", Sort.of("Duck"))),
                Sort.of("Audio"));
        table.register(describe);

        DispatchResult result = table.resolve(
                "describe",
                List.of(pointRecord("Point", 1, 2)),
                SIMPLIFIER);

        assertInstanceOf(DispatchResult.NoMatch.class, result);
    }

    @Test
    void traitParam_rejectsAnonymousRecordArg() {
        // typeName=null can't be a registered satisfier.
        TraitRegistry registry = new TraitRegistry();
        registry.register("Duck", "Point");
        DispatchTable table = new DispatchTable(registry);

        FunctionDecl describe = FunctionDecl.declaration(
                "describe",
                List.of(new FunctionDecl.Param("d", Sort.of("Duck"))),
                Sort.of("Audio"));
        table.register(describe);

        DispatchResult result = table.resolve(
                "describe",
                List.of(pointRecord(null, 0, 0)),
                SIMPLIFIER);

        assertInstanceOf(DispatchResult.NoMatch.class, result);
    }

    @Test
    void traitParam_rejectsPrimitiveArg() {
        // Primitive (non-record) args can never satisfy a struct-style trait.
        TraitRegistry registry = new TraitRegistry();
        registry.register("Duck", "Point");
        DispatchTable table = new DispatchTable(registry);

        FunctionDecl describe = FunctionDecl.declaration(
                "describe",
                List.of(new FunctionDecl.Param("d", Sort.of("Duck"))),
                Sort.of("Audio"));
        table.register(describe);

        DispatchResult result = table.resolve(
                "describe", List.of(SymExpr.lit(42)), SIMPLIFIER);

        assertInstanceOf(DispatchResult.NoMatch.class, result);
    }

    @Test
    void traitParam_mixedWithPrimitiveParam() {
        // function tag(d:Duck, label:Int):Audio
        // tag(donald, 42) — first param trait-promoted, second normal.
        TraitRegistry registry = new TraitRegistry();
        registry.register("Duck", "Point");
        DispatchTable table = new DispatchTable(registry);

        FunctionDecl tag = FunctionDecl.declaration(
                "tag",
                List.of(
                        new FunctionDecl.Param("d", Sort.of("Duck")),
                        new FunctionDecl.Param("label", Sort.of("Int"))),
                Sort.of("Audio"));
        table.register(tag);

        DispatchResult result = table.resolve(
                "tag",
                List.of(pointRecord("Point", 1, 2), SymExpr.lit(42)),
                SIMPLIFIER);

        assertInstanceOf(DispatchResult.Resolved.class, result);
    }

    @Test
    void traitParam_picksByConcreteType_amongMultipleSatisfiers() {
        // function describe(d:Duck) is called with different concrete types,
        // each registered. Trait-param matching accepts each.
        Sort circleStruct = Sort.structural("Circle", Map.of("r", Sort.of("Int")));
        TraitRegistry registry = new TraitRegistry();
        registry.register("Duck", "Point");
        registry.register("Duck", "Circle");
        DispatchTable table = new DispatchTable(registry);

        FunctionDecl describe = FunctionDecl.declaration(
                "describe",
                List.of(new FunctionDecl.Param("d", Sort.of("Duck"))),
                Sort.of("Audio"));
        table.register(describe);

        Map<String, SymExpr> circleMembers = new LinkedHashMap<>();
        circleMembers.put("r", SymExpr.lit(5));
        SymExpr circleArg = SymExpr.record("Circle", circleMembers);

        DispatchResult viaPoint = table.resolve(
                "describe", List.of(pointRecord("Point", 1, 2)), SIMPLIFIER);
        DispatchResult viaCircle = table.resolve(
                "describe", List.of(circleArg), SIMPLIFIER);

        assertInstanceOf(DispatchResult.Resolved.class, viaPoint);
        assertInstanceOf(DispatchResult.Resolved.class, viaCircle);
    }

    @Test
    void traitFallback_picksByConcreteType_amongMultipleSatisfiers() {
        // Duck is satisfied by both Point and Circle. The arg's concrete
        // type chooses which Type.method gets invoked.
        Sort circleStruct = Sort.structural("Circle",
                Map.of("r", Sort.of("Int")));

        TraitRegistry registry = new TraitRegistry();
        registry.register("Duck", "Point");
        registry.register("Duck", "Circle");
        DispatchTable table = new DispatchTable(registry);

        FunctionDecl pointQuack = FunctionDecl.declaration(
                "Point.quack",
                List.of(new FunctionDecl.Param("self", POINT_STRUCT)),
                Sort.of("Audio"));
        FunctionDecl circleQuack = FunctionDecl.declaration(
                "Circle.quack",
                List.of(new FunctionDecl.Param("self", circleStruct)),
                Sort.of("Audio"));
        table.register(pointQuack);
        table.register(circleQuack);

        Map<String, SymExpr> circleMembers = new LinkedHashMap<>();
        circleMembers.put("r", SymExpr.lit(5));
        SymExpr circleArg = SymExpr.record("Circle", circleMembers);

        DispatchResult pointResult = table.resolve(
                "Duck.quack", List.of(pointRecord("Point", 1, 2)), SIMPLIFIER);
        DispatchResult circleResult = table.resolve(
                "Duck.quack", List.of(circleArg), SIMPLIFIER);

        assertEquals("Point.quack",
                ((DispatchResult.Resolved) pointResult).decl().name());
        assertEquals("Circle.quack",
                ((DispatchResult.Resolved) circleResult).decl().name());
    }
}

package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.types.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrStructuralSortTest {

    private static IrSort positive() {
        return IrSort.refined("Int",
                IrExpr.binOp(IrExpr.Op.GT, IrExpr.self(), IrExpr.lit(0)));
    }

    @Test
    void compileStructural_withNamedMembers_yieldsStructuralSort() {
        Map<String, IrSort> members = new LinkedHashMap<>();
        members.put("x", IrSort.named("Int"));
        members.put("y", IrSort.named("Int"));
        IrSort point = IrSort.structural("Point", members);

        Sort compiled = IrCompiler.compileSort(point);

        assertTrue(compiled.isStructural(), "expected structural Sort, got: " + compiled);
        assertEquals("Point", compiled.name());
        assertEquals(2, compiled.members().size());
        assertEquals(Sort.of("Int"), compiled.members().get("x"));
        assertEquals(Sort.of("Int"), compiled.members().get("y"));
    }

    @Test
    void compileStructural_withRefinedMembers_carriesPredicates() {
        Map<String, IrSort> members = new LinkedHashMap<>();
        members.put("count", positive());
        IrSort counter = IrSort.structural("Counter", members);

        Sort compiled = IrCompiler.compileSort(counter);

        assertTrue(compiled.isStructural());
        Sort countMember = compiled.members().get("count");
        assertNotNull(countMember, "expected 'count' member");
        assertTrue(countMember.isRefined(), "count member should be refined");
        assertNotNull(countMember.predicate());
    }

    @Test
    void compileStructural_nestedStructurals_recurseProperly() {
        // Box { inner: Point { x: Int, y: Int } }
        Map<String, IrSort> pointMembers = new LinkedHashMap<>();
        pointMembers.put("x", IrSort.named("Int"));
        pointMembers.put("y", IrSort.named("Int"));
        IrSort point = IrSort.structural("Point", pointMembers);

        Map<String, IrSort> boxMembers = new LinkedHashMap<>();
        boxMembers.put("inner", point);
        IrSort box = IrSort.structural("Box", boxMembers);

        Sort compiled = IrCompiler.compileSort(box);

        assertTrue(compiled.isStructural());
        Sort inner = compiled.members().get("inner");
        assertNotNull(inner);
        assertTrue(inner.isStructural());
        assertEquals("Point", inner.name());
        assertEquals(2, inner.members().size());
    }

    @Test
    void structural_emptyMembers_isAllowed_modelsEmptyRecord() {
        IrSort unit = IrSort.structural("Unit", Map.of());
        Sort compiled = IrCompiler.compileSort(unit);
        assertTrue(compiled.isStructural());
        assertEquals(0, compiled.members().size());
    }

    @Test
    void structural_nullName_throwsAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new IrSort.Structural(null, Map.of(), Origin.NONE));
    }

    @Test
    void structural_emptyName_throwsAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new IrSort.Structural("", Map.of(), Origin.NONE));
    }

    @Test
    void structural_nullMembers_throwsAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new IrSort.Structural("S", null, Origin.NONE));
    }

    @Test
    void factory_defaultsToOriginNONE() {
        IrSort s = IrSort.structural("S", Map.of("a", IrSort.named("Int")));
        assertEquals(Origin.NONE, s.origin());
    }

    @Test
    void functionDecl_canUseStructuralParamSort_compilesSuccessfully() {
        // fn area(p: Point) -> Int = 0
        // We can't yet construct or dispatch on Point values at runtime,
        // but the IR should compile without error — the param sort is just metadata
        // in the dispatch table.
        Map<String, IrSort> pointMembers = new LinkedHashMap<>();
        pointMembers.put("w", IrSort.named("Int"));
        pointMembers.put("h", IrSort.named("Int"));
        IrSort point = IrSort.structural("Point", pointMembers);

        IrStmt.FunctionDecl decl = IrStmt.functionDecl(
                "area",
                List.of(new IrParam("p", point)),
                IrSort.named("Int"),
                IrExpr.lit(0));

        IrModule module = new IrModule("areaModule", List.of(decl), IrExpr.lit(0));

        IrCompiler compiler = new IrCompiler(
                new sibarum.pontif.core.symbolic.Simplifier(List.of()));
        CompiledModule compiled = compiler.compile(module);

        // The compiled module should carry the dispatch entry with our structural sort
        assertFalse(compiled.dispatch().declarationsFor("area").isEmpty());
        Sort paramSort = compiled.dispatch().declarationsFor("area").get(0).parameters().get(0).sort();
        assertTrue(paramSort.isStructural());
        assertEquals("Point", paramSort.name());
    }
}

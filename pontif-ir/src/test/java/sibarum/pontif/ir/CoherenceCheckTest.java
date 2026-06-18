package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The orphan / coherence rule: {@code impl Trait for Type} is allowed only in
 * the module owning the trait or the type — never a third module owning
 * neither (type piracy).
 */
class CoherenceCheckTest {

    private static IrModule geoWithPoint() {
        return new IrModule("geo", List.of(
                IrStmt.typeAlias("Point",
                        IrSort.structural("Point", Map.of("x", IrSort.named("Int"))))),
                IrExpr.lit(0));
    }

    private static IrModule showWithTrait() {
        return new IrModule("show", List.of(
                IrStmt.typeAlias("Show", new IrSort.Trait("Show", Map.of(), Origin.NONE))),
                IrExpr.lit(0));
    }

    private static IrModule implIn(String module) {
        return new IrModule(module, List.of(
                IrStmt.traitImpl("Point", "Show", List.of())),
                IrExpr.lit(0));
    }

    private static Map<String, IrModule> project(IrModule... mods) {
        Map<String, IrModule> m = new LinkedHashMap<>();
        for (IrModule mod : mods) m.put(mod.name(), mod);
        return m;
    }

    @Test
    void implInThirdModule_owningNeitherTraitNorType_isRejected() {
        Map<String, IrModule> mods = project(geoWithPoint(), showWithTrait(), implIn("pirate"));
        CompileException e = assertThrows(CompileException.class,
                () -> CoherenceCheck.check(mods, ModuleSymbolTable.build(mods)));
        assertTrue(e.getMessage().contains("orphan rule") && e.getMessage().contains("pirate"),
                () -> e.getMessage());
    }

    @Test
    void implInTypeOwningModule_isAllowed() {
        // geo owns Point → may impl Show for Point even though show owns Show.
        IrModule geoWithImpl = new IrModule("geo", List.of(
                IrStmt.typeAlias("Point", IrSort.structural("Point", Map.of("x", IrSort.named("Int")))),
                IrStmt.traitImpl("Point", "Show", List.of())), IrExpr.lit(0));
        Map<String, IrModule> mods = project(geoWithImpl, showWithTrait());
        assertDoesNotThrow(() -> CoherenceCheck.check(mods, ModuleSymbolTable.build(mods)));
    }

    @Test
    void implInTraitOwningModule_isAllowed() {
        // show owns Show → may impl Show for Point.
        Map<String, IrModule> mods = project(geoWithPoint(),
                new IrModule("show", List.of(
                        IrStmt.typeAlias("Show", new IrSort.Trait("Show", Map.of(), Origin.NONE)),
                        IrStmt.traitImpl("Point", "Show", List.of())), IrExpr.lit(0)));
        assertDoesNotThrow(() -> CoherenceCheck.check(mods, ModuleSymbolTable.build(mods)));
    }

    @Test
    void singleModuleOwningBoth_isAllowed() {
        IrModule solo = new IrModule("app", List.of(
                IrStmt.typeAlias("Point", IrSort.structural("Point", Map.of("x", IrSort.named("Int")))),
                IrStmt.typeAlias("Show", new IrSort.Trait("Show", Map.of(), Origin.NONE)),
                IrStmt.traitImpl("Point", "Show", List.of())), IrExpr.lit(0));
        Map<String, IrModule> mods = project(solo);
        assertDoesNotThrow(() -> CoherenceCheck.check(mods, ModuleSymbolTable.build(mods)));
    }

    // --- Operator orphan rule (mechanism-1 overloads) ------------------------

    private static IrStmt.FunctionDecl op(String sym, String lt, String rt) {
        return IrStmt.functionDecl(sym,
                List.of(new IrParam("a", IrSort.named(lt)), new IrParam("b", IrSort.named(rt))),
                IrSort.named(rt), IrExpr.lit(0));
    }

    @Test
    void operatorOnUnownedPrimitives_isRejected() {
        // A module that owns neither operand redefining `+(Int, Int)` is type piracy.
        IrModule pirate = new IrModule("pirate", List.of(op("+", "Int", "Int")), IrExpr.lit(0));
        Map<String, IrModule> mods = project(geoWithPoint(), pirate);
        CompileException e = assertThrows(CompileException.class,
                () -> CoherenceCheck.check(mods, ModuleSymbolTable.build(mods)));
        assertTrue(e.getMessage().contains("operator '+'") && e.getMessage().contains("pirate"),
                () -> e.getMessage());
    }

    @Test
    void operatorOnOwnedType_isAllowed() {
        // num.vec owns Vec → may define `+(Vec, Vec)`.
        IrModule vec = new IrModule("num.vec", List.of(
                IrStmt.typeAlias("Vec", IrSort.structural("Vec", Map.of("x", IrSort.named("Int")))),
                op("+", "Vec", "Vec")), IrExpr.lit(0));
        Map<String, IrModule> mods = project(vec, geoWithPoint());
        assertDoesNotThrow(() -> CoherenceCheck.check(mods, ModuleSymbolTable.build(mods)));
    }

    @Test
    void operatorWithOneOwnedOperand_isAllowed() {
        // frac owns Frac → may define `/(Int, Frac)` even though it doesn't own Int.
        IrModule frac = new IrModule("frac", List.of(
                IrStmt.typeAlias("Frac", IrSort.structural("Frac", Map.of("n", IrSort.named("Int")))),
                op("/", "Int", "Frac")), IrExpr.lit(0));
        Map<String, IrModule> mods = project(frac, geoWithPoint());
        assertDoesNotThrow(() -> CoherenceCheck.check(mods, ModuleSymbolTable.build(mods)));
    }
}

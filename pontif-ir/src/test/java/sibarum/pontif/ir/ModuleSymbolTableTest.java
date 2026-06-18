package sibarum.pontif.ir;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The type-association index (import-by-association, Phase 2): each operator
 * overload is indexed by its operand types, and each {@code Type.member} method /
 * static attribute by its prefix type. Pure data — Phase 3's visibility gate consumes
 * it. Primitives (no module owns them) anchor no association.
 */
class ModuleSymbolTableTest {

    private static IrModule module(String name, IrStmt... stmts) {
        return new IrModule(name, List.of(stmts), IrExpr.lit(0));
    }

    private static ModuleSymbolTable tableOf(IrModule... mods) {
        Map<String, IrModule> m = new LinkedHashMap<>();
        for (IrModule mod : mods) m.put(mod.name(), mod);
        return ModuleSymbolTable.build(m);
    }

    private static IrStmt typeDecl(String name) {
        return IrStmt.typeAlias(name, IrSort.structural(name, Map.of("x", IrSort.named("Int"))));
    }

    private static IrStmt op(String sym, String lt, String rt) {
        return IrStmt.functionDecl(sym,
                List.of(new IrParam("a", IrSort.named(lt)), new IrParam("b", IrSort.named(rt))),
                IrSort.named(rt), IrExpr.lit(0));
    }

    @Test
    void operator_indexedByEachDeclaredOperandType() {
        ModuleSymbolTable t = tableOf(module("num.vec", typeDecl("Vec"), op("+", "Vec", "Vec")));
        assertTrue(t.associatedDecls("Vec").contains(
                new ModuleSymbolTable.Association("num.vec", "+", false)), t.associatedDecls("Vec").toString());
    }

    @Test
    void operator_primitiveOperandAnchorsNothing() {
        // /(Int, Frac): associated with Frac (declared), NOT with Int (a primitive,
        // owned by no module — you never import Int).
        ModuleSymbolTable t = tableOf(module("frac", typeDecl("Frac"), op("/", "Int", "Frac")));
        assertTrue(t.associatedDecls("Frac").contains(
                new ModuleSymbolTable.Association("frac", "/", false)), t.associatedDecls("Frac").toString());
        assertEquals(0, t.associatedDecls("Int").size());
    }

    @Test
    void staticMember_indexedByPrefixType() {
        // `let Traction.one = …` → FunctionDecl "Traction.one"; associates with Traction.
        IrModule m = module("phys",
                typeDecl("Traction"),
                IrStmt.functionDecl("Traction.one", List.of(), IrSort.named("Traction"), IrExpr.lit(1)));
        ModuleSymbolTable t = tableOf(m);
        assertTrue(t.associatedDecls("Traction").contains(
                new ModuleSymbolTable.Association("phys", "Traction.one", true)),
                t.associatedDecls("Traction").toString());
    }

    @Test
    void crossModule_overloadInOneModule_associatesWithTypeOwnedByAnother() {
        // §4: importing the type surfaces the overload even though it's declared in a
        // different module. Vec owned by `geom`; `+(Vec,Vec)` declared in `algebra`.
        ModuleSymbolTable t = tableOf(
                module("geom", typeDecl("Vec")),
                module("algebra", op("+", "Vec", "Vec")));
        assertTrue(t.associatedDecls("Vec").contains(
                new ModuleSymbolTable.Association("algebra", "+", false)), t.associatedDecls("Vec").toString());
    }

    @Test
    void unrelatedType_hasNoAssociations() {
        ModuleSymbolTable t = tableOf(module("num.vec", typeDecl("Vec"), op("+", "Vec", "Vec")));
        assertEquals(0, t.associatedDecls("Nonexistent").size());
    }
}

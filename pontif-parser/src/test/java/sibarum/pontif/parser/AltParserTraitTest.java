package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for alt-syntax trait surface:
 * <ul>
 *   <li>{@code let Duck:Type{methodName:[Method(...):Ret], ...}} — trait
 *       declaration, lowers to {@link IrStmt.TypeAlias} with
 *       {@link IrSort.Trait}.</li>
 *   <li>{@code assign trait Donald:Duck { methodName(params):Ret -> body }} —
 *       impl block, lowers to {@link IrStmt.TraitImpl}. Methods get
 *       {@code self:Donald} prepended and names qualified to
 *       {@code Donald.methodName}.</li>
 * </ul>
 */
class AltParserTraitTest {

    private static IrModule parse(String src) throws ParseException {
        return AltParser.parseModule(src, "t");
    }

    // --- let X:Type{...} ----------------------------------------------------

    @Test
    void traitDecl_lowersToTypeAliasWithTraitSort() throws Exception {
        IrModule m = parse("let Duck:Type{quack:[Method():Int]}");
        IrStmt.TypeAlias ta = assertInstanceOf(IrStmt.TypeAlias.class, m.statements().get(0));
        assertEquals("Duck", ta.name());
        IrSort.Trait trait = assertInstanceOf(IrSort.Trait.class, ta.sort());
        assertEquals("Duck", trait.name());
        assertTrue(trait.methods().containsKey("quack"));
    }

    @Test
    void traitDecl_methodWithParams_parses() throws Exception {
        IrModule m = parse("let Eater:Type{eat:[Method(Int):Int]}");
        IrStmt.TypeAlias ta = (IrStmt.TypeAlias) m.statements().get(0);
        IrSort.Trait trait = (IrSort.Trait) ta.sort();
        IrSort.Method eatSig = trait.methods().get("eat");
        assertEquals(1, eatSig.paramSorts().size());
    }

    @Test
    void traitDecl_multipleMethods_parses() throws Exception {
        IrModule m = parse("""
                let Duck:Type{
                  quack:[Method():Int],
                  eat:[Method(Int):Int]
                }
                """);
        IrSort.Trait trait = (IrSort.Trait) ((IrStmt.TypeAlias) m.statements().get(0)).sort();
        assertEquals(2, trait.methods().size());
    }

    @Test
    void traitDecl_withValue_throws() {
        // Trait sorts can't have values; trait decls are type-level only.
        ParseException ex = assertThrows(ParseException.class, () ->
                parse("let Duck:Type{quack:[Method():Int]} = 5"));
        assertTrue(ex.getMessage().toLowerCase().contains("trait"));
    }

    @Test
    void traitDecl_nonMethodMember_isTypedAttribute() throws Exception {
        // A non-Method member sort is a typed DATA attribute (existence + type),
        // not an error — methods and attributes live together in `Type{…}`.
        IrModule m = parse("let Boxed:Type{width:Int, height:Int}");
        IrSort.Trait trait = (IrSort.Trait) ((IrStmt.TypeAlias) m.statements().get(0)).sort();
        assertEquals(0, trait.methods().size());
        assertEquals(2, trait.attributes().size());
        assertTrue(trait.attributes().containsKey("width"));
    }

    @Test
    void traitDecl_methodsAndAttributesTogether_parse() throws Exception {
        IrModule m = parse("let Heavyish:Type{ping:[Method():Int], weight:[Int:@>0]}");
        IrSort.Trait trait = (IrSort.Trait) ((IrStmt.TypeAlias) m.statements().get(0)).sort();
        assertTrue(trait.methods().containsKey("ping"));
        assertInstanceOf(IrSort.Refined.class, trait.attributes().get("weight"));
    }

    @Test
    void traitDecl_memberWithoutType_throws() {
        // A member name with no type is rejected (no typeless attribute).
        assertThrows(ParseException.class, () -> parse("let Duck:Type{weight}"));
    }

    // --- assign trait X:Y { ... } -------------------------------------------

    @Test
    void traitImpl_singleMethod_lowersToTraitImpl() throws Exception {
        IrModule m = parse("""
                let Duck:Type{quack:[Method():Int]}
                struct Donald(name:Int)
                assign trait Donald:Duck {
                  quack():Int -> 42
                }
                """);
        // Trait decl, struct decl, impl block.
        IrStmt.TraitImpl ti = assertInstanceOf(IrStmt.TraitImpl.class, m.statements().get(2));
        assertEquals("Donald", ti.typeName());
        assertEquals("Duck", ti.traitName());
        assertEquals(1, ti.methods().size());

        IrStmt.FunctionDecl impl = ti.methods().get(0);
        assertEquals("Donald.quack", impl.name());
        assertEquals(1, impl.params().size());  // just self
        assertEquals("this", impl.params().get(0).name());
        assertEquals("Donald", ((IrSort.Named) impl.params().get(0).sort()).name());
    }

    @Test
    void traitImpl_methodUsesSelf_parsesCorrectly() throws Exception {
        IrModule m = parse("""
                let Sized:Type{size:[Method():Int]}
                struct Point(x:Int, y:Int)
                assign trait Point:Sized {
                  size():Int -> this.x + this.y
                }
                """);
        IrStmt.TraitImpl ti = (IrStmt.TraitImpl) m.statements().get(2);
        IrStmt.FunctionDecl impl = ti.methods().get(0);
        assertEquals("Point.size", impl.name());
        // Body references this.x and this.y — the parser should resolve
        // self as a Var, not auto-Call it.
        assertInstanceOf(sibarum.pontif.ir.IrExpr.BinOp.class, impl.body());
    }

    @Test
    void traitImpl_methodWithUserParam_selfPrependedAndUserParamFollows() throws Exception {
        IrModule m = parse("""
                let Eater:Type{eat:[Method(Int):Int]}
                struct Cow(mass:Int)
                assign trait Cow:Eater {
                  eat(food:Int):Int -> this.mass + food
                }
                """);
        IrStmt.TraitImpl ti = (IrStmt.TraitImpl) m.statements().get(2);
        IrStmt.FunctionDecl impl = ti.methods().get(0);
        assertEquals("Cow.eat", impl.name());
        assertEquals(2, impl.params().size());
        assertEquals("this", impl.params().get(0).name());
        assertEquals("food", impl.params().get(1).name());
    }

    @Test
    void traitImpl_multipleMethods_allParsed() throws Exception {
        IrModule m = parse("""
                let Duck:Type{
                  quack:[Method():Int],
                  eat:[Method(Int):Int]
                }
                struct Donald(name:Int)
                assign trait Donald:Duck {
                  quack():Int -> 42
                  eat(food:Int):Int -> food + 1
                }
                """);
        IrStmt.TraitImpl ti = (IrStmt.TraitImpl) m.statements().get(2);
        assertEquals(2, ti.methods().size());
        assertEquals("Donald.quack", ti.methods().get(0).name());
        assertEquals("Donald.eat", ti.methods().get(1).name());
    }

    @Test
    void traitImpl_keywordAsMethodName_throws() {
        ParseException ex = assertThrows(ParseException.class, () ->
                parse("""
                        let Duck:Type{match:[Method():Int]}
                        struct Donald(name:Int)
                        assign trait Donald:Duck {
                          match():Int -> 42
                        }
                        """));
        assertTrue(ex.getMessage().toLowerCase().contains("keyword"));
    }
}

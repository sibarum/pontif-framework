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
 * Operator contract members on traits (dispatch-unification B1, reopened;
 * docs/traits.md "Operator contract members"). A trait may name an operator as a
 * {@code [Dispatch(...)]} member — {@code +:[Dispatch(this.type, this.type):this.type]}
 * — a mechanism-1 *bound* the satisfier must witness. This slice is the PARSER:
 * the member lands in {@link IrSort.Trait#operators()}, keyed by the operator
 * symbol, and the v1 homogeneity scope is enforced with a clear error.
 */
class AltParserOperatorTraitTest {

    private static IrModule parse(String src) throws ParseException {
        return AltParser.parseModule(src, "t");
    }

    private static IrSort.Trait traitOf(String src) throws ParseException {
        IrStmt.TypeAlias ta = assertInstanceOf(IrStmt.TypeAlias.class, parse(src).statements().get(0));
        return assertInstanceOf(IrSort.Trait.class, ta.sort());
    }

    private static boolean isSelf(IrSort s) {
        return s instanceof IrSort.Named n && n.name().equals(IrSort.SELF_TYPE);
    }

    @Test
    void operatorMember_landsInOperators_notMethodsOrAttributes() throws Exception {
        IrSort.Trait trait = traitOf("trait Numeric{+:[Dispatch(this.type, this.type):this.type]}");
        assertEquals("Numeric", trait.name());
        assertTrue(trait.operators().containsKey("+"), "operator '+' should land in operators()");
        assertTrue(trait.methods().isEmpty(), "no method members");
        assertTrue(trait.attributes().isEmpty(), "no attribute members");

        IrSort.CallSig d = trait.operators().get("+");
        assertEquals(2, d.paramSorts().size());
        assertTrue(isSelf(d.paramSorts().get(0)) && isSelf(d.paramSorts().get(1)),
                "both operands are this.type");
        assertTrue(isSelf(d.returnSort()), "result is this.type");
    }

    @Test
    void multipleOperatorMembers() throws Exception {
        IrSort.Trait trait = traitOf("""
                trait Numeric{
                  +:[Dispatch(this.type, this.type):this.type],
                  *:[Dispatch(this.type, this.type):this.type]
                }
                """);
        assertTrue(trait.operators().containsKey("+"));
        assertTrue(trait.operators().containsKey("*"));
    }

    @Test
    void operatorAndMethodMembers_coexist() throws Exception {
        IrSort.Trait trait = traitOf("""
                trait Showy{
                  +:[Dispatch(this.type, this.type):this.type],
                  show:[Method():Int]
                }
                """);
        assertTrue(trait.operators().containsKey("+"), "operator in operators()");
        assertTrue(trait.methods().containsKey("show"), "method in methods()");
        assertTrue(trait.operators().get("+") != null);
    }

    @Test
    void comparisonOperatorMember() throws Exception {
        IrSort.Trait trait = traitOf("trait Ord{<:[Dispatch(this.type, this.type):this.type]}");
        assertTrue(trait.operators().containsKey("<"));
    }

    // --- v1 scope: homogeneous self-typed only ------------------------------

    @Test
    void mixedOperandContract_rejectedWithClearError() {
        ParseException e = assertThrows(ParseException.class,
                () -> parse("trait Bad{+:[Dispatch(this.type, Int):this.type]}"));
        assertTrue(e.getMessage().contains("homogeneous"), () -> e.getMessage());
    }

    @Test
    void nonSelfResultContract_rejected() {
        ParseException e = assertThrows(ParseException.class,
                () -> parse("trait Bad{+:[Dispatch(this.type, this.type):Int]}"));
        assertTrue(e.getMessage().contains("homogeneous"), () -> e.getMessage());
    }

    // --- shape / key validity -----------------------------------------------

    @Test
    void operatorMemberWithNonDispatchSort_rejected() {
        ParseException e = assertThrows(ParseException.class,
                () -> parse("trait Bad{+:[Method(this.type):this.type]}"));
        assertTrue(e.getMessage().contains("Dispatch"), () -> e.getMessage());
    }

    @Test
    void nonOverloadableOperatorMember_rejected() {
        ParseException e = assertThrows(ParseException.class,
                () -> parse("trait Bad{&:[Dispatch(this.type, this.type):this.type]}"));
        assertTrue(e.getMessage().contains("overloadable"), () -> e.getMessage());
    }

    @Test
    void duplicateOperatorMember_rejected() {
        ParseException e = assertThrows(ParseException.class,
                () -> parse("""
                        trait Bad{
                          +:[Dispatch(this.type, this.type):this.type],
                          +:[Dispatch(this.type, this.type):this.type]
                        }
                        """));
        assertTrue(e.getMessage().contains("Duplicate"), () -> e.getMessage());
    }
}

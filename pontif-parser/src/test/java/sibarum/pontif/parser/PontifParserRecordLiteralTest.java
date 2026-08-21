package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrStmt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for struct-literal construction in {@link PontifParser}.
 *
 * <p>Two surface forms produce the same {@link IrExpr.Record} shape:
 * <ul>
 *   <li>Positional: {@code Point(1, 2)}
 *   <li>By-name:    {@code Point{x=1, y=2}}
 * </ul>
 *
 * <p>Both validate against the declared struct's fields at parse time
 * (arity for positional, name set for by-name) and canonicalize the
 * resulting record to declared field iteration order regardless of how
 * the source was written.
 */
class PontifParserRecordLiteralTest {

    /** Parses a function body in the context of a {@code Point(x:Int, y:Int)} struct. */
    private static IrExpr parseBodyInPointFunction(String bodySrc) throws ParseException {
        return parseBodyWithPrelude(
                "struct Point(x:Int, y:Int)",
                "function test(n:Int):Int -> " + bodySrc);
    }

    /** Parses a custom prelude + final function decl, returns the last decl's body. */
    private static IrExpr parseBodyWithPrelude(String prelude, String functionDecl)
            throws ParseException {
        IrModule module = PontifParser.parseModule(prelude + "\n" + functionDecl, "test");
        IrStmt last = module.statements().get(module.statements().size() - 1);
        IrStmt.FunctionDecl decl = (IrStmt.FunctionDecl) last;
        return decl.body();
    }

    // --- Positional construction ---------------------------------------------

    @Test
    void positional_construction_produces_record_in_declared_field_order() throws Exception {
        IrExpr body = parseBodyInPointFunction("Point(1, 2)");
        IrExpr.Record r = assertInstanceOf(IrExpr.Record.class, body);
        assertEquals(List.of("x", "y"), List.copyOf(r.members().keySet()));
        assertEquals(1L, ((IrExpr.Lit) r.members().get("x")).value());
        assertEquals(2L, ((IrExpr.Lit) r.members().get("y")).value());
    }

    @Test
    void positional_accepts_computed_values() throws Exception {
        IrExpr body = parseBodyInPointFunction("Point(n + 1, n * 2)");
        IrExpr.Record r = assertInstanceOf(IrExpr.Record.class, body);
        assertInstanceOf(IrExpr.BinOp.class, r.members().get("x"));
        assertInstanceOf(IrExpr.BinOp.class, r.members().get("y"));
    }

    @Test
    void positional_arity_too_few_throws() {
        ParseException ex = assertThrows(ParseException.class, () ->
                parseBodyInPointFunction("Point(1)"));
        assertTrue(ex.getMessage().contains("expects 2 positional"),
                () -> "Unexpected message: " + ex.getMessage());
    }

    @Test
    void positional_arity_too_many_throws() {
        ParseException ex = assertThrows(ParseException.class, () ->
                parseBodyInPointFunction("Point(1, 2, 3)"));
        assertTrue(ex.getMessage().contains("expects 2 positional"),
                () -> "Unexpected message: " + ex.getMessage());
    }

    // --- By-name construction ------------------------------------------------

    @Test
    void by_name_construction_in_declared_order() throws Exception {
        IrExpr body = parseBodyInPointFunction("Point{x=1, y=2}");
        IrExpr.Record r = assertInstanceOf(IrExpr.Record.class, body);
        assertEquals(List.of("x", "y"), List.copyOf(r.members().keySet()));
        assertEquals(1L, ((IrExpr.Lit) r.members().get("x")).value());
        assertEquals(2L, ((IrExpr.Lit) r.members().get("y")).value());
    }

    @Test
    void by_name_free_order_is_canonicalized_to_declared_order() throws Exception {
        IrExpr body = parseBodyInPointFunction("Point{y=2, x=1}");
        IrExpr.Record r = assertInstanceOf(IrExpr.Record.class, body);
        // Source wrote y first, x second — IR must put x first per declaration.
        assertEquals(List.of("x", "y"), List.copyOf(r.members().keySet()));
        assertEquals(1L, ((IrExpr.Lit) r.members().get("x")).value());
        assertEquals(2L, ((IrExpr.Lit) r.members().get("y")).value());
    }

    @Test
    void by_name_accepts_computed_values() throws Exception {
        IrExpr body = parseBodyInPointFunction("Point{x=1+2, y=n}");
        IrExpr.Record r = assertInstanceOf(IrExpr.Record.class, body);
        assertInstanceOf(IrExpr.BinOp.class, r.members().get("x"));
        assertInstanceOf(IrExpr.Var.class, r.members().get("y"));
    }

    @Test
    void by_name_missing_field_throws() {
        ParseException ex = assertThrows(ParseException.class, () ->
                parseBodyInPointFunction("Point{x=1}"));
        assertTrue(ex.getMessage().contains("missing field 'y'"),
                () -> "Unexpected message: " + ex.getMessage());
    }

    @Test
    void by_name_unknown_field_throws() {
        ParseException ex = assertThrows(ParseException.class, () ->
                parseBodyInPointFunction("Point{x=1, y=2, z=3}"));
        assertTrue(ex.getMessage().contains("no field 'z'"),
                () -> "Unexpected message: " + ex.getMessage());
    }

    @Test
    void by_name_duplicate_field_throws() {
        ParseException ex = assertThrows(ParseException.class, () ->
                parseBodyInPointFunction("Point{x=1, x=2, y=3}"));
        assertTrue(ex.getMessage().contains("more than once"),
                () -> "Unexpected message: " + ex.getMessage());
    }

    // --- Composition ---------------------------------------------------------

    @Test
    void nested_struct_literal_parses() throws Exception {
        String prelude = "struct Inner(a:Int, b:Int)\nstruct Outer(inner:Inner, n:Int)";
        IrExpr body = parseBodyWithPrelude(
                prelude,
                "function test(n:Int):Int -> Outer{inner=Inner{a=1, b=2}, n=3}");
        IrExpr.Record outer = assertInstanceOf(IrExpr.Record.class, body);
        IrExpr.Record inner = assertInstanceOf(
                IrExpr.Record.class, outer.members().get("inner"));
        assertEquals(1L, ((IrExpr.Lit) inner.members().get("a")).value());
        assertEquals(2L, ((IrExpr.Lit) inner.members().get("b")).value());
        assertEquals(3L, ((IrExpr.Lit) outer.members().get("n")).value());
    }

    @Test
    void positional_and_by_name_produce_equal_records() throws Exception {
        IrExpr.Record pos = (IrExpr.Record) parseBodyInPointFunction("Point(1, 2)");
        IrExpr.Record nam = (IrExpr.Record) parseBodyInPointFunction("Point{x=1, y=2}");
        // Field set and order must match
        assertEquals(List.copyOf(pos.members().keySet()),
                List.copyOf(nam.members().keySet()));
        assertEquals(((IrExpr.Lit) pos.members().get("x")).value(),
                ((IrExpr.Lit) nam.members().get("x")).value());
        assertEquals(((IrExpr.Lit) pos.members().get("y")).value(),
                ((IrExpr.Lit) nam.members().get("y")).value());
    }

    @Test
    void postfix_field_access_on_by_name_literal() throws Exception {
        IrExpr body = parseBodyInPointFunction("Point{x=1, y=2}.x");
        IrExpr.FieldAccess fa = assertInstanceOf(IrExpr.FieldAccess.class, body);
        assertEquals("x", fa.fieldName());
        assertInstanceOf(IrExpr.Record.class, fa.base());
    }

    @Test
    void postfix_field_access_on_positional_literal() throws Exception {
        IrExpr body = parseBodyInPointFunction("Point(1, 2).y");
        IrExpr.FieldAccess fa = assertInstanceOf(IrExpr.FieldAccess.class, body);
        assertEquals("y", fa.fieldName());
        assertInstanceOf(IrExpr.Record.class, fa.base());
    }

    @Test
    void struct_literal_inside_binop() throws Exception {
        IrExpr body = parseBodyInPointFunction("Point{x=1, y=2}.x + Point(3, 4).y");
        IrExpr.BinOp op = assertInstanceOf(IrExpr.BinOp.class, body);
        assertInstanceOf(IrExpr.FieldAccess.class, op.left());
        assertInstanceOf(IrExpr.FieldAccess.class, op.right());
    }

    // --- Disambiguation ------------------------------------------------------

    @Test
    void undeclared_name_with_paren_becomes_call_not_record() throws Exception {
        // No `struct Foo` decl — `Foo(1, 2)` must still parse, but as a Call.
        // The dispatch table will reject it at compile time, which is the
        // appropriate error layer.
        IrModule module = PontifParser.parseModule(
                "function test(n:Int):Int -> Foo(1, 2)", "test");
        IrStmt.FunctionDecl decl = (IrStmt.FunctionDecl) module.statements().get(0);
        assertInstanceOf(IrExpr.Call.class, decl.body());
    }
}

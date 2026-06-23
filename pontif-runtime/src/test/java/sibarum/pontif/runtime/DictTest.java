package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Slice 2 — dictionaries (anonymous by-name aggregates) + the `.{}` named
 * decomposition payload's `let` consumer. A dictionary is the by-name sibling
 * of the tuple: same record substrate, no new node. By-name reads are
 * projections (partial-honest); an unknown key is a lie; positional keys are
 * destructure-only.
 */
class DictTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "t.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    // --- literal + display + access ---

    @Test
    void dictLiteral_roundtripsAsAnonymousRecord() throws Exception {
        Object r = run("{a = 1, b = true}");
        RecordValue rv = assertInstanceOf(RecordValue.class, r);
        assertNull(rv.typeName());
        assertEquals(1L, rv.members().get("a"));
        assertEquals(true, rv.members().get("b"));
    }

    @Test
    void dictLiteral_displaysByName() throws Exception {
        assertEquals("{a: 1, b: 2}", String.valueOf(run("{a = 1, b = 2}")));
    }

    @Test
    void dictField_readsByName() throws Exception {
        assertEquals(1L, run("let d = {a = 1, b = 2} d.a"));
    }

    @Test
    void parenBlock_letChain_evaluates() throws Exception {
        // BRACE-AGGREGATES WAR: the block role is parens now; `{…}` is an
        // aggregate. The dict literal `{a = 1, b = 2}` (above) and the paren
        // block must coexist without the dict lookahead eating either.
        assertEquals(3L, run("( let a = 1 let b = 2 a + b )"));
    }

    @Test
    void duplicateKey_isRejected() {
        assertThrows(ParseException.class, () -> run("{a = 1, a = 2}"));
    }

    // --- `let SOURCE.{...}` — the value consumer of the decomposition payload ---

    @Test
    void letDecomposition_bindsByName() throws Exception {
        assertEquals(3L, run("let d = {a = 1, b = 2} let d.{a, b} a + b"));
    }

    @Test
    void letDecomposition_renameBecomesLocalName() throws Exception {
        assertEquals(3L, run("let d = {a = 1, b = 2} let d.{a, b -> bee} a + bee"));
    }

    @Test
    void letDecomposition_partialProjectionIsHonest() throws Exception {
        // By-name reads are projections — taking a subset claims nothing false.
        assertEquals(2L, run("let d = {a = 1, b = 2, c = 3} let d.{b} b"));
    }

    @Test
    void letDecomposition_unknownKeyIsRejected() {
        // Naming a key the source provably lacks is a lie — compile error.
        assertThrows(ParseException.class,
                () -> run("let d = {a = 1} let d.{nope} nope"));
    }

    @Test
    void letDecomposition_topLevel() throws Exception {
        assertEquals(1L, run("let d = {a = 1, b = 2}\nlet d.{a -> x}\nx"));
    }

    @Test
    void letDecomposition_onStructSource_projectsFields() throws Exception {
        // The struct is the named cell of the same grid — by-name projection
        // reads its fields identically.
        assertEquals(7L, run(
                "struct Point(x:Int, y:Int)\nlet p = Point(7, 9) let p.{x -> px} px"));
    }

    @Test
    void letDecomposition_onTupleSource_isRejected() {
        // Positional aggregates are destructure-only; by-name decomposition of
        // a tuple would bypass arity-totality.
        assertThrows(ParseException.class,
                () -> run("let t = (1, 2) let t.{_0 -> a} a"));
    }

    @Test
    void letDecomposition_duplicateBinder_isRejected() {
        assertThrows(ParseException.class,
                () -> run("let d = {a = 1, b = 2} let d.{a -> x, b -> x} x"));
    }
}

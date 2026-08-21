package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.PontifParser;
import sibarum.pontif.parser.ParseException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Standing index declarations (docs/stream-queries.md §3, docs/keyed.md) — Slice B1:
 * `assign <unique|ordinal|cardinal> index NAME:[ (n:T) -> keyExpr ]`. An index is a NAMED
 * key-mapping T→K decoupled from T's intrinsic identity, so several views over one T may key
 * it differently. B1 parses the declaration and type-checks the key-transform (its projection
 * validated against T) by lowering to a marker `#index#` FunctionDecl — the action/conduit
 * precedent. It drives no structure and enforces no constraint (kind is a hint only);
 * registration (B2) and pushdown (Slice C) follow.
 */
class AssignIndexTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = PontifParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    private CompiledModule compile(String src) throws ParseException, CompileException {
        IrModule module = PontifParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        return new IrCompiler(simp).compile(module);
    }

    @Test void uniqueIndexOnAField_compiles() throws Exception {
        assertEquals("0", String.valueOf(run("""
                struct User(id:Int, name:String)
                assign unique index byId:[ (n:User) -> n.id ]
                0""")));
    }

    @Test void ordinalAndCardinalKinds_compile() throws Exception {
        assertEquals("0", String.valueOf(run("""
                struct User(id:Int, name:String)
                assign ordinal index byName:[ (n:User) -> n.name ]
                0""")));
        assertEquals("0", String.valueOf(run("""
                struct User(id:Int, name:String)
                assign cardinal index byName:[ (n:User) -> n.name ]
                0""")));
    }

    @Test void compoundKey_tupleReturn_compiles() throws Exception {
        assertEquals("0", String.valueOf(run("""
                struct User(id:Int, name:String)
                assign unique index byBoth:[ (n:User) -> {n.id, n.name} ]
                0""")));
    }

    @Test void multipleIndexesOnOneType_coexist() throws Exception {
        // The decoupling: two views keying the same T differently.
        assertEquals("0", String.valueOf(run("""
                struct User(id:Int, name:String)
                assign unique index byId:[ (n:User) -> n.id ]
                assign ordinal index byName:[ (n:User) -> n.name ]
                0""")));
    }

    @Test void registersIndexes_byElementType_withNameAndKind() throws Exception {
        // B2: the declarations are recognized and recorded in CompiledModule.indexesByType,
        // keyed by element type, one list entry per view (decoupled key-mappings).
        CompiledModule m = compile("""
                struct User(id:Int, name:String)
                assign unique index byId:[ (n:User) -> n.id ]
                assign ordinal index byName:[ (n:User) -> n.name ]
                0""");
        List<CompiledModule.CompiledIndex> idx = m.indexesByType().get("User");
        assertNotNull(idx, "expected indexes registered under element type 'User'");
        assertEquals(2, idx.size());
        assertEquals("byId", idx.get(0).name());
        assertEquals("unique", idx.get(0).kind());
        assertEquals("User", idx.get(0).elementType());
        assertEquals("byName", idx.get(1).name());
        assertEquals("ordinal", idx.get(1).kind());
    }

    @Test void keyTransform_isTypeChecked_badProjectionRejected() {
        // The payoff of lowering to a real FunctionDecl: the projection is validated against
        // T, so a non-existent field is a compile error, not a silent no-op.
        assertThrows(CompileException.class, () -> run("""
                struct User(id:Int, name:String)
                assign unique index byBad:[ (n:User) -> n.nonexistent ]
                0"""));
    }
}

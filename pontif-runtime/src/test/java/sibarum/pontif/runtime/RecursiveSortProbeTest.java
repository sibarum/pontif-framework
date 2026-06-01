package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KNOWN LIMITATION (documented, 2026-05-31): Pontif has <b>no recursive
 * sorts</b>. {@code AliasResolver} resolves a struct's member types by eager
 * structural substitution, so any self-reference trips its cycle detector and
 * is rejected as a "Cyclic type alias chain". This blocks linked lists, trees,
 * ASTs, JSON — and the struct-tree proof-authoring approach (a {@code Split}
 * that contains {@code Refinement}s).
 *
 * <p>Supporting recursion means teaching the resolver to allow recursion
 * <em>through a constructor boundary</em> (a contractive/productive recursive
 * type) while still rejecting degenerate alias-to-alias cycles. Flip these
 * tests when that lands.
 */
class RecursiveSortProbeTest {

    private static String compileOutcome(String src) {
        try {
            IrModule module = AltParser.parseModule(src, "rec.ptf");
            Simplifier simp = new Simplifier(List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
            new IrCompiler(simp).compile(module);
            return "COMPILED OK";
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    @Test
    void selfReferentialStructIsRejected() {
        String out = compileOutcome("module m\nstruct Node(v:Int, next:Node)\n42");
        assertTrue(out.contains("Cyclic type alias chain"),
                () -> "expected recursive struct to be rejected; got: " + out);
    }

    @Test
    void recursiveUnionIsRejected() {
        // The shape a Refinement proof tree needs: a union including a struct
        // that refers back to the union.
        String out = compileOutcome("""
                module m
                struct Leaf(tag:Int)
                struct Split(p:Int, t:[Leaf|Split], f:[Leaf|Split])
                42""");
        assertTrue(out.contains("Cyclic type alias chain"),
                () -> "expected recursive union to be rejected; got: " + out);
    }
}

package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.PontifParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Recursive types (landed): Pontif structs are <b>nominal</b> — a struct
 * reference stays {@code IrSort.Named} and is resolved by name against the type
 * registry on demand, never inlined — so a struct may refer to itself (directly,
 * or through a union) and {@code AliasResolver} admits it. The contractiveness
 * discipline ("recursion must pass through a constructor") falls out of
 * excluding structs from the inlining table: a constructor-free abbreviation
 * cycle ({@code type A = [A|Int]}) is still rejected, recursion through a struct
 * constructor is accepted.
 *
 * <p>This unblocks linked lists, trees, ASTs, JSON — and the struct-tree
 * proof-authoring approach (a {@code Split} that contains {@code Refinement}s).
 *
 * <p>Was {@code RecursiveSortProbeTest}'s rejection pin; now its admission pin.
 */
class RecursiveSortProbeTest {

    private static String compileOutcome(String src) {
        try {
            IrModule module = PontifParser.parseModule(src, "rec.ptf");
            Simplifier simp = new Simplifier(List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
            new IrCompiler(simp).compile(module);
            return "COMPILED OK";
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** Compile + interpret Pontif-syntax source end to end. */
    private Object run(String src) throws Exception {
        IrModule module = PontifParser.parseModule(src, "rec.ptf");
        Simplifier simp = new Simplifier(List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        IrCompiler compiler = new IrCompiler(simp);
        CompiledModule compiled = compiler.compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test
    void selfReferentialStructCompiles() {
        // struct Node(v:Int, next:Node) — Node refers to itself directly. The
        // resolver no longer unrolls it, so it compiles instead of tripping the
        // old "Cyclic type alias chain".
        String out = compileOutcome("module m\nstruct Node(v:Int, next:Node)\n42");
        assertEquals("COMPILED OK", out);
    }

    @Test
    void recursiveUnionCompiles() {
        // The shape a Refinement proof tree needs: a union including a struct
        // that refers back to the union. Recursion passes through the Split
        // constructor, so it's contractive and admitted.
        String out = compileOutcome("""
                module m
                struct Leaf(tag:Int)
                struct Split(p:Int, t:[Leaf|Split], f:[Leaf|Split])
                42""");
        assertEquals("COMPILED OK", out);
    }

    // The contractiveness line — a constructor-free abbreviation cycle
    // (type A = B; type B = A) is still rejected — is pinned by
    // TypeAliasIntegrationTest.cyclicAliasChain_isACompileError; the discipline
    // here is that recursion THROUGH a struct constructor (above) is admitted
    // while that pure-abbreviation cycle is not.

    @Test
    void recursiveTypeValue_roundTripsThroughConstructionAndTraversal() throws Exception {
        // A genuinely recursive type (Pair refers to itself through the
        // [Leaf|Pair] union), a value of it constructed two levels deep, and a
        // field read back out — end to end through compile + interpret.
        String src = """
                module m
                struct Leaf(v:Int)
                struct Pair(a:[Leaf|Pair], b:[Leaf|Pair])
                function sumLeaves(p:Pair):Int -> p.a.v + p.b.v
                sumLeaves(Pair(Leaf(3), Leaf(4)))
                """;
        assertEquals(7L, run(src));
    }

    @Test
    void nominalStructParam_stillCheckedStructurally() {
        // Nominalizing structs (param sort is now a by-reference name, not an
        // inlined structural sort) must NOT lose shape checking: passing a bare
        // Int where a Point is required is rejected, because the registry
        // resolves Point to its definition at check time.
        String src = """
                module m
                struct Point(x:Int, y:Int)
                function id(p:Point):Point -> p
                id(42)
                """;
        String out = compileOutcome(src);
        if (out.equals("COMPILED OK")) {
            // Compiles fine; the shape violation surfaces at run time.
            try {
                run(src);
            } catch (Throwable t) {
                return; // rejected as expected
            }
            throw new AssertionError("expected id(42) to be rejected (42 is not a Point)");
        }
        // Or it's rejected at compile time — also acceptable.
    }
}

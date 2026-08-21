package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.runtime.PontifRunner.Engine;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Regression guard for the recursive-union type-checking blowup
 * (docs/recursive-union-typecheck-blowup.md).
 *
 * <p>Type-checking a nested/structural {@code match} over an {@code N}-member recursive
 * closed union used to be super-exponential in arity — {@code ≈ ×90 per +2 members},
 * so the 12-member {@code AlgExpr} union hung the compiler for ~19 minutes. The driver
 * was {@code Refinements.imply} re-deriving the identical {@code (union ⊑ union)}
 * obligation on every recursive-field descent, because the coinductive back-edge guard
 * only covered named struct pairs and not the anonymous union pair. With that pair now
 * guarded, compile time is polynomial in arity (12 members type-check in tens of ms).
 *
 * <p>{@link #arity12TypeChecksPromptly()} is the timing guard: a future change that
 * reintroduces un-guarded recursive-type work bends the curve back up and blows the
 * (deliberately generous) budget. {@link #recursiveUnionMatchEvaluatesCorrectly()} is
 * the semantics guard: the coinductive assumption must break the type-checking loop
 * <em>without</em> weakening what {@code match}/dispatch actually compute at runtime.
 */
class RecursiveUnionTypeCheckTest {

    private final PontifCompiler compiler = new PontifCompiler();

    /**
     * The §3 minimal repro at arity {@code n}: {@code n} binary-node structs over one
     * recursive closed union {@code T}, and a single nested-match {@code same} over it.
     * Pure type-checking stress — the all-binary nodes have no leaf, so no value is
     * constructed; the blowup was entirely in checking {@code same}'s body.
     */
    private static String sameOverArity(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append("struct K").append(i).append("(a:T, b:T)\n");
        }
        b.append("let T:Type[");
        for (int i = 0; i < n; i++) {
            b.append(i > 0 ? " | " : "").append("K").append(i);
        }
        b.append("]\n");
        b.append("function both(p:Bool, q:Bool):Bool -> match p { [Bool:true] -> q  [Bool:false] -> false }\n");
        b.append("function same(x:T, y:T):Bool -> match x {\n");
        for (int i = 0; i < n; i++) {
            b.append("  [K").append(i).append("(a, b)] -> match y { [K").append(i)
                    .append("(c, d)] -> both(same(a, c), same(b, d))  [_] -> false }\n");
        }
        b.append("}\n");
        b.append("true\n");
        return b.toString();
    }

    @Test
    void arity12TypeChecksPromptly() {
        // Pre-fix: an unterminating (~19-minute) hang at arity 12. Post-fix: tens of ms.
        // The budget is generous on purpose — it is a curve-bend detector, not a microbenchmark.
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            PontifCompiler.CompileResult r = compiler.compile(sameOverArity(12), "recursive-union.ptf");
            assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                    () -> "arity-12 recursive union should type-check; got " + r);
        });
    }

    @Test
    void recursiveUnionMatchEvaluatesCorrectly() {
        // A recursive union WITH a leaf, so real trees can be built and compared — pins that
        // the coinductive type-check shortcut left runtime match/dispatch semantics intact.
        String program = """
                struct Leaf(v:Int)
                struct Node(l:Tree, r:Tree)
                let Tree:Type[Leaf | Node]
                function both(p:Bool, q:Bool):Bool -> match p { [Bool:true] -> q  [Bool:false] -> false }
                function same(x:Tree, y:Tree):Bool -> match x {
                  [Leaf(a)] -> match y { [Leaf(b)] -> a == b  [_] -> false }
                  [Node(la, ra)] -> match y { [Node(lb, rb)] -> both(same(la, lb), same(ra, rb))  [_] -> false }
                }
                """;
        assertEquals("true", run(program
                + "same(Node(Leaf(1), Leaf(2)), Node(Leaf(1), Leaf(2)))\n"));
        assertEquals("false", run(program
                + "same(Node(Leaf(1), Leaf(2)), Node(Leaf(1), Leaf(9)))\n"));
        assertEquals("false", run(program
                + "same(Leaf(1), Node(Leaf(1), Leaf(2)))\n"));
    }

    private String run(String src) {
        PontifCompiler.CompileResult r = compiler.compile(src, "recursive-union.ptf");
        PontifCompiler.CompileResult.Compiled compiled =
                assertInstanceOf(PontifCompiler.CompileResult.Compiled.class, r,
                        () -> "should compile; got " + r);
        return new PontifRunner().run(compiled.program(), Engine.INTERPRETER).text();
    }
}

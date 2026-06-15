package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrInterpreter;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The iteration construct's <em>alt-syntax</em> surface (docs/iteration.md §8),
 * slice 1 — map + filter. Parses `iter(src).{…} { match value … }` end-to-end:
 * AltParser → {@link sibarum.pontif.ir.IrExpr.Iterate} → IrInterpreter. (The
 * reference S-expr parser stays sugar-free; this sugar is alt-only.)
 */
class IterationParseTest {

    private Object run(String src) throws Exception {
        IrModule module = AltParser.parseModule(src, "iter.ptf");
        Simplifier simp = new Simplifier(List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    private static List<Object> heads(Object chain) {
        List<Object> out = new ArrayList<>();
        while (chain instanceof RecordValue rv && "Element".equals(rv.typeName())) {
            out.add(rv.get("head", Origin.NONE));
            chain = rv.get("rest", Origin.NONE);
        }
        return out;
    }

    private static final String STREAM_DECLS = """
            struct Leaf()
            struct Element(head:Int, rest:[Element|Leaf])
            """;

    @Test
    void map_doublesEachElement() throws Exception {
        // Pure map: only `value` destructured; the bare-value arm → default stream.
        Object r = run(STREAM_DECLS + """
                iter(Element(1, Element(2, Element(3, Leaf())))).{value} {
                  match value
                    [_] -> value * 2
                }
                """);
        assertEquals(List.of(2L, 4L, 6L), heads(r));  // single output → returned directly
    }

    @Test
    void filter_partitionsAcceptAndReject() throws Exception {
        // accept/reject → two streams; nothing erased.
        RecordValue r = (RecordValue) run(STREAM_DECLS + """
                iter(Element(1, Element(2, Element(3, Leaf())))).{value, accept, reject} {
                  match value
                    [@>1] -> accept(value)
                    [_]   -> reject(value)
                }
                """);
        assertEquals(List.of(2L, 3L), heads(r.get("accept", Origin.NONE)));
        assertEquals(List.of(1L), heads(r.get("reject", Origin.NONE)));
    }

    @Test
    void filterAndMap_acceptCarriesATransform() throws Exception {
        // accept(v)/reject(v) carry a (transformed) value ⇒ filter AND map.
        RecordValue r = (RecordValue) run(STREAM_DECLS + """
                iter(Element(1, Element(2, Element(3, Leaf())))).{value, accept, reject} {
                  match value
                    [@>1] -> accept(value * 10)
                    [_]   -> reject(value)
                }
                """);
        assertEquals(List.of(20L, 30L), heads(r.get("accept", Origin.NONE)));
        assertEquals(List.of(1L), heads(r.get("reject", Origin.NONE)));
    }

    @Test
    void filter_boolArmsRouteCurrentValue() throws Exception {
        // bool arms are the skip disposition — legal because accept/reject are in scope.
        RecordValue r = (RecordValue) run(STREAM_DECLS + """
                iter(Element(1, Element(2, Element(3, Leaf())))).{value, accept, reject} {
                  match value
                    [0] -> false
                    [_] -> true
                }
                """);
        assertEquals(List.of(1L, 2L, 3L), heads(r.get("accept", Origin.NONE)));
        assertEquals(List.of(), heads(r.get("reject", Origin.NONE)));
    }
}

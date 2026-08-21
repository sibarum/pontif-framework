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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stream war: the guard-filter (docs/stream-war.md §3, RULED 2026-07-04 — the reversal of
 * the earlier "takeWhile" reading). A domain-refined element binder is a PER-ELEMENT
 * ADMITTANCE FILTER (the "subscribe" semantic): emit elements in-domain, DROP (skip and
 * continue) the ones that aren't. It is NOT stream-ending — an in-domain element AFTER an
 * out-of-domain one is still emitted. Terminating the stream is a returned {@code Break}
 * value (see {@code StreamBreakTest}), never an input guard.
 *
 * <p>Historical note (WAR breadcrumb): this exact syntax previously lowered to takeWhile
 * (STOP at the first out-of-domain element). James ruled that an argument type refinement
 * is per-element, not a stream-ending semantic — so {@code {1,2,9,3}} + {@code [Int:@<5]}
 * now yields {@code {1,2,3}} (drop the 9), not {@code {1,2}} (stop at 9).
 */
class StreamGuardFilterTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = PontifParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test void dropsOutOfDomain_keepsIterating() throws Exception {
        // The decisive test (reversed from the old takeWhile): the 9 is out of domain,
        // but the in-domain 3 AFTER it is still emitted. filter drops-and-continues.
        assertEquals("{1, 2, 3}", String.valueOf(run("""
                let s = {1, 2, 9, 3}
                &s:[ (el:[Int:@<5]) -> el ]""")));
    }

    @Test void keepsAllInDomain() throws Exception {
        assertEquals("{1, 2, 3, 4}", String.valueOf(run("""
                let s = {1, 2, 3, 4, 5, 6}
                &s:[ (el:[Int:@<5]) -> el ]""")));
    }

    @Test void emptyWhenNoneInDomain() throws Exception {
        assertEquals("{}", String.valueOf(run("""
                let s = {5, 6, 7}
                &s:[ (el:[Int:@<5]) -> el ]""")));
    }

    @Test void wholeStreamWhenAllInDomain() throws Exception {
        assertEquals("{1, 2, 3}", String.valueOf(run("""
                let s = {1, 2, 3}
                &s:[ (el:[Int:@<5]) -> el ]""")));
    }

    @Test void transformsTheAdmittedElements() throws Exception {
        // The guard admits (filters), the body transforms the survivors: el*10 for the
        // in-domain elements, the out-of-domain 9 dropped, iteration continues past it.
        assertEquals("{10, 20, 30}", String.valueOf(run("""
                let s = {1, 2, 9, 3}
                &s:[ (el:[Int:@<5]) -> el * 10 ]""")));
    }
}

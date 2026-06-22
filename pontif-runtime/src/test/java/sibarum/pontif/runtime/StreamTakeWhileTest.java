package sibarum.pontif.runtime;

import org.junit.jupiter.api.Test;
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

/**
 * Stream war: takeWhile (docs/stream-war.md §3, RULED Option A). A domain-refined
 * element binder is the guard — emit while the element is in-domain, STOP at the first
 * that isn't (the stop disposition). The source-driven dual of the generator's
 * domain-refinement halt (§7.9). Distinct from filter (the body-`null` drop): takeWhile
 * HALTS, it does not skip-and-continue.
 */
class StreamTakeWhileTest {

    private Object run(String src) throws ParseException, CompileException {
        IrModule module = AltParser.parseModule(src, "m.ptf");
        Simplifier simp = new Simplifier(java.util.List.<RewriteRule>copyOf(PontifCompiler.defaultRules()));
        CompiledModule compiled = new IrCompiler(simp).compile(module);
        return new IrInterpreter(simp).eval(compiled);
    }

    @Test void emitsWhileInDomain_stopsAtFirstFailure() throws Exception {
        assertEquals("(1, 2, 3, 4)", String.valueOf(run("""
                let s = (1, 2, 3, 4, 5, 6)
                &s:[ (el:[Int:@<5]) -> el ]""")));
    }

    @Test void stopsNotFilters_laterInDomainElementsAreNotKept() throws Exception {
        // The decisive test: an in-domain element AFTER an out-of-domain one must NOT
        // appear. filter would yield (1, 2, 3); takeWhile stops at 9 → (1, 2).
        assertEquals("(1, 2)", String.valueOf(run("""
                let s = (1, 2, 9, 3)
                &s:[ (el:[Int:@<5]) -> el ]""")));
    }

    @Test void emptyWhenFirstElementOutOfDomain() throws Exception {
        assertEquals("()", String.valueOf(run("""
                let s = (5, 6, 7)
                &s:[ (el:[Int:@<5]) -> el ]""")));
    }

    @Test void wholeStreamWhenAllInDomain() throws Exception {
        assertEquals("(1, 2, 3)", String.valueOf(run("""
                let s = (1, 2, 3)
                &s:[ (el:[Int:@<5]) -> el ]""")));
    }

    @Test void transformsTheEmittedElements() throws Exception {
        // takeWhile composes with a map body: emit el*10 while in-domain.
        assertEquals("(10, 20)", String.valueOf(run("""
                let s = (1, 2, 9, 3)
                &s:[ (el:[Int:@<5]) -> el * 10 ]""")));
    }
}

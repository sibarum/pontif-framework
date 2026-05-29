package sibarum.pontif.runtime;

import sibarum.pontif.core.symbolic.DefaultRules;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.runtime.PontifRunner.RunResult;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.LanguageDef;
import sibarum.pontif.parser.ParseException;
import sibarum.pontif.parser.Parser;

import java.util.List;

/**
 * Source → {@link CompiledProgram} pipeline. Parses with a configured
 * {@link LanguageDef}, then runs the IR compiler with a configured set of
 * simplifier rules. Errors at any stage become a {@link CompileResult.Failed}
 * carrying a {@link RunResult} with origin information.
 *
 * <p>Immutable and thread-safe. Reuse one instance across many sources; one
 * compile per source.
 */
public final class PontifCompiler {

    private final LanguageDef language;
    private final List<RewriteRule> simplifierRules;

    public PontifCompiler() {
        this(LanguageDef.defaults(), defaultRules());
    }

    public PontifCompiler(LanguageDef language, List<RewriteRule> simplifierRules) {
        this.language = language;
        this.simplifierRules = List.copyOf(simplifierRules);
    }

    /**
     * The production rule set. Delegates to {@link DefaultRules#production()} —
     * the canonical source. Callers (production code and tests that want
     * to track production behavior) should prefer
     * {@code DefaultRules.production()} directly when in pontif-core's
     * dependency reach; this method remains as the runtime entry point.
     */
    public static List<RewriteRule> defaultRules() {
        return DefaultRules.production();
    }

    public LanguageDef language() {
        return language;
    }

    public List<RewriteRule> simplifierRules() {
        return simplifierRules;
    }

    /**
     * Compile the S-expression reference syntax. Used by the unit-test suite
     * (which is the canonical source of language behavior). Stable; not
     * expected to change.
     */
    public CompileResult compile(String source, String sourceName) {
        IrModule module;
        try {
            module = Parser.parseModule(source, sourceName, language);
        } catch (ParseException pe) {
            return new CompileResult.Failed(
                    RunResult.error("Parse error: " + pe.getMessage(), pe.origin()));
        } catch (RuntimeException e) {
            return new CompileResult.Failed(
                    RunResult.error("Parse error: " + e.getMessage()));
        }
        return compileModule(module, sourceName);
    }

    /**
     * Compile the alt syntax (see {@code docs/alternative-syntax.ptf}). The
     * playground and any alt-syntax-aware caller use this entry point. The
     * IR compile path is shared with {@link #compile} — only the frontend
     * differs.
     */
    public CompileResult compileAlt(String source, String sourceName) {
        IrModule module;
        try {
            module = AltParser.parseModule(source, sourceName);
        } catch (ParseException pe) {
            return new CompileResult.Failed(
                    RunResult.error("Parse error: " + pe.getMessage(), pe.origin()));
        } catch (RuntimeException e) {
            return new CompileResult.Failed(
                    RunResult.error("Parse error: " + e.getMessage()));
        }
        return compileModule(module, sourceName);
    }

    /**
     * Runs the IR compile pipeline on an already-parsed module. Shared by
     * {@link #compile} and {@link #compileAlt}.
     */
    private CompileResult compileModule(IrModule module, String sourceName) {
        Simplifier simplifier = new Simplifier(simplifierRules);
        IrCompiler compiler = new IrCompiler(simplifier);
        CompiledModule compiled;
        try {
            compiled = compiler.compile(module);
        } catch (CompileException ce) {
            return new CompileResult.Failed(
                    RunResult.error("Compile error: " + ce.getMessage(), ce.origin()));
        } catch (RuntimeException e) {
            return new CompileResult.Failed(
                    RunResult.error("Compile error: " + e.getMessage()));
        }
        return new CompileResult.Compiled(new CompiledProgram(compiled, compiler, simplifier, sourceName));
    }

    public sealed interface CompileResult permits CompileResult.Compiled, CompileResult.Failed {
        record Compiled(CompiledProgram program) implements CompileResult {}
        record Failed(RunResult error) implements CompileResult {}
    }
}

package sibarum.pontif.runtime;

import sibarum.pontif.defaults.DefaultRules;
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
import sibarum.pontif.ir.AliasResolver;
import sibarum.pontif.receipts.BuiltinIssuer;
import sibarum.pontif.receipts.Drafter;
import sibarum.pontif.receipts.GraphReference;
import sibarum.pontif.receipts.ProofBinding;
import sibarum.pontif.receipts.ReceiptGraph;
import sibarum.pontif.receipts.ReceiptGraphPrinter;
import sibarum.pontif.receipts.Refinement;
import sibarum.pontif.runtime.module.ModuleLinker;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * Compiles a multi-file project: links the parsed modules into one combined
     * module (FQN-keyed, coherence-checked) and runs it through the same
     * pipeline as a single file — so the return gate, overload check, and
     * runtime are all shared. {@code entryModule}'s {@code main} is the program
     * entry. Single-file {@link #compile}/{@link #compileAlt} are unaffected.
     */
    public CompileResult compileProject(Map<String, IrModule> modules, String entryModule) {
        IrModule combined;
        try {
            combined = ModuleLinker.combine(modules, entryModule);
        } catch (CompileException ce) {
            return new CompileResult.Failed(
                    RunResult.error("Link error: " + ce.getMessage(), ce.origin()));
        } catch (RuntimeException e) {
            return new CompileResult.Failed(RunResult.error("Link error: " + e.getMessage()));
        }
        return compileModule(combined, entryModule);
    }

    /**
     * Runs the IR compile pipeline on an already-parsed module. Shared by
     * {@link #compile}, {@link #compileAlt}, and {@link #compileProject}.
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
        // Return-refinement gate: reject a declared return the proof system
        // can't discharge (and no proof is supplied). Sound but incomplete —
        // it only rejects on a positive NOT-DISCHARGED verdict over a graph
        // that drafted cleanly; programs outside the receipt-graph's current
        // scope (drafting throws) abstain rather than reject, so the drafter's
        // gaps never punish otherwise-valid code.
        Optional<String> unprovable = firstUnprovableReturn(module);
        if (unprovable.isPresent()) {
            return new CompileResult.Failed(RunResult.error(unprovable.get()));
        }
        return new CompileResult.Compiled(new CompiledProgram(compiled, compiler, simplifier, sourceName));
    }

    /**
     * The first function whose declared return refinement the proof system
     * can't discharge, as an error message — or empty if every refined return
     * is discharged (or the program falls outside the receipt-graph's scope,
     * in which case we abstain). Consults the receipt-graph engine
     * ({@link BuiltinIssuer}), which handles recursion via back-references, and
     * any in-source {@code proof f = …} declarations: a branch the engine can't
     * close is rescued if a supplied, validated {@link Refinement} discharges it.
     *
     * <p>Staleness is per-function re-validation, not a snapshot compare: every
     * compile re-checks each proof against its function's freshly-drafted
     * obligation, so an unrelated edit never disturbs a valid proof, while a
     * change that actually breaks one yields a scoped hard error. A supplied
     * proof that no longer discharges (stale or insufficient), a proof naming an
     * unknown function, or a proof orphaned by a dropped return refinement are
     * all hard errors.
     */
    private static Optional<String> firstUnprovableReturn(IrModule module) {
        ReceiptGraph graph;
        try {
            graph = Drafter.draft(AliasResolver.resolve(module));
        } catch (Exception | StackOverflowError e) {
            return Optional.empty();  // outside the drafter's scope → abstain
        }

        // Bind in-source proofs to obligations (shared with ReceiptGraphReport so
        // the two views agree). Any binding problem — unknown/overloaded/orphaned/
        // multi-branch target, duplicate, untranslatable tree — is a hard error.
        ProofBinding.Result bound = ProofBinding.bind(module, graph);
        if (!bound.problems().isEmpty()) {
            return Optional.of(bound.problems().get(0));
        }
        Map<GraphReference, Refinement> proofs = bound.proofs();

        List<BuiltinIssuer.Attempt> attempts = BuiltinIssuer.attemptAll(graph, proofs);
        for (int nodeIndex = 0; nodeIndex < graph.roots().size(); nodeIndex++) {
            final int idx = nodeIndex;
            List<BuiltinIssuer.Attempt> nodeAttempts =
                    attempts.stream().filter(a -> a.nodeIndex() == idx).toList();
            if (!nodeAttempts.isEmpty()
                    && nodeAttempts.stream().anyMatch(a -> !a.discharged())) {
                String fn = graph.roots().get(idx).functionName();
                String obligation = ReceiptGraphPrinter.renderSym(nodeAttempts.get(0).obligation());
                // A proof was supplied for this node but didn't discharge → stale
                // or insufficient (per-function re-validation, not a snapshot diff).
                if (proofs.containsKey(new GraphReference(idx, 0))) {
                    return Optional.of("The supplied proof for '" + fn
                            + "' no longer discharges its obligation " + obligation
                            + " — it's stale or insufficient; update or remove it.");
                }
                return Optional.of("Cannot prove the declared return refinement of '"
                        + fn + "': " + obligation
                        + " — prove it (supply a refinement proof), weaken the declared "
                        + "return, or drop the narrowing.");
            }
        }
        return Optional.empty();
    }

    public sealed interface CompileResult permits CompileResult.Compiled, CompileResult.Failed {
        record Compiled(CompiledProgram program) implements CompileResult {}
        record Failed(RunResult error) implements CompileResult {}
    }
}

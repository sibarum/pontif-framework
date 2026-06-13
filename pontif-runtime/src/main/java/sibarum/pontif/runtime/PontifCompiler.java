package sibarum.pontif.runtime;

import sibarum.pontif.defaults.DefaultRules;
import sibarum.pontif.core.symbolic.RewriteRule;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.runtime.PontifRunner.RunResult;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.CompiledModule;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrStmt;
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
import sibarum.pontif.receipts.ReturnProofBinding;
import sibarum.pontif.runtime.module.ModuleLinker;

import java.util.ArrayList;
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
        // A file that `requires` anything (e.g. builtin proof types from
        // std.proof) opts into the module pipeline: link it so the required
        // builtins are injected, imports validated, and names FQN-resolved. A
        // file with no `requires` stays on the bare single-file path,
        // byte-for-byte unchanged. The link/skip rule is shared with the
        // receipt-graph report via ModuleLinker.combineSingle.
        IrModule linked;
        try {
            linked = ModuleLinker.combineSingle(module);
        } catch (CompileException ce) {
            return new CompileResult.Failed(
                    RunResult.error("Link error: " + ce.getMessage(), ce.origin()));
        } catch (RuntimeException e) {
            return new CompileResult.Failed(RunResult.error("Link error: " + e.getMessage()));
        }
        return compileModule(linked, sourceName);
    }

    /**
     * Compiles a project from disk: discovers the {@code module.ptf.toml} root
     * marker, scans + parses every {@code .ptf} module under {@code rootDir},
     * resolves the entry module (marker {@code entry}, else the sole module with
     * a {@code main}), and links. The on-disk counterpart of
     * {@link #compileProject(Map, String)}.
     */
    public CompileResult compileProjectDir(java.nio.file.Path rootDir) {
        sibarum.pontif.runtime.module.ProjectRoot root;
        Map<String, IrModule> modules;
        try {
            root = sibarum.pontif.runtime.module.ProjectRoot.read(rootDir);
            modules = sibarum.pontif.runtime.module.ModuleLoader.load(rootDir);
        } catch (ParseException pe) {
            return new CompileResult.Failed(
                    RunResult.error("Parse error: " + pe.getMessage(), pe.origin()));
        } catch (java.io.IOException io) {
            return new CompileResult.Failed(RunResult.error("Project load error: " + io.getMessage()));
        } catch (RuntimeException e) {
            return new CompileResult.Failed(RunResult.error("Project load error: " + e.getMessage()));
        }
        if (modules.isEmpty()) {
            return new CompileResult.Failed(
                    RunResult.error("No .ptf modules found under " + rootDir));
        }
        if (root.entryModule().isPresent()) {
            String entry = root.entryModule().get();
            if (!modules.containsKey(entry)) {
                return new CompileResult.Failed(RunResult.error(
                        "Entry module '" + entry + "' (from " + sibarum.pontif.runtime.module
                                .ProjectRoot.MARKER + ") is not among the project's modules: "
                                + modules.keySet()));
            }
            return compileProject(modules, entry);
        }
        String inferred = soleModuleWithMain(modules);
        if (inferred == null) {
            return new CompileResult.Failed(RunResult.error(
                    "No entry module: set `entry = \"…\"` in " + sibarum.pontif.runtime.module
                            .ProjectRoot.MARKER + ", or have exactly one module with a main expression."));
        }
        return compileProject(modules, inferred);
    }

    /** The single module whose {@code main} isn't the trivial {@code 0} placeholder, or null. */
    private static String soleModuleWithMain(Map<String, IrModule> modules) {
        String found = null;
        for (Map.Entry<String, IrModule> e : modules.entrySet()) {
            sibarum.pontif.ir.IrExpr main = e.getValue().main();
            boolean trivial = main instanceof sibarum.pontif.ir.IrExpr.Lit l && l.value() == 0L;
            if (!trivial) {
                if (found != null) return null;  // ambiguous — more than one main
                found = e.getKey();
            }
        }
        return found;
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
    private CompileResult compileModule(IrModule rawModule, String sourceName) {
        // Resolve instance-method calls up front so every consumer below — the
        // IR compiler, the return-refinement gate, and the conservation gate —
        // sees ordinary dispatch Calls rather than the parser's transient
        // MethodCall placeholder. (IrCompiler runs MethodResolver too; on an
        // already-resolved module that pass is a no-op.)
        IrModule module;
        try {
            module = sibarum.pontif.ir.MethodResolver.resolve(rawModule);
        } catch (CompileException ce) {
            return new CompileResult.Failed(
                    RunResult.error("Compile error: " + ce.getMessage(), ce.origin()));
        } catch (RuntimeException e) {
            return new CompileResult.Failed(
                    RunResult.error("Compile error: " + e.getMessage()));
        }
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
        // Conservation gate: the dataflow sibling of the return gate. A
        // `proof f = Lossless()`-style assertion (std.conservation vocabulary)
        // is re-evaluated against the freshly-drafted conservation ledger on
        // every compile; a failing assertion is a hard error whose body
        // includes the printed ledger node — the error IS the receipt.
        // Programs with no conservation proofs pay nothing.
        Optional<String> conservation = firstFailedConservation(module);
        if (conservation.isPresent()) {
            return new CompileResult.Failed(RunResult.error(conservation.get()));
        }
        return new CompileResult.Compiled(new CompiledProgram(compiled, compiler, simplifier, sourceName));
    }

    /**
     * The first conservation assertion the ledger refuses to certify, as an
     * error message — or empty when there are no conservation proofs, every
     * assertion holds, or the program falls outside the conservation drafter's
     * scope (abstain, mirroring the return gate's policy). Fail-closed at the
     * assertion level: untraceable (opaque) or call-mediated flow never
     * certifies, but a program without conservation proofs is never punished
     * for being untraceable.
     */
    private static Optional<String> firstFailedConservation(IrModule module) {
        sibarum.pontif.conservation.ConservationProofs.Result bound =
                sibarum.pontif.conservation.ConservationProofs.bind(module);
        if (!bound.problems().isEmpty()) {
            return Optional.of(bound.problems().get(0));
        }
        if (bound.assertions().isEmpty()) {
            return Optional.empty();
        }
        sibarum.pontif.conservation.ConservationGraph.Ledger ledger;
        try {
            ledger = sibarum.pontif.conservation.ConservationDrafter.draft(
                    AliasResolver.resolve(module));
        } catch (Exception | StackOverflowError e) {
            // Assertions exist but the ledger can't draft — that's a refusal
            // to certify, not an abstention: a conservation proof must never
            // pass un-checked.
            return Optional.of("Conservation proofs could not be checked — the ledger "
                    + "failed to draft: " + e.getMessage());
        }
        for (Map.Entry<String, sibarum.pontif.conservation.ConservationProofs.Assertion> e
                : bound.assertions().entrySet()) {
            var graph = ledger.graph(e.getKey());
            if (graph.isEmpty()) {
                return Optional.of("Conservation proof for '" + e.getKey()
                        + "' has no ledger entry — is it a function declaration?");
            }
            Optional<String> failure = sibarum.pontif.conservation.ConservationProofs
                    .evaluate(e.getKey(), e.getValue(), graph.get());
            if (failure.isPresent()) {
                return failure;
            }
        }
        return Optional.empty();
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
        // Conservation-headed trees are another ledger's propositions — skipped
        // here, bound by firstFailedConservation instead.
        ProofBinding.Result bound = ProofBinding.bind(module, graph,
                sibarum.pontif.conservation.ConservationProofs.HEAD_NAMES);
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

        // `assign proof` return-refinement proofs: the granted refinement lives on
        // the proof, and several proofs may cover one function's regions (proof
        // dispatch). Each is matched to the body branch it proves and validated
        // there — see ReturnProofBinding. The target function declares a base
        // return, so the loop above (which only flags declared-refined nodes)
        // never sees these.
        List<IrStmt.ReturnProof> returnProofs = new ArrayList<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.ReturnProof rp) {
                returnProofs.add(rp);
            }
        }
        Optional<String> returnProofProblem = ReturnProofBinding.validate(returnProofs, graph);
        if (returnProofProblem.isPresent()) {
            return returnProofProblem;
        }

        return Optional.empty();
    }

    public sealed interface CompileResult permits CompileResult.Compiled, CompileResult.Failed {
        record Compiled(CompiledProgram program) implements CompileResult {}
        record Failed(RunResult error) implements CompileResult {}
    }
}

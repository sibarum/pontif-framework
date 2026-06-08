package sibarum.pontif.conservation;

import sibarum.pontif.conservation.ConservationGraph.Capacity;
import sibarum.pontif.conservation.ConservationGraph.Ledger;
import sibarum.pontif.conservation.ConservationGraph.TypedAtom;
import sibarum.pontif.conservation.FlowNode.Arm;
import sibarum.pontif.conservation.FlowNode.OpClass;
import sibarum.pontif.conservation.FlowNode.Recoverability;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrFreeVars;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.ir.TypeRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drafts the conservation graph per {@code docs/conservation-algebra.md} —
 * the taxonomy DERIVED from the sealed IR, not hypothesized over it. The
 * expression switches below are exhaustive with <b>no default case</b>: the
 * compiler itself proves the taxonomy total, and any future {@code IrExpr}
 * variant must declare what it conserves before this module compiles again.
 *
 * <p>Three node kinds (Computation / Branch / Construction); everything else
 * is metadata on flows. Residual flows — lambdas, applications, unresolved
 * calls — are the located ignorance, carrying their over-approximated touch
 * sets so queries fail closed on exactly the right atoms. Recursive calls are
 * no longer residual: cycle members' summaries are computed as a Kleene
 * fixpoint from the optimistic seed (the inductive hypothesis), so a
 * recursive call substitutes its own function's converged summary — the
 * self-referential case of no-duplicate-edges.
 */
public final class ConservationDrafter {

    /**
     * Backstop on Kleene rounds. The lattice argument bounds rounds by the
     * total atom count (each productive round makes at least one strict
     * descent; each atom can descend at most a handful of times), so hitting
     * this cap means monotonicity broke — fail loudly, never fall back to a
     * residual that would silently degrade verdicts.
     */
    private static final int FIXPOINT_ROUND_CAP = 64;

    private ConservationDrafter() {}

    public static Ledger draft(IrModule module) throws CompileException {
        Map<String, IrSort.Structural> structs = TypeRegistry.collect(module);

        // Composition over the call DAG: functions draft in topological order
        // so call sites substitute their callees' summaries (by reference —
        // never re-expanded). Cycle members' summaries are the Kleene
        // fixpoint from the optimistic seed — the recursive call substitutes
        // the function's own converged summary, the self-referential case of
        // no-duplicate-edges.
        Map<String, List<IrStmt.FunctionDecl>> byName = new LinkedHashMap<>();
        List<IrStmt.FunctionDecl> decls = new ArrayList<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                byName.computeIfAbsent(fd.name(), k -> new ArrayList<>()).add(fd);
                decls.add(fd);
            }
        }
        Map<String, Set<String>> callees = new LinkedHashMap<>();
        for (IrStmt.FunctionDecl fd : decls) {
            Set<String> called = new HashSet<>();
            collectCallNames(fd.body(), called);
            called.retainAll(byName.keySet());
            callees.merge(fd.name(), called, (a, b) -> { a.addAll(b); return a; });
        }

        Map<String, ConservationSummary> summaries = new HashMap<>();
        Map<String, ConservationGraph> drafted = new LinkedHashMap<>();
        // Kahn-style: draft a function once every callee (other than itself)
        // is summarized — or overloaded, since overloaded call sites are
        // dispatch-as-Branch and need no summary. Whatever never becomes
        // ready is in a cycle (or blocked behind one).
        Set<String> done = new HashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (IrStmt.FunctionDecl fd : decls) {
                String key = declKey(fd);
                if (done.contains(key)) continue;
                Set<String> needed = callees.getOrDefault(fd.name(), Set.of());
                // No self-exemption: a directly-recursive function is its own
                // cycle and belongs to the fixpoint below.
                boolean ready = needed.stream().allMatch(n ->
                        summaries.containsKey(n)
                        || byName.getOrDefault(n, List.of()).size() > 1);
                if (!ready) continue;
                ConservationGraph graph = draftFunction(fd, structs, byName, summaries);
                drafted.put(key, graph);
                // Overloads share a name; the shared summary must be the
                // conservative MUST-merge — v1: only summarize unambiguous
                // names (callers of overloaded names get per-candidate arms
                // instead, so no summary is needed).
                if (byName.get(fd.name()).size() == 1) {
                    summaries.put(fd.name(), ConservationSummary.of(graph));
                }
                done.add(key);
                progress = true;
            }
        }
        // The fixpoint: whatever Kahn couldn't finish is a cycle member (or a
        // caller blocked behind one). Seed each unambiguous one at lattice
        // top — the inductive hypothesis "assume the recursive call
        // conserves" — then re-draft and re-summarize until nothing changes.
        // Every step is monotone-decreasing in the finite summary lattice
        // (relations degrade, spends drop, residuals grow), so this
        // terminates without widening; the conservation claim it converges
        // to quantifies over completed evaluations (the partial-correctness
        // ruling, docs/conservation-algebra.md).
        List<IrStmt.FunctionDecl> cycleMembers = new ArrayList<>();
        for (IrStmt.FunctionDecl fd : decls) {
            if (!done.contains(declKey(fd))) cycleMembers.add(fd);
        }
        for (IrStmt.FunctionDecl fd : cycleMembers) {
            if (byName.get(fd.name()).size() == 1) {
                summaries.put(fd.name(), ConservationSummary.seed(fd.name(),
                        inputAtomsOf(fd, structs).stream().map(TypedAtom::path).toList()));
            }
        }
        int round = 0;
        boolean changed = !cycleMembers.isEmpty();
        while (changed) {
            changed = false;
            if (++round > FIXPOINT_ROUND_CAP) {
                throw new IllegalStateException("conservation fixpoint exceeded "
                        + FIXPOINT_ROUND_CAP + " rounds over " + cycleMembers.size()
                        + " cycle members — the summary lattice is not"
                        + " monotone-decreasing (a drafter monotonicity bug)");
            }
            for (IrStmt.FunctionDecl fd : cycleMembers) {
                if (byName.get(fd.name()).size() != 1) continue;
                ConservationSummary next = ConservationSummary.of(
                        draftFunction(fd, structs, byName, summaries));
                if (!next.equals(summaries.get(fd.name()))) {
                    summaries.put(fd.name(), next);
                    changed = true;
                }
            }
        }
        // The authoritative graphs: one draft against the converged
        // summaries (intermediate rounds are discarded — no optimistic-seed
        // nodes leak into artifacts).
        for (IrStmt.FunctionDecl fd : cycleMembers) {
            drafted.put(declKey(fd), draftFunction(fd, structs, byName, summaries));
        }
        // Preserve source order.
        List<ConservationGraph> graphs = new ArrayList<>();
        for (IrStmt.FunctionDecl fd : decls) graphs.add(drafted.get(declKey(fd)));
        return new Ledger(graphs);
    }

    private static String declKey(IrStmt.FunctionDecl fd) {
        return fd.name() + "#" + System.identityHashCode(fd);
    }

    private static void collectCallNames(IrExpr expr, Set<String> out) {
        switch (expr) {
            case IrExpr.Call c -> {
                out.add(c.functionName());
                for (IrExpr a : c.args()) collectCallNames(a, out);
            }
            case IrExpr.BinOp op -> {
                collectCallNames(op.left(), out);
                collectCallNames(op.right(), out);
            }
            case IrExpr.LetIn l -> {
                collectCallNames(l.value(), out);
                collectCallNames(l.body(), out);
            }
            case IrExpr.Match m -> {
                collectCallNames(m.scrutinee(), out);
                for (IrExpr.MatchBranch b : m.branches()) collectCallNames(b.result(), out);
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) collectCallNames(v, out);
            }
            case IrExpr.FieldAccess fa -> collectCallNames(fa.base(), out);
            case IrExpr.Apply app -> {
                collectCallNames(app.fn(), out);
                for (IrExpr a : app.args()) collectCallNames(a, out);
            }
            case IrExpr.Lambda lam -> collectCallNames(lam.body(), out);
            case IrExpr.Lit l -> { }
            case IrExpr.Dec d -> { }
            case IrExpr.Chr c -> { }
            case IrExpr.Bool b -> { }
            case IrExpr.Var v -> { }
            case IrExpr.SelfRef s -> { }
            // Creating a metareference is not a call — invocation through a
            // binding is the residual case, handled at the Call site.
            case IrExpr.DispatchRef d -> { }
        };
    }

    /** Flattened input atoms for a declaration — shared by drafting and the fixpoint seed. */
    private static List<TypedAtom> inputAtomsOf(
            IrStmt.FunctionDecl fd, Map<String, IrSort.Structural> structs) {
        List<TypedAtom> inputs = new ArrayList<>();
        for (IrParam p : fd.params()) {
            flatten(AttributePath.of(p.name() + "_0"), p.sort(), structs,
                    new HashSet<>(), inputs);
        }
        return inputs;
    }

    private static ConservationGraph draftFunction(
            IrStmt.FunctionDecl fd, Map<String, IrSort.Structural> structs,
            Map<String, List<IrStmt.FunctionDecl>> declsByName,
            Map<String, ConservationSummary> summaries)
            throws CompileException {
        return draftFunction(fd, structs, declsByName, summaries, false);
    }

    private static ConservationGraph draftFunction(
            IrStmt.FunctionDecl fd, Map<String, IrSort.Structural> structs,
            Map<String, List<IrStmt.FunctionDecl>> declsByName,
            Map<String, ConservationSummary> summaries, boolean inDispatchArm)
            throws CompileException {
        List<TypedAtom> inputs = inputAtomsOf(fd, structs);
        Ctx ctx = new Ctx(fd, structs, declsByName, summaries, inDispatchArm, inputs);
        StringBuilder params = new StringBuilder();
        for (IrParam p : fd.params()) {
            String varName = p.name() + "_0";
            if (params.length() > 0) params.append(", ");
            params.append(varName).append(": ").append(renderSort(p.sort()));
            ctx.env.put(p.name(), new Flow.Verbatim(AttributePath.of(varName)));
            ctx.rename.put(p.name(), SymExpr.var(varName));
        }
        List<AttributePath> outputs = new ArrayList<>();
        List<TypedAtom> outputAtoms = new ArrayList<>();
        flatten(AttributePath.of("r_0"), fd.returnSort(), structs, new HashSet<>(), outputAtoms);
        for (TypedAtom a : outputAtoms) outputs.add(a.path());

        Flow result = draftTail(fd.body(), ctx);
        return new ConservationGraph(
                fd.name(), params.toString(), renderSort(fd.returnSort()),
                inputs, outputs, ctx.nodes, ctx.callFacts, result);
    }

    /** Per-function drafting context: env, nodes, counters, guard renaming. */
    private static final class Ctx {
        final Map<String, Flow> env = new HashMap<>();
        final Map<String, SymExpr> rename = new HashMap<>();
        final Map<String, FlowNode> nodes = new LinkedHashMap<>();
        final Map<String, ConservationGraph.CallFact> callFacts = new LinkedHashMap<>();
        final IrStmt.FunctionDecl enclosing;
        final Map<String, IrSort.Structural> structs;
        final Map<String, List<IrStmt.FunctionDecl>> declsByName;
        final Map<String, ConservationSummary> summaries;
        /** Drafting a dispatch-as-Branch candidate: no re-entry, no summaries. */
        final boolean inDispatchArm;
        /** The function's flattened input atoms — leaf detection for native projection. */
        final List<TypedAtom> inputs;
        int counter = 1;

        Ctx(IrStmt.FunctionDecl enclosing, Map<String, IrSort.Structural> structs,
                Map<String, List<IrStmt.FunctionDecl>> declsByName,
                Map<String, ConservationSummary> summaries, boolean inDispatchArm,
                List<TypedAtom> inputs) {
            this.enclosing = enclosing;
            this.structs = structs;
            this.declsByName = declsByName;
            this.summaries = summaries;
            this.inDispatchArm = inDispatchArm;
            this.inputs = inputs;
        }

        /** True when {@code path} IS an input atom (a scalar leaf — no sub-atoms). */
        boolean isLeafAtom(AttributePath path) {
            return inputs.stream().anyMatch(a -> a.path().equals(path));
        }

        String add(FlowNode node) {
            nodes.put(node.id(), node);
            return node.id();
        }

        String freshId(String kind) { return kind + "_" + (counter++); }
    }

    /**
     * Tail position: the result is CONSTRUCTED (returns are construction).
     * A record tail becomes the return-construction directly (slots keyed
     * {@code r_0.<member>}); a match tail becomes a Branch whose arms
     * recursively construct; anything else constructs the single {@code r_0}
     * slot.
     */
    private static Flow draftTail(IrExpr expr, Ctx ctx) throws CompileException {
        return switch (expr) {
            case IrExpr.LetIn l -> {
                Flow value = draftValue(l.value(), ctx);
                Flow prev = ctx.env.put(l.name(), value);
                Flow result = draftTail(l.body(), ctx);
                if (prev != null) ctx.env.put(l.name(), prev); else ctx.env.remove(l.name());
                yield result;
            }
            case IrExpr.Match m -> draftBranch(m, ctx, true);
            case IrExpr.Record r -> {
                Map<String, Flow> slots = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> member : r.members().entrySet()) {
                    slots.put("r_0." + member.getKey(), draftValue(member.getValue(), ctx));
                }
                String id = ctx.freshId("ret");
                ctx.add(new FlowNode.Construction(id, claimOf(r), slots));
                yield new Flow.FromNode(id);
            }
            // Every other form constructs the single r_0 slot. Listed
            // explicitly — no default — so a new IrExpr variant must take a
            // stance here before this compiles.
            case IrExpr.Lit ignored -> wrapReturn(draftValue(expr, ctx), ctx);
            case IrExpr.Dec ignored -> wrapReturn(draftValue(expr, ctx), ctx);
            case IrExpr.Chr ignored -> wrapReturn(draftValue(expr, ctx), ctx);
            case IrExpr.DispatchRef ignored -> wrapReturn(draftValue(expr, ctx), ctx);
            case IrExpr.Bool ignored -> wrapReturn(draftValue(expr, ctx), ctx);
            case IrExpr.Var ignored -> wrapReturn(draftValue(expr, ctx), ctx);
            case IrExpr.SelfRef ignored -> wrapReturn(draftValue(expr, ctx), ctx);
            case IrExpr.BinOp ignored -> wrapReturn(draftValue(expr, ctx), ctx);
            case IrExpr.Call ignored -> wrapReturn(draftValue(expr, ctx), ctx);
            case IrExpr.Lambda ignored -> wrapReturn(draftValue(expr, ctx), ctx);
            case IrExpr.Apply ignored -> wrapReturn(draftValue(expr, ctx), ctx);
            case IrExpr.FieldAccess ignored -> wrapReturn(draftValue(expr, ctx), ctx);
        };
    }

    private static Flow wrapReturn(Flow value, Ctx ctx) {
        String id = ctx.freshId("ret");
        Map<String, Flow> slots = new LinkedHashMap<>();
        slots.put("r_0", value);
        ctx.add(new FlowNode.Construction(id, "return", slots));
        return new Flow.FromNode(id);
    }

    /** A Match anywhere — tail or value position — is a Branch node. */
    private static Flow draftBranch(IrExpr.Match m, Ctx ctx, boolean tail)
            throws CompileException {
        Flow scrutinee = draftValue(m.scrutinee(), ctx);
        boolean discriminates = m.branches().stream().anyMatch(
                arm -> isRefutable(arm.pattern()));
        List<Flow> discriminants = discriminates ? List.of(scrutinee) : List.of();

        List<Arm> arms = new ArrayList<>(m.branches().size());
        for (IrExpr.MatchBranch arm : m.branches()) {
            String label = armLabel(arm.pattern(), m.scrutinee(), ctx);
            Flow result = tail ? draftTail(arm.result(), ctx)
                               : draftValue(arm.result(), ctx);
            arms.add(new Arm(label, result));
        }
        String id = ctx.freshId("br");
        ctx.add(new FlowNode.Branch(id, discriminants, arms));
        return new Flow.FromNode(id);
    }

    private static boolean isRefutable(IrSort pattern) {
        return switch (pattern) {
            case IrSort.Refined r -> true;
            case IrSort.Named n -> !n.name().equals("_");
            case IrSort.Structural s ->
                    !"_tuple".equals(s.name()) && !"_record".equals(s.name());
            default -> false;
        };
    }

    private static String armLabel(IrSort pattern, IrExpr scrutinee, Ctx ctx) {
        try {
            if (pattern instanceof IrSort.Refined refined) {
                SymExpr predicate = Substitute.apply(
                        IrCompiler.compileSymExpr(refined.predicate()), ctx.rename);
                SymExpr scrut = Substitute.apply(
                        IrCompiler.compileSymExpr(scrutinee), ctx.rename);
                return ConservationLedgerPrinter.renderGuard(
                        Substitute.applySelf(predicate, scrut));
            }
        } catch (CompileException ignored) {
            // fall through to the structural label
        }
        return switch (pattern) {
            case IrSort.Named n -> n.name().equals("_") ? "_" : "pattern: " + n.name();
            case IrSort.Structural s -> "_tuple".equals(s.name()) ? "(…)"
                    : "_record".equals(s.name()) ? "{…}" : s.name() + "(…)";
            default -> "arm";
        };
    }

    /**
     * Value position. Exhaustive over the sealed IR — the standing
     * completeness proof; the residual cases are exactly the algebra's ruled
     * ones: lambda, application, unresolved call.
     */
    private static Flow draftValue(IrExpr expr, Ctx ctx) throws CompileException {
        return switch (expr) {
            // Metadata: constants, naming, binding, path selection.
            case IrExpr.Lit l -> new Flow.Constant(String.valueOf(l.value()));
            case IrExpr.Dec d -> new Flow.Constant(d.value().toPlainString());
            case IrExpr.Chr c -> new Flow.Constant(
                    "'" + sibarum.pontif.core.types.CharValue.render(c.codePoint()) + "'");
            // A metareference is built from statics only — no input content
            // flows into it. Its INVOCATION (a call through the binding) is
            // the residual case, per the Lambda/Apply ruling.
            case IrExpr.DispatchRef d -> new Flow.Constant(
                    d.functionName() + "[" + d.keySorts().stream()
                            .map(ConservationDrafter::renderSort)
                            .collect(java.util.stream.Collectors.joining(", ")) + "]");
            case IrExpr.Bool b -> new Flow.Constant(String.valueOf(b.value()));
            case IrExpr.Var v -> {
                Flow bound = ctx.env.get(v.name());
                yield bound != null ? bound
                        : new Flow.Residual("unbound '" + v.name() + "'", List.of());
            }
            case IrExpr.LetIn l -> {
                Flow value = draftValue(l.value(), ctx);
                Flow prev = ctx.env.put(l.name(), value);
                Flow result = draftValue(l.body(), ctx);
                if (prev != null) ctx.env.put(l.name(), prev); else ctx.env.remove(l.name());
                yield result;
            }
            case IrExpr.FieldAccess fa -> {
                Flow base = draftValue(fa.base(), ctx);
                yield switch (base) {
                    // Native-anatomy projection on a scalar LEAF atom (a
                    // Decimal param): there is no sub-atom to select — the
                    // projection DERIVES part of the atom's content.
                    // Content-class, DEGRADED (influence, not recoverability);
                    // the (unscaled, scale) pair is jointly recoverable, a
                    // cross-node fact like /+% — a later refinement.
                    case Flow.Verbatim v when ctx.isLeafAtom(v.path())
                            && sibarum.pontif.core.Decimals.isAnatomyField(fa.fieldName()) -> {
                        String id = ctx.freshId("c");
                        ctx.add(new FlowNode.Computation(id, "." + fa.fieldName(),
                                OpClass.ARITHMETIC, Recoverability.DEGRADED, List.of(v)));
                        yield new Flow.FromNode(id);
                    }
                    case Flow.Verbatim v -> new Flow.Verbatim(v.path().child(fa.fieldName()));
                    // Projection through a known construction is exact: the
                    // slot's own flow (path-selection metadata collapsing).
                    case Flow.FromNode n when ctx.nodes.get(n.nodeId())
                            instanceof FlowNode.Construction c
                            && c.slots().containsKey(fa.fieldName()) ->
                            c.slots().get(fa.fieldName());
                    case Flow.Residual r -> r;
                    default -> new Flow.Residual(
                            "projection '." + fa.fieldName() + "' on a computed value",
                            touchesOf(base, ctx));
                };
            }
            // Computation.
            case IrExpr.BinOp op -> {
                Flow left = draftValue(op.left(), ctx);
                Flow right = draftValue(op.right(), ctx);
                String id = ctx.freshId("c");
                ctx.add(new FlowNode.Computation(
                        id, opSymbol(op.op()), opClass(op.op()),
                        recoverability(op.op(), left, right), List.of(left, right)));
                yield new Flow.FromNode(id);
            }
            // Discrimination — nested matches are TRACED (they were the v1
            // ledger's false OPAQUEs).
            case IrExpr.Match m -> draftBranch(m, ctx, false);
            // Construction in value position.
            case IrExpr.Record r -> {
                Map<String, Flow> slots = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> member : r.members().entrySet()) {
                    slots.put(member.getKey(), draftValue(member.getValue(), ctx));
                }
                String id = ctx.freshId("k");
                ctx.add(new FlowNode.Construction(id, claimOf(r), slots));
                yield new Flow.FromNode(id);
            }
            // Calls compose: a summarized callee substitutes by reference
            // (no-duplicate-edges); an overloaded callee is dispatch-as-Branch
            // over its candidates; an unsummarized callee (recursion, unknown)
            // is the located ignorance.
            case IrExpr.Call c -> {
                List<Flow> argFlows = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) argFlows.add(draftValue(a, ctx));
                List<IrStmt.FunctionDecl> candidates =
                        ctx.declsByName.getOrDefault(c.functionName(), List.of());
                if (candidates.size() == 1
                        && ctx.summaries.containsKey(c.functionName())) {
                    yield substituteCall(c.functionName(),
                            ctx.summaries.get(c.functionName()),
                            candidates.get(0), argFlows,
                            verbatimSelfReentry(c.functionName(), argFlows, ctx), ctx);
                }
                if (candidates.size() > 1 && !ctx.inDispatchArm) {
                    // Dispatch-as-Branch: one arm per candidate (each its own
                    // summary substitution); properties quantify over arms.
                    // Candidates draft with no summaries and no further
                    // dispatch expansion — guards against mutual recursion
                    // through overloads; conservative and terminating.
                    List<Arm> arms = new ArrayList<>();
                    for (IrStmt.FunctionDecl candidate : candidates) {
                        ConservationGraph g = draftFunction(candidate,
                                ctx.structs, ctx.declsByName, Map.of(), true);
                        ConservationSummary s = ConservationSummary.of(g);
                        arms.add(new Arm("overload " + candidate.name(),
                                substituteCall(c.functionName(), s, candidate,
                                        argFlows, false, ctx)));
                    }
                    String id = ctx.freshId("br");
                    ctx.add(new FlowNode.Branch(id, List.of(), arms));
                    yield new Flow.FromNode(id);
                }
                List<AttributePath> touches = new ArrayList<>();
                for (Flow a : argFlows) touches.addAll(touchesOf(a, ctx));
                // Post-fixpoint, every unambiguous name is summarized before
                // its callers' final draft; what's left is genuinely outside
                // the slice — say which.
                String why = candidates.isEmpty()
                        ? "call '" + c.functionName() + "' (unresolved)"
                        : candidates.size() > 1
                                ? "call '" + c.functionName()
                                        + "' (overload within dispatch arm — out of scope)"
                                : "call '" + c.functionName()
                                        + "' (summary unavailable in dispatch arm)";
                yield new Flow.Residual(why, touches);
            }
            case IrExpr.Lambda lam ->
                    new Flow.Residual("lambda (ruled residual)", freeTouches(lam, ctx));
            case IrExpr.Apply app ->
                    new Flow.Residual("application (ruled residual)", freeTouches(app, ctx));
            case IrExpr.SelfRef s ->
                    new Flow.Residual("self (typing-level)", List.of());
        };
    }

    /**
     * Substitutes a callee's {@link ConservationSummary} at a call site. The
     * call becomes a Computation ("via callee") whose inputs are the caller
     * flows for exactly the callee atoms whose content reaches the result
     * (CONTENT relation; BIT relations pass through a measurement wrapper so
     * the chain class stays honest). Callee-internal branching spend is
     * credited via a single-arm Branch whose discriminants are the spent
     * args' flows — dispatch-as-Branch, literally. Residual-touched callee
     * atoms make the corresponding caller flow residual (fail-closed).
     */
    /**
     * Verbatim self re-entry: a direct self-call whose every argument is the
     * unmodified content of one of the enclosing function's own params, each
     * param appearing exactly once (a permutation counts — the orbit is
     * finite, so pure re-entry revisits a prior state). Flow-based, not
     * syntactic: {@code let y = x in f(y)} is caught; a rebinding through any
     * computation is not. Sound under pure, strict evaluation.
     */
    private static boolean verbatimSelfReentry(
            String callee, List<Flow> argFlows, Ctx ctx) {
        if (!callee.equals(ctx.enclosing.name())) return false;
        List<IrParam> params = ctx.enclosing.params();
        if (argFlows.size() != params.size()) return false;
        Set<String> roots = new HashSet<>();
        for (Flow f : argFlows) {
            if (!(f instanceof Flow.Verbatim v) || !v.path().segments().isEmpty()) {
                return false;
            }
            roots.add(v.path().root());
        }
        if (roots.size() != params.size()) return false;
        for (IrParam p : params) {
            if (!roots.contains(p.name() + "_0")) return false;
        }
        return true;
    }

    private static Flow substituteCall(
            String callee, ConservationSummary summary,
            IrStmt.FunctionDecl decl, List<Flow> argFlows,
            boolean verbatimReentry, Ctx ctx) {
        // callee param root (e.g. "s_0") -> caller arg flow, positionally.
        Map<String, Flow> argByRoot = new LinkedHashMap<>();
        for (int i = 0; i < decl.params().size() && i < argFlows.size(); i++) {
            argByRoot.put(decl.params().get(i).name() + "_0", argFlows.get(i));
        }

        List<Flow> contentInputs = new ArrayList<>();
        List<Flow> spentFlows = new ArrayList<>();
        for (AttributePath atom : summary.inputAtoms()) {
            Flow callerFlow = projectThrough(argByRoot.get(atom.root()), atom, ctx);
            if (callerFlow == null) continue;
            if (summary.residualTouched().contains(atom) || summary.anyPathPoisoned()) {
                contentInputs.add(new Flow.Residual(
                        "via '" + callee + "' (residual inside callee)",
                        touchesOf(callerFlow, ctx)));
                continue;
            }
            switch (summary.relations().get(atom)) {
                case CONTENT -> contentInputs.add(callerFlow);
                case BIT -> {
                    String mid = ctx.freshId("c");
                    ctx.add(new FlowNode.Computation(mid, "bit via " + callee,
                            OpClass.MEASUREMENT, Recoverability.MEASUREMENT_BIT,
                            List.of(callerFlow)));
                    contentInputs.add(new Flow.FromNode(mid));
                }
                case NONE -> { }
            }
            if (summary.spentEverywhere().contains(atom)) {
                spentFlows.add(callerFlow);
            }
        }
        if (summary.anyPathPoisoned()) {
            return new Flow.Residual("via '" + callee + "' (callee untraceable)",
                    argFlows.stream().flatMap(f -> touchesOf(f, ctx).stream()).toList());
        }
        String id = ctx.freshId("c");
        ctx.add(new FlowNode.Computation(id, "via " + callee,
                OpClass.ARITHMETIC, Recoverability.DEGRADED, contentInputs));
        ctx.callFacts.put(id, new ConservationGraph.CallFact(callee, verbatimReentry));
        Flow result = new Flow.FromNode(id);
        if (!spentFlows.isEmpty()) {
            // Credit the callee's universal branching spend at the call site.
            String bid = ctx.freshId("br");
            ctx.add(new FlowNode.Branch(bid, spentFlows,
                    List.of(new Arm("via " + callee, result))));
            result = new Flow.FromNode(bid);
        }
        return result;
    }

    /** Projects a caller arg flow down to a callee atom's path segments. */
    private static Flow projectThrough(Flow argFlow, AttributePath calleeAtom, Ctx ctx) {
        if (argFlow == null) return null;
        Flow current = argFlow;
        for (String segment : calleeAtom.segments()) {
            current = switch (current) {
                case Flow.Verbatim v -> new Flow.Verbatim(v.path().child(segment));
                case Flow.FromNode n when ctx.nodes.get(n.nodeId())
                        instanceof FlowNode.Construction c
                        && c.slots().containsKey(segment) -> c.slots().get(segment);
                // Whole-flow fallback: the atom's content is within it.
                default -> current;
            };
        }
        return current;
    }

    /** Over-approximated atoms reachable through a flow (for residual touch sets). */
    private static List<AttributePath> touchesOf(Flow flow, Ctx ctx) {
        List<AttributePath> out = new ArrayList<>();
        collectTouches(flow, ctx, new HashSet<>(), out);
        return out;
    }

    private static void collectTouches(
            Flow flow, Ctx ctx, Set<String> seen, List<AttributePath> out) {
        switch (flow) {
            case Flow.Verbatim v -> out.add(v.path());
            case Flow.Residual r -> out.addAll(r.touches());
            case Flow.Constant c -> { }
            case Flow.FromNode n -> {
                if (!seen.add(n.nodeId())) return;
                switch (ctx.nodes.get(n.nodeId())) {
                    case FlowNode.Computation c -> {
                        for (Flow f : c.inputs()) collectTouches(f, ctx, seen, out);
                    }
                    case FlowNode.Construction c -> {
                        for (Flow f : c.slots().values()) collectTouches(f, ctx, seen, out);
                    }
                    case FlowNode.Branch b -> {
                        for (Flow f : b.discriminants()) collectTouches(f, ctx, seen, out);
                        for (Arm a : b.arms()) collectTouches(a.result(), ctx, seen, out);
                    }
                }
            }
        }
    }

    private static List<AttributePath> freeTouches(IrExpr expr, Ctx ctx) {
        List<AttributePath> out = new ArrayList<>();
        for (String free : IrFreeVars.freeVars(expr)) {
            if (ctx.env.get(free) instanceof Flow.Verbatim v) out.add(v.path());
        }
        return out;
    }

    private static String claimOf(IrExpr.Record r) {
        return r.typeName() == null ? "_record" : r.typeName();
    }

    // --- op classification per the algebra ---

    private static OpClass opClass(IrExpr.Op op) {
        return switch (op) {
            case ADD, SUB, MUL, DIV, MOD, POW -> OpClass.ARITHMETIC;
            case LT, LE, GT, GE, EQ, NE, APPROX -> OpClass.MEASUREMENT;
            case AND, OR -> OpClass.LOGICAL;
        };
    }

    private static Recoverability recoverability(IrExpr.Op op, Flow left, Flow right) {
        return switch (op) {
            case ADD, SUB -> Recoverability.RECOVERABLE;
            case MUL -> nonzeroConstant(left) || nonzeroConstant(right)
                    ? Recoverability.RECOVERABLE : Recoverability.DEGRADED;
            // Individually lossy; the joint /+% identity is a cross-node fact
            // (a later refinement). Conservative verdict here.
            case DIV, MOD, POW -> Recoverability.DEGRADED;
            case LT, LE, GT, GE, EQ, NE, APPROX -> Recoverability.MEASUREMENT_BIT;
            case AND, OR -> Recoverability.DEGRADED;
        };
    }

    private static boolean nonzeroConstant(Flow flow) {
        if (!(flow instanceof Flow.Constant c)) return false;
        try {
            return new java.math.BigDecimal(c.rendering()).signum() != 0;
        } catch (NumberFormatException nf) {
            return false;
        }
    }

    // --- atoms + capacity (DECLARED base sorts only — never inferred) ---

    private static void flatten(
            AttributePath root, IrSort sort,
            Map<String, IrSort.Structural> structs, Set<String> visiting,
            List<TypedAtom> out) {
        IrSort.Structural structural = switch (sort) {
            case IrSort.Structural s -> s;
            case IrSort.Named n -> structs.get(n.name());
            case IrSort.Refined r -> structs.get(r.name());
            default -> null;
        };
        if (structural == null || visiting.contains(structural.name())) {
            out.add(new TypedAtom(root, capacityOf(sort)));
            return;
        }
        visiting.add(structural.name());
        for (Map.Entry<String, IrSort> member : structural.members().entrySet()) {
            flatten(root.child(member.getKey()), member.getValue(), structs, visiting, out);
        }
        visiting.remove(structural.name());
    }

    private static Capacity capacityOf(IrSort sort) {
        String base = switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            default -> null;
        };
        if ("Bool".equals(base)) return Capacity.BIT;
        // Char joins the NUMERIC rule: a code point is ~21 bits, so a
        // comparison/branch extracts one bit of many — branching spend never
        // exhausts a Char's content the way it exhausts a Bool's.
        if ("Int".equals(base) || "Decimal".equals(base) || "Char".equals(base)) {
            return Capacity.NUMERIC;
        }
        return Capacity.OTHER;
    }

    private static String opSymbol(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/"; case MOD -> "%"; case POW -> "^";
            case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ -> "=="; case NE -> "!="; case APPROX -> "~=";
            case AND -> "&"; case OR -> "|";
        };
    }

    private static String renderSort(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> "[" + r.name() + ":…]";
            case IrSort.Structural s -> "_tuple".equals(s.name())
                    ? "(" + String.join(", ", s.members().values().stream()
                            .map(ConservationDrafter::renderSort).toList()) + ")"
                    : s.name();
            case IrSort.Method f -> "Method";
            case IrSort.Dispatch d -> "Dispatch";
            case IrSort.Trait t -> t.name();
            case IrSort.Union u -> "Union";
            case IrSort.Intersection i -> "Intersection";
        };
    }
}

package sibarum.pontif.conservation;

import sibarum.pontif.conservation.ConservationLedger.ConservationBranch;
import sibarum.pontif.conservation.ConservationLedger.ConservationNode;
import sibarum.pontif.conservation.ConservationLedger.NamedSort;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Drafts the {@link ConservationLedger} from an {@link IrModule} — the
 * dataflow sibling of the receipt-graph {@code Drafter}, and deliberately
 * built "in a similar fashion": stateless single pass, parameters renamed to
 * call-instance form ({@code n} → {@code n_0}), the result rooted at
 * {@code r_0}, one branch per top-level match arm with the arm's refinement
 * predicate as the guard, and calls recorded by reference, never re-expanded
 * (the no-duplicate-edges rule).
 *
 * <p>No reasoning happens here — only transcription of dataflow events.
 * Anything the walk cannot trace becomes an {@link Event.Opaque}: honest
 * ignorance, on which every conservation query fails closed.
 *
 * <p>Draft from the same resolved module the receipt graph drafts from
 * (post-link, post-alias, post-promotion) so aggregate literals carry their
 * claims and canonical field order.
 */
public final class ConservationDrafter {

    private ConservationDrafter() {}

    public static ConservationLedger draft(IrModule module) throws CompileException {
        Map<String, IrSort.Structural> structs = TypeRegistry.collect(module);
        List<ConservationNode> nodes = new ArrayList<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                nodes.add(draftFunction(fd, structs));
            }
        }
        return new ConservationLedger(nodes);
    }

    private static ConservationNode draftFunction(
            IrStmt.FunctionDecl fd, Map<String, IrSort.Structural> structs)
            throws CompileException {
        // Params renamed to call-instance form; the provenance environment is
        // keyed by the ORIGINAL binder names the body uses.
        List<NamedSort> params = new ArrayList<>(fd.params().size());
        Map<String, Provenance> env = new HashMap<>();
        Map<String, SymExpr> renameBindings = new HashMap<>();
        List<AttributePath> inputs = new ArrayList<>();
        for (IrParam p : fd.params()) {
            String varName = p.name() + "_0";
            params.add(new NamedSort(varName, renderSort(p.sort())));
            env.put(p.name(), new Provenance.Path(AttributePath.of(varName)));
            renameBindings.put(p.name(), SymExpr.var(varName));
            flatten(AttributePath.of(varName), p.sort(), structs, new HashSet<>(), inputs);
        }

        List<AttributePath> outputs = new ArrayList<>();
        flatten(AttributePath.of("r_0"), fd.returnSort(), structs, new HashSet<>(), outputs);

        int[] derivedCounter = {1};
        List<ConservationBranch> branches = fd.body() instanceof IrExpr.Match match
                ? draftMatchBranches(match, env, renameBindings, derivedCounter)
                : List.of(draftBranch(fd.body(), Optional.empty(), Optional.empty(),
                        env, derivedCounter));

        return new ConservationNode(
                fd.name(), params, renderSort(fd.returnSort()), inputs, outputs, branches);
    }

    private static List<ConservationBranch> draftMatchBranches(
            IrExpr.Match match, Map<String, Provenance> env,
            Map<String, SymExpr> renameBindings, int[] derivedCounter)
            throws CompileException {
        // The scrutinee's attribute paths are what a discriminating arm consults.
        List<AttributePath> scrutineePaths =
                pathsOf(provenanceOfPure(match.scrutinee(), env));

        List<ConservationBranch> branches = new ArrayList<>(match.branches().size());
        for (IrExpr.MatchBranch arm : match.branches()) {
            Optional<SymExpr> guard = Optional.empty();
            Optional<String> patternNote = Optional.empty();
            boolean discriminates = false;
            if (arm.pattern() instanceof IrSort.Refined refined) {
                SymExpr predicate = Substitute.apply(
                        IrCompiler.compileSymExpr(refined.predicate()), renameBindings);
                SymExpr scrutinee = Substitute.apply(
                        IrCompiler.compileSymExpr(stripToVarOrSelf(match.scrutinee())),
                        renameBindings);
                guard = Optional.of(Substitute.applySelf(predicate, scrutinee));
                discriminates = true;
            } else if (arm.pattern() instanceof IrSort.Named named
                    && !named.name().equals("_")) {
                // A bare named pattern is a claim test — discrimination too.
                patternNote = Optional.of("pattern: " + named.name());
                discriminates = true;
            } else if (arm.pattern() instanceof IrSort.Structural sp
                    && !"_tuple".equals(sp.name()) && !"_record".equals(sp.name())) {
                // A named structural destructure pattern also claims; bare
                // tuple/record destructures are irrefutable — no discrimination.
                patternNote = Optional.of("pattern: " + sp.name() + "(…)");
            }

            List<Event> events = new ArrayList<>();
            if (discriminates && !scrutineePaths.isEmpty()) {
                events.add(new Event.Consult(scrutineePaths));
            }
            ConservationBranch branch = draftBranch(
                    arm.result(), guard, patternNote, env, derivedCounter);
            List<Event> all = new ArrayList<>(events);
            all.addAll(branch.events());
            branches.add(new ConservationBranch(guard, patternNote, all));
        }
        return branches;
    }

    /** Match scrutinees may be wrapped expressions; the guard binds @ to the value itself. */
    private static IrExpr stripToVarOrSelf(IrExpr scrutinee) {
        return scrutinee;
    }

    private static ConservationBranch draftBranch(
            IrExpr body, Optional<SymExpr> guard, Optional<String> patternNote,
            Map<String, Provenance> env, int[] derivedCounter)
            throws CompileException {
        List<Event> events = new ArrayList<>();
        walkToTail(body, new HashMap<>(env), events, derivedCounter);
        return new ConservationBranch(guard, patternNote, events);
    }

    /**
     * Walks let-chains extending the provenance environment, then emits the
     * tail expression into the output slots.
     */
    private static void walkToTail(
            IrExpr expr, Map<String, Provenance> env,
            List<Event> events, int[] derivedCounter) {
        if (expr instanceof IrExpr.LetIn let) {
            Provenance value = provenanceOf(let.value(), env, events, derivedCounter);
            env.put(let.name(), value);
            walkToTail(let.body(), env, events, derivedCounter);
            return;
        }
        emitTail(expr, env, events, derivedCounter);
    }

    /** The branch tail flows into the result: per-member for an aggregate literal, whole otherwise. */
    private static void emitTail(
            IrExpr tail, Map<String, Provenance> env,
            List<Event> events, int[] derivedCounter) {
        if (tail instanceof IrExpr.Record r) {
            AttributePath result = AttributePath.of("r_0");
            for (Map.Entry<String, IrExpr> member : r.members().entrySet()) {
                Provenance source = provenanceOf(member.getValue(), env, events, derivedCounter);
                events.add(new Event.Emit(source, result.child(member.getKey())));
            }
            return;
        }
        Provenance source = provenanceOf(tail, env, events, derivedCounter);
        events.add(new Event.Emit(source, AttributePath.of("r_0")));
    }

    /**
     * The provenance of a value expression, recording {@link Event.Combine}
     * and {@link Event.Call} events as it goes. Untraceable forms yield
     * {@link Provenance.Opaque} and an {@link Event.Opaque} over-approximating
     * the touched paths via free-variable analysis.
     */
    private static Provenance provenanceOf(
            IrExpr expr, Map<String, Provenance> env,
            List<Event> events, int[] derivedCounter) {
        return switch (expr) {
            case IrExpr.Lit l -> new Provenance.Constant(String.valueOf(l.value()));
            case IrExpr.Dec d -> new Provenance.Constant(d.value().toPlainString());
            case IrExpr.Bool b -> new Provenance.Constant(String.valueOf(b.value()));
            case IrExpr.Var v -> env.getOrDefault(
                    v.name(), new Provenance.Opaque("unbound '" + v.name() + "'"));
            case IrExpr.FieldAccess fa -> {
                Provenance base = provenanceOf(fa.base(), env, events, derivedCounter);
                yield base instanceof Provenance.Path p
                        ? new Provenance.Path(p.path().child(fa.fieldName()))
                        : new Provenance.Opaque("field access on " + base.render());
            }
            case IrExpr.BinOp op -> {
                Provenance left = provenanceOf(op.left(), env, events, derivedCounter);
                Provenance right = provenanceOf(op.right(), env, events, derivedCounter);
                String id = "d_" + (derivedCounter[0]++);
                events.add(new Event.Combine(List.of(left, right), opSymbol(op.op()), id));
                yield new Provenance.Derived(id);
            }
            case IrExpr.Call c -> {
                List<Provenance> args = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) {
                    args.add(provenanceOf(a, env, events, derivedCounter));
                }
                String id = "c_" + (derivedCounter[0]++);
                events.add(new Event.Call(c.functionName(), args, id));
                yield new Provenance.CallResult(id);
            }
            case IrExpr.LetIn let -> {
                // A let in value position: trace it like a body prefix.
                Provenance value = provenanceOf(let.value(), env, events, derivedCounter);
                Map<String, Provenance> inner = new HashMap<>(env);
                inner.put(let.name(), value);
                yield provenanceOf(let.body(), inner, events, derivedCounter);
            }
            // Nested constructions, lambdas, applications, nested matches,
            // and the refinement subject are untraceable in v1.
            default -> {
                List<AttributePath> touched = new ArrayList<>();
                for (String free : IrFreeVars.freeVars(expr)) {
                    if (env.get(free) instanceof Provenance.Path p) touched.add(p.path());
                }
                String reason = expr.getClass().getSimpleName().toLowerCase() + " (untraced in v1)";
                events.add(new Event.Opaque(reason, touched));
                yield new Provenance.Opaque(reason);
            }
        };
    }

    /** Pure variant for the scrutinee — no events recorded, paths only. */
    private static Provenance provenanceOfPure(IrExpr expr, Map<String, Provenance> env) {
        return switch (expr) {
            case IrExpr.Var v -> env.getOrDefault(
                    v.name(), new Provenance.Opaque("unbound '" + v.name() + "'"));
            case IrExpr.FieldAccess fa -> {
                Provenance base = provenanceOfPure(fa.base(), env);
                yield base instanceof Provenance.Path p
                        ? new Provenance.Path(p.path().child(fa.fieldName()))
                        : new Provenance.Opaque("field access on " + base.render());
            }
            default -> new Provenance.Opaque("non-path scrutinee");
        };
    }

    private static List<AttributePath> pathsOf(Provenance provenance) {
        return provenance instanceof Provenance.Path p ? List.of(p.path()) : List.of();
    }

    /**
     * Flattens a declared sort into its attribute atoms under {@code root},
     * recursing through registered struct definitions and inline structural
     * sorts (tuples included — their members are the {@code _N} slots).
     * Recursive types terminate via the visited-name set: a back-edge field
     * stays a whole-attribute leaf.
     */
    private static void flatten(
            AttributePath root, IrSort sort,
            Map<String, IrSort.Structural> structs, Set<String> visiting,
            List<AttributePath> out) {
        IrSort.Structural structural = switch (sort) {
            case IrSort.Structural s -> s;
            case IrSort.Named n -> structs.get(n.name());
            case IrSort.Refined r -> structs.get(r.name());
            default -> null;
        };
        if (structural == null || visiting.contains(structural.name())) {
            out.add(root);
            return;
        }
        visiting.add(structural.name());
        for (Map.Entry<String, IrSort> member : structural.members().entrySet()) {
            flatten(root.child(member.getKey()), member.getValue(), structs, visiting, out);
        }
        visiting.remove(structural.name());
    }

    private static String opSymbol(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/"; case MOD -> "%";
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
            case IrSort.Function f -> "Function";
            case IrSort.Trait t -> t.name();
            case IrSort.Union u -> "Union";
            case IrSort.Intersection i -> "Intersection";
        };
    }
}

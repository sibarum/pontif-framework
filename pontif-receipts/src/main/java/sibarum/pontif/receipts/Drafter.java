package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.ir.InferenceContext;
import sibarum.pontif.ir.NarrowingInference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pontif's built-in deterministic receipt-graph builder. Given an
 * {@link IrModule}, walks each function declaration and transcribes the
 * body into a {@link ReceiptGraph}. No reasoning happens here — only
 * transcription. Standalone and stateless; the same module always drafts
 * to the same graph.
 *
 * <p>The drafter is the only component that touches {@link IrModule};
 * downstream consumers (issuers, notary) operate on the receipt-graph,
 * not on IR.
 *
 * <p><b>Current slice (vertical):</b>
 * <ul>
 *   <li>Non-recursive arithmetic bodies → a single unconditional
 *       {@link Branch} carrying one {@link InitialReceipt} of shape
 *       {@code r_0 = body}.
 *   <li>{@code match} bodies → one {@link Branch} per arm. The arm's
 *       guard is its refinement pattern's predicate with {@code @}
 *       bound to the (renamed) scrutinee (e.g., {@code [@<0]} over
 *       {@code match n} becomes guard {@code n_0 < 0}); the arm's body
 *       equation is {@code r_0 = armResult}.
 * </ul>
 * In all cases parameter references are renamed to their call-instance
 * form ({@code n} → {@code n_0}).
 *
 * <p>Calls inside a body equation are <b>hoisted</b> into {@link CallRef}s:
 * each call gets a fresh result var ({@code r_1}, {@code r_2}, …) and is
 * replaced by a reference to it, so the body equation reads in terms of
 * the call results (e.g. {@code n * factorial(n-1)} → CallRef
 * {@code factorial(n_0 - 1) -> r_1} plus equation {@code r_0 = n_0 * r_1}).
 * A {@code CallRef} naming the enclosing function <em>is</em> the
 * back-reference (no-duplicate-edges rule); a {@code CallRef} naming
 * another function is an external call. The call result var's sort is
 * the callee's return narrowing (declared for the recursive case,
 * {@link StaticDispatch}-resolved for overloaded cross-function calls
 * via {@link NarrowingInference}), so the back-reference carries the
 * inductive hypothesis automatically.
 *
 * <p>See {@code docs/receipt-graph.md} for the design and worked example.
 */
public final class Drafter {

    private Drafter() {}

    /** Drafts a receipt-graph for every {@link IrStmt.FunctionDecl} in the module. */
    public static ReceiptGraph draft(IrModule module) throws CompileException {
        // Module-wide inference context: overloads + declared returns + struct
        // defs, used to resolve each hoisted call's return narrowing.
        InferenceContext baseCtx = InferenceContext.fromModule(module);
        // One node per function declaration, in source order — overloads
        // (same name, distinct param sorts) each get their own node.
        List<Node> roots = new ArrayList<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                roots.add(draftFunction(fd, baseCtx));
            }
        }
        return new ReceiptGraph(roots);
    }

    /**
     * Drafts a single function as call instance 0. Each parameter {@code n}
     * becomes the symbolic variable {@code n_0}; the result variable is
     * {@code r_0}. Body references to params are renamed accordingly so the
     * receipt reads in terms of the call-instance variables. Sub-call result
     * vars ({@code r_1}, {@code r_2}, …) are allocated by a per-function
     * counter shared across all branches.
     */
    private static Node draftFunction(IrStmt.FunctionDecl fd, InferenceContext baseCtx)
            throws CompileException {
        int callIndex = 0;

        // A declared top-level let lowers to a 0-arg function whose body is
        // wrapped in a claim-bearing LetIn (`LetIn(n, value, Var(n))`) so the
        // construction gate can judge the claim. The wrapper is dataflow-
        // transparent — the claim is a check, identity on the value — so
        // drafting sees through it; otherwise the body equation would route
        // through a binding and the return obligation could never discharge.
        IrExpr fnBody = fd.body();
        if (fnBody instanceof IrExpr.LetIn l
                && l.body() instanceof IrExpr.Var v
                && v.name().equals(l.name())) {
            fnBody = l.value();
        }

        // Build params + a rename map (body's IrExpr.Var("n") → SymExpr.Var("n_0")).
        List<Param> params = new ArrayList<>(fd.params().size());
        Map<String, SymExpr> renameBindings = new HashMap<>();
        InferenceContext ctx = baseCtx;
        for (IrParam p : fd.params()) {
            String varName = p.name() + "_" + callIndex;
            params.add(new Param(varName, IrCompiler.compileSort(p.sort())));
            renameBindings.put(p.name(), SymExpr.var(varName));
            // Seed the param under its ORIGINAL name so call-arg narrowing
            // inference (which sees the un-renamed body) can resolve it.
            ctx = ctx.withVar(p.name(), p.sort());
        }

        // The return refinement may reference parameters (e.g. a spec-only
        // [Int:@==y+1] synthesizes body y+1). Rename those param refs to
        // call-instance form (y → y_0) so the obligation reads in the same
        // variables as the body equation — otherwise r_0 == y+1 (raw) can't
        // be discharged against the body r_0 == y_0+1 (renamed).
        //
        // A top-level let's return sort is DEFINITIONAL, not declared: the
        // parser inferred it FROM the body (`let five = zero + 5` carries
        // [Int:@==zero+5] — a receipt of inference, not a claim), so it
        // mints no proof obligation. The binding's actual declared claim
        // travels in the claim wrapper, judged by the construction gate.
        Sort resultSort = IrCompiler.compileSort(fd.returnSort());
        if (fd.topLevelLet() && resultSort.isRefined()) {
            resultSort = Sort.of(resultSort.name());
        }
        Var resultVar = new Var(
                "r_" + callIndex,
                renameSortPredicate(resultSort, renameBindings));

        // Sub-call result vars start at r_1 (r_0 is the function result).
        int[] callCounter = {1};

        List<Branch> branches = fnBody instanceof IrExpr.Match match
                ? draftMatchBranches(match, resultVar, renameBindings, ctx, callCounter)
                : List.of(draftUnconditionalBranch(fnBody, resultVar, renameBindings, ctx, callCounter));

        return new Node(fd.name(), params, resultVar, branches);
    }

    /**
     * Renames parameter references inside a refined sort's predicate to
     * their call-instance form ({@code y} → {@code y_0}), leaving the base
     * and non-refined sorts untouched. Applied to a function's return sort
     * so its obligation reads in the same variables as the body.
     */
    private static Sort renameSortPredicate(Sort sort, Map<String, SymExpr> renameBindings) {
        if (sort.isRefined()) {
            return Sort.refined(sort.name(), Substitute.apply(sort.predicate(), renameBindings));
        }
        return sort;
    }

    /**
     * Drafts a single unconditional branch from a non-{@code match} body:
     * calls hoisted into {@link CallRef}s, one body equation
     * {@code r_0 = body} with param refs renamed and calls replaced by
     * their result vars.
     */
    private static Branch draftUnconditionalBranch(
            IrExpr body, Var resultVar, Map<String, SymExpr> renameBindings,
            InferenceContext ctx, int[] callCounter)
            throws CompileException {
        List<CallRef> calls = new ArrayList<>();
        SymExpr rhs = transcribeBody(body, renameBindings, ctx, callCounter, calls);
        InitialReceipt bodyReceipt = new InitialReceipt(
                SymExpr.cmp(SymExpr.var(resultVar.name()), SymExpr.CmpOp.EQ, rhs));
        return new Branch(Optional.empty(), List.of(bodyReceipt), calls);
    }

    /**
     * Drafts one {@link Branch} per match arm. The scrutinee is lifted +
     * renamed once; each arm's refinement-pattern predicate becomes the
     * branch guard (with {@code @} bound to the scrutinee). The arm's
     * result is transcribed with calls hoisted into the branch's
     * {@link CallRef}s, leaving a body equation {@code r_0 = armResult}.
     *
     * <p>Arms whose pattern isn't an {@link IrSort.Refined} (e.g.
     * structural patterns) currently produce a guardless branch — struct
     * match drafting is a later slice.
     */
    private static List<Branch> draftMatchBranches(
            IrExpr.Match match, Var resultVar, Map<String, SymExpr> renameBindings,
            InferenceContext ctx, int[] callCounter)
            throws CompileException {
        SymExpr scrutinee = Substitute.apply(
                IrCompiler.compileSymExpr(match.scrutinee()), renameBindings);

        List<Branch> branches = new ArrayList<>(match.branches().size());
        for (IrExpr.MatchBranch arm : match.branches()) {
            Optional<SymExpr> guard = Optional.empty();
            if (arm.pattern() instanceof IrSort.Refined refined) {
                SymExpr predicate = Substitute.apply(
                        IrCompiler.compileSymExpr(refined.predicate()), renameBindings);
                // Bind @ (the refinement subject) to the scrutinee.
                guard = Optional.of(Substitute.applySelf(predicate, scrutinee));
            }
            List<CallRef> calls = new ArrayList<>();
            SymExpr rhs = transcribeBody(arm.result(), renameBindings, ctx, callCounter, calls);
            InitialReceipt bodyReceipt = new InitialReceipt(
                    SymExpr.cmp(SymExpr.var(resultVar.name()), SymExpr.CmpOp.EQ, rhs));
            branches.add(new Branch(guard, List.of(bodyReceipt), calls));
        }
        return branches;
    }

    /**
     * Transcribes a body expression into the body-equation RHS: hoists
     * every call into {@code calls} (replacing it with a fresh result var)
     * then lifts the call-free expression to a {@link SymExpr} with param
     * refs renamed.
     */
    private static SymExpr transcribeBody(
            IrExpr body, Map<String, SymExpr> renameBindings,
            InferenceContext ctx, int[] callCounter, List<CallRef> calls)
            throws CompileException {
        IrExpr hoisted = hoistCalls(body, renameBindings, ctx, callCounter, calls);
        return Substitute.apply(IrCompiler.compileSymExpr(hoisted), renameBindings);
    }

    /**
     * Walks {@code expr}, replacing every {@link IrExpr.Call} with a fresh
     * result-var reference and appending a {@link CallRef} to {@code calls}.
     * Post-order: a call's arguments are hoisted before the call itself, so
     * nested calls ({@code f(g(x))}) get earlier result-var numbers.
     *
     * <p>The {@code CallRef}'s argument bindings are the (call-free) args
     * lifted to {@link SymExpr} and renamed; its result var's sort is the
     * callee's return narrowing per {@link #resolveCallReturnSort}.
     */
    private static IrExpr hoistCalls(
            IrExpr expr, Map<String, SymExpr> renameBindings,
            InferenceContext ctx, int[] callCounter, List<CallRef> calls)
            throws CompileException {
        return switch (expr) {
            case IrExpr.Call c -> {
                List<IrExpr> hoistedArgs = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) {
                    hoistedArgs.add(hoistCalls(a, renameBindings, ctx, callCounter, calls));
                }
                String varName = "r_" + (callCounter[0]++);
                Sort returnSort = resolveCallReturnSort(c, ctx);
                List<SymExpr> argBindings = new ArrayList<>(hoistedArgs.size());
                for (IrExpr a : hoistedArgs) {
                    argBindings.add(Substitute.apply(IrCompiler.compileSymExpr(a), renameBindings));
                }
                calls.add(new CallRef(c.functionName(), argBindings, new Var(varName, returnSort)));
                yield IrExpr.var(varName);
            }
            // Division/remainder are outside the linear kernel the body
            // equation feeds — hoist the operation like a call (under
            // dispatch unification it IS one: an operator call). The result
            // var's sort is UNREFINED, so the receipts simply claim nothing
            // about the divided value and no inductive hypothesis can attach;
            // the rest of the body keeps its receipts instead of the whole
            // function refusing to draft. Deliberately NOT resolved through
            // resolveCallReturnSort: name-based inference on "/" could pick a
            // user overload (e.g. a struct's own division) and fabricate a
            // wrong narrowing — the base sort comes from the operands, per
            // the Int-promotes-to-Decimal ruling.
            case IrExpr.BinOp op when op.op() == IrExpr.Op.DIV
                    || op.op() == IrExpr.Op.MOD -> {
                IrExpr left = hoistCalls(op.left(), renameBindings, ctx, callCounter, calls);
                IrExpr right = hoistCalls(op.right(), renameBindings, ctx, callCounter, calls);
                String varName = "r_" + (callCounter[0]++);
                List<SymExpr> argBindings = List.of(
                        Substitute.apply(IrCompiler.compileSymExpr(left), renameBindings),
                        Substitute.apply(IrCompiler.compileSymExpr(right), renameBindings));
                String opName = op.op() == IrExpr.Op.DIV ? "/" : "%";
                Sort base = Sort.of(involvesDecimal(left, ctx) || involvesDecimal(right, ctx)
                        ? "Decimal" : "Int");
                calls.add(new CallRef(opName, argBindings, new Var(varName, base)));
                yield IrExpr.var(varName);
            }
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(),
                    hoistCalls(op.left(), renameBindings, ctx, callCounter, calls),
                    hoistCalls(op.right(), renameBindings, ctx, callCounter, calls),
                    op.origin());
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    hoistCalls(fa.base(), renameBindings, ctx, callCounter, calls),
                    fa.fieldName(), fa.origin());
            case IrExpr.Record r -> {
                java.util.Map<String, IrExpr> members = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> e : r.members().entrySet()) {
                    members.put(e.getKey(),
                            hoistCalls(e.getValue(), renameBindings, ctx, callCounter, calls));
                }
                yield new IrExpr.Record(r.typeName(), members, r.origin());
            }
            case IrExpr.LetIn l -> new IrExpr.LetIn(
                    l.name(), l.declaredSort(),
                    hoistCalls(l.value(), renameBindings, ctx, callCounter, calls),
                    hoistCalls(l.body(), renameBindings, ctx, callCounter, calls),
                    l.origin(), l.claim());
            // Leaves and forms not yet call-hoisted (Apply/Lambda/Match nested
            // in a body equation are rare; transcribed as-is for now).
            default -> expr;
        };
    }

    /**
     * Whether an expression's value is Decimal-sorted, for the hoisted
     * division's result-var base: any Decimal operand promotes (the
     * Int-promotes-to-Decimal ruling). Conservative — unknown shapes read
     * as Int, matching the existing bare-Int call fallback.
     */
    private static boolean involvesDecimal(IrExpr expr, InferenceContext ctx) {
        return switch (expr) {
            case IrExpr.Dec d -> true;
            case IrExpr.Var v -> {
                IrSort sort = ctx.typeEnv().get(v.name());
                String base = switch (sort) {
                    case IrSort.Named n -> n.name();
                    case IrSort.Refined r -> r.name();
                    case null, default -> null;
                };
                yield "Decimal".equals(base);
            }
            case IrExpr.BinOp op ->
                    involvesDecimal(op.left(), ctx) || involvesDecimal(op.right(), ctx);
            default -> false;
        };
    }

    /**
     * The {@link Sort} for a hoisted call's result var: the callee's return
     * narrowing, resolved through {@link NarrowingInference} (which picks the
     * dispatched overload for overloaded callees, falling back to the
     * declared return). When the declared return is unrefined (bare
     * {@code Int}), falls back to inferring the callee's body via
     * {@link NarrowingInference#inferCallReturnFromBody} — this turns an
     * arithmetic body like {@code x + 5} over a refined param into a
     * refined CallRef result sort, which then carries an inductive
     * hypothesis into the issuer's path facts. Falls back to bare
     * {@code Int} when neither path yields a narrowing.
     */
    private static Sort resolveCallReturnSort(IrExpr.Call call, InferenceContext ctx)
            throws CompileException {
        IrSort narrowing = NarrowingInference.infer(call, ctx);
        if (!(narrowing instanceof IrSort.Refined)) {
            IrSort.Refined fromBody = NarrowingInference.inferCallReturnFromBody(call, ctx);
            if (fromBody != null) narrowing = fromBody;
        }
        return narrowing != null ? IrCompiler.compileSort(narrowing) : Sort.of("Int");
    }
}

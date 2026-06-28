package sibarum.pontif.ir;

import sibarum.pontif.ast.record.RecordValue;
import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.DispatchResult;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.Refinements;
import sibarum.pontif.core.symbolic.RuntimeCheckException;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IrInterpreter {

    private final Simplifier simplifier;

    public IrInterpreter(Simplifier simplifier) {
        this.simplifier = simplifier;
    }

    public Object eval(CompiledModule module) {
        // The Inquisition: every top-level let is force-evaluated before
        // main, declaration order — its claims (binding claims, construction
        // checks) are notarized whether or not anything references it. Pure
        // language: forcing is observationally invisible except where a
        // check fails or the value diverges — exactly the lies the lazy
        // ruling let an unreferenced binding tell. Genuine 0-arg functions
        // are NOT forced; a diverging body is legitimate until applied.
        for (String let : module.topLevelLets()) {
            eval(new IrExpr.Call(let, List.of(), Origin.NONE), Environment.empty(), module);
        }
        return eval(module.main(), Environment.empty(), module);
    }

    /**
     * The simplifier carrying this module's nominal-struct registry, so
     * {@link Refinements} can resolve a by-reference struct sort to its shape
     * when checking a value or dispatching. Without it a struct param sort is a
     * bare name treated as unconstrained — accepting any value.
     */
    private Simplifier checker(CompiledModule module) {
        return simplifier.withRegistry(module.structRegistry());
    }

    public Object eval(IrExpr expr, Environment env, CompiledModule module) {
        return switch (expr) {
            case IrExpr.Lit l -> l.value();
            case IrExpr.Dec d -> d.value();
            case IrExpr.Chr c -> new sibarum.pontif.core.types.CharValue(c.codePoint());
            case IrExpr.Str s -> new sibarum.pontif.core.types.StringValue(s.value());
            // A metareference evaluates to a first-class dispatch — built
            // from statics only; invocation reruns registry dispatch.
            case IrExpr.DispatchRef d -> {
                List<sibarum.pontif.core.types.Sort> keys = new ArrayList<>(d.keySorts().size());
                try {
                    for (IrSort k : d.keySorts()) keys.add(IrCompiler.compileSort(k));
                } catch (CompileException ce) {
                    throw new RuntimeCheckException(
                            "Metareference key sort failed to compile: " + ce.getMessage(),
                            d.origin());
                }
                yield new sibarum.pontif.core.types.DispatchValue(d.functionName(), keys);
            }
            case IrExpr.Bool b -> b.value();
            case IrExpr.Var v -> env.lookup(v.name());
            case IrExpr.SelfRef s -> throw new IllegalStateException(
                    "Self has no runtime value — it is a typing-context placeholder");
            case IrExpr.BinOp op -> evalBinOp(op, env, module);
            case IrExpr.LetIn l -> {
                Object value = eval(l.value(), env, module);
                // Binding claim kept by ConstructionGate (UNKNOWN verdict):
                // the declared sort whose fit was undecidable at compile time
                // is judged here, where the value is concrete. Fail-closed,
                // mirroring construction checks.
                if (l.claim() != null) {
                    Sort claim = module.sortFor(l.claim());
                    ProofResult pr = Refinements.satisfies(toSymExpr(value), claim, checker(module));
                    if (!(pr instanceof ProofResult.Passed)) {
                        throw new RuntimeCheckException(
                                "Binding claim violated: '" + l.name() + "' = " + value
                                        + " does not satisfy the declared sort " + claim,
                                l.origin());
                    }
                }
                Environment extended = env.extend(l.name(), value);
                yield eval(l.body(), extended, module);
            }
            case IrExpr.Call c -> evalCall(c, env, module);
            case IrExpr.Lambda lambda -> new Closure(lambda, env);
            case IrExpr.Apply apply -> evalApply(apply, env, module);
            case IrExpr.Match m -> evalMatch(m, env, module);
            case IrExpr.Record r -> {
                Map<String, Object> members = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> e : r.members().entrySet()) {
                    members.put(e.getKey(), eval(e.getValue(), env, module));
                }
                // Construction-claim checks stamped by ConstructionGate: the
                // members whose fit was undecidable at compile time are judged
                // here, where the value is concrete. Fail-closed: anything
                // short of Passed rejects the construction.
                for (Map.Entry<String, IrSort> check : r.runtimeChecks().entrySet()) {
                    Object v = members.get(check.getKey());
                    Sort claim = module.sortFor(check.getValue());
                    ProofResult pr = Refinements.satisfies(toSymExpr(v), claim, checker(module));
                    if (!(pr instanceof ProofResult.Passed)) {
                        throw new RuntimeCheckException(
                                "Construction claim violated: '" + r.typeName() + "."
                                        + check.getKey() + "' = " + v
                                        + " does not satisfy the declared sort " + claim,
                                r.origin());
                    }
                }
                // A native constructor builds its carrier scalar (the bijection
                // contract's construct half), not a RecordValue.
                NativeConstructors.Entry nativeCons = NativeConstructors.get(r.typeName());
                if (nativeCons != null) {
                    yield nativeCons.construct().apply(
                            members.values().toArray(), r.origin());
                }
                yield new RecordValue(r.typeName(), members);
            }
            case IrExpr.FieldAccess fa -> {
                Object baseValue = eval(fa.base(), env, module);
                // Decimal anatomy projection — total; unscaled is the
                // canonical scale-0 Decimal (never an Int: one-way wall).
                if (baseValue instanceof BigDecimal dec) {
                    if (!sibarum.pontif.core.Decimals.isAnatomyField(fa.fieldName())) {
                        throw new RuntimeCheckException(
                                "Decimal has no field '." + fa.fieldName()
                                        + "' — its anatomy is (unscaled, scale)",
                                fa.origin());
                    }
                    yield "scale".equals(fa.fieldName())
                            ? (Object) sibarum.pontif.core.Decimals.projectScale(dec)
                            : sibarum.pontif.core.Decimals.projectUnscaled(dec);
                }
                if (!(baseValue instanceof RecordValue rec)) {
                    throw new RuntimeCheckException(
                            "Field access '." + fa.fieldName() + "' requires a record value, got "
                                    + (baseValue == null ? "null" : baseValue.getClass().getSimpleName())
                                    + ": " + baseValue,
                            fa.origin());
                }
                if (rec.members().containsKey(fa.fieldName())) {
                    yield rec.get(fa.fieldName(), fa.origin());
                }
                // Trait-view attribute access: the value carries no such stored
                // field, so resolve a computed projection — a `Type.attr(this)`
                // producer registered by an `assign trait` block. This is what
                // lets a struct be viewed through a trait that adds attributes.
                Object projected = tryAttributeProducer(rec, fa.fieldName(), module);
                if (projected != NO_ATTRIBUTE) {
                    yield projected;
                }
                yield rec.get(fa.fieldName(), fa.origin());  // re-throws the "no field" error
            }
            case IrExpr.MethodCall mc -> throw MethodResolver.unresolved(mc, "IrInterpreter");
            case IrExpr.Iterate it -> evalIterate(it, env, module);
            case IrExpr.Emit emit -> evalEmit(emit, env, module);
            case IrExpr.Cast cast -> evalCast(cast, env, module);
        };
    }

    /**
     * Evaluates an {@code emit EVENT  BODY} statement (docs/events.md, slice 1b):
     * evaluate the event, route it <b>by its type name</b> to the matching native
     * conduit ({@link NativeFunctions} — the builtin {@code StdOut}/{@code StdErr}
     * output streams), discard the (write-only) result, then continue with the body.
     * An event with no registered conduit fails closed (stateful/user conduits are
     * later slices).
     */
    private Object evalEmit(IrExpr.Emit emit, Environment env, CompiledModule module) {
        Object event = eval(emit.event(), env, module);
        if (!(event instanceof RecordValue rec)) {
            throw new RuntimeCheckException(
                    "emit expects an event value (a struct), got "
                            + (event == null ? "null" : event.getClass().getSimpleName()),
                    emit.origin());
        }
        if (rec.typeName() == null) {
            throw new RuntimeCheckException(
                    "emit expects a named event struct, got an anonymous aggregate", emit.origin());
        }
        NativeFunctions.Effect conduit = NativeFunctions.get(rec.typeName());
        if (conduit == null) {
            throw new RuntimeCheckException(
                    "No conduit for event type '" + rec.typeName() + "' — slice 1b routes only "
                            + "the builtin StdOut/StdErr output conduits (docs/events.md)",
                    emit.origin());
        }
        conduit.apply(rec, emit.origin());
        return eval(emit.body(), env, module);
    }

    /**
     * Evaluates the iteration construct (docs/iteration.md) as a pure fold over a
     * source. Slice 1: the source is an {@code Element/Leaf} chain; output kinds
     * STREAM (sealed to an {@code Element/Leaf} chain) and ACCUMULATOR (sealed to
     * its final revision) are supported; KEYED/REWRITE throw. A frame binds the
     * current element and each accumulator's PRIOR revision (the read side of its
     * pair, §2.5); stream outputs are write-only (not bound). No conservation
     * checking yet (§10 REVISIT) — empty write set = the element is simply not
     * placed.
     */
    private Object evalIterate(IrExpr.Iterate it, Environment env, CompiledModule module) {
        java.util.Map<String, java.util.List<Object>> streams = new LinkedHashMap<>();
        java.util.Map<String, Object> accumulators = new LinkedHashMap<>();
        java.util.Map<String, IrExpr.OutputKind> kinds = new LinkedHashMap<>();
        for (IrExpr.OutputSpec os : it.outputs()) {
            kinds.put(os.name(), os.kind());
            switch (os.kind()) {
                case STREAM -> streams.put(os.name(), new ArrayList<>());
                case ACCUMULATOR -> accumulators.put(os.name(),
                        os.init() == null ? null : eval(os.init(), env, module));
                default -> throw new RuntimeCheckException(
                        "Iterate: output kind " + os.kind() + " not yet implemented (slice 1)",
                        it.origin());
            }
        }

        // A stream is a positional record (a tuple literal `{1,2,3}`); iterate its
        // members in order. (Element/Leaf cons-chains are retired here — trees use
        // recursion, streams are native; docs/iteration.md §7.1, James 2026-06-15.)
        // With co-sources (zip / fan-in, docs/stream-war.md §3) the sources are walked
        // in lockstep, stopping at the shortest, and `element` binds to a tuple of the
        // i-th value from each.
        //
        // DEMAND-DRIVEN path (docs/events.md, "The three stages"): a single source that
        // evaluates to a LiveSource is pulled ONE element at a time — the lazy iterator.
        // Each pulled element runs the arms (which may `emit`) before the next is pulled,
        // so effects are interactive, and the source's seal (EOF) ends the loop by
        // construction. The eager path below pre-materialises a finite tuple instead.
        if (it.coSources().isEmpty() && eval(it.source(), env, module) instanceof LiveSource live) {
            java.util.Optional<Object> next;
            while ((next = live.pull()).isPresent()) {
                if (iterateStep(it, next.get(), env, module, kinds, streams, accumulators)) {
                    break;  // STOP disposition
                }
            }
            return sealIterate(it, streams, accumulators);
        }

        List<IrExpr> sourceExprs = new ArrayList<>();
        sourceExprs.add(it.source());
        sourceExprs.addAll(it.coSources());
        List<List<Object>> columns = new ArrayList<>(sourceExprs.size());
        int steps = Integer.MAX_VALUE;
        for (IrExpr se : sourceExprs) {
            Object sv = eval(se, env, module);
            if (!(sv instanceof RecordValue rec)) {
                throw new RuntimeCheckException(
                        "Iterate: source must be a stream (a positional record / tuple), got "
                                + (sv == null ? "null" : sv.getClass().getSimpleName()),
                        it.origin());
            }
            List<Object> col = new ArrayList<>(rec.members().values());
            columns.add(col);
            steps = Math.min(steps, col.size());
        }
        boolean zip = !it.coSources().isEmpty();
        for (int idx = 0; idx < steps; idx++) {
            Object element;
            if (zip) {
                java.util.Map<String, Object> tuple = new LinkedHashMap<>();
                for (int s = 0; s < columns.size(); s++) tuple.put("_" + s, columns.get(s).get(idx));
                element = new RecordValue("_tuple", tuple);
            } else {
                element = columns.get(0).get(idx);
            }
            if (iterateStep(it, element, env, module, kinds, streams, accumulators)) {
                break;  // stop disposition — seal what's emitted, end the iteration
            }
        }
        return sealIterate(it, streams, accumulators);
    }

    /**
     * Processes one element through the iterate arms — the body shared by the eager
     * (materialised) and demand-driven (LiveSource) drives. Binds {@code element} (and
     * each accumulator's prior revision, the read side), matches the arms top-to-bottom,
     * and routes the matched arm's writes. Returns {@code true} for the STOP disposition
     * (docs/stream-war.md §3, takeWhile) — the triggering element is not emitted and the
     * caller seals what's accumulated so far. Throws if no arm matches.
     */
    private boolean iterateStep(IrExpr.Iterate it, Object element, Environment env,
            CompiledModule module, Map<String, IrExpr.OutputKind> kinds,
            Map<String, List<Object>> streams, Map<String, Object> accumulators) {
        Environment frame = env.extend(it.element(), element);
        for (java.util.Map.Entry<String, Object> a : accumulators.entrySet()) {
            frame = frame.extend(a.getKey(), a.getValue());  // prior revision (read side)
        }
        SymExpr sym = toSymExpr(element);
        for (IrExpr.Arm arm : it.arms()) {
            Sort pat = module.sortFor(arm.pattern());
            if (Refinements.satisfies(sym, pat, checker(module)) instanceof ProofResult.Passed) {
                for (IrExpr.Write w : arm.writes()) {
                    if (w.output().equals(IrExpr.Write.STOP)) {
                        return true;  // halt: triggering element not emitted
                    }
                    if (w.output().equals(IrExpr.Write.FAN)) {
                        Object tup = eval(w.value(), frame, module);
                        if (!(tup instanceof RecordValue rv)) {
                            throw new RuntimeCheckException(
                                    "Iterate: a multi-channel fragment must return a tuple, got "
                                            + (tup == null ? "null" : tup.getClass().getSimpleName()),
                                    it.origin());
                        }
                        for (IrExpr.OutputSpec os : it.outputs()) {
                            routeWrite(os.name(), rv.members().get(os.name()), kinds,
                                    streams, accumulators, it.origin());
                        }
                        continue;
                    }
                    IrExpr.OutputKind k = kinds.get(w.output());
                    if (k == null) throw new RuntimeCheckException(
                            "Iterate: write to unknown output '" + w.output() + "'", it.origin());
                    routeWrite(w.output(), eval(w.value(), frame, module), kinds,
                            streams, accumulators, it.origin());
                }
                return false;
            }
        }
        throw new RuntimeCheckException("Iterate: no arm matched element " + element, it.origin());
    }

    /**
     * Seals the iterate outputs: a single output directly, else a record keyed by output
     * name. Positional names ({@code _0.._n} — the multi-channel fragment shape) seal to a
     * {@code _tuple} so the result destructures with {@code let {a, b} = …}; named outputs
     * (the {@code iter()} disposition form) stay a plain keyed record.
     */
    private static Object sealIterate(IrExpr.Iterate it, Map<String, List<Object>> streams,
            Map<String, Object> accumulators) {
        java.util.Map<String, Object> result = new LinkedHashMap<>();
        for (IrExpr.OutputSpec os : it.outputs()) {
            result.put(os.name(), os.kind() == IrExpr.OutputKind.STREAM
                    ? sealStream(streams.get(os.name()))
                    : accumulators.get(os.name()));
        }
        if (result.size() == 1) return result.values().iterator().next();
        boolean positional = result.keySet().stream().allMatch(IrInterpreter::isPositionalKey);
        return positional ? new RecordValue("_tuple", result) : new RecordValue(result);
    }

    /** True for a tuple positional key — {@code _0}, {@code _1}, … (underscore + digits). */
    private static boolean isPositionalKey(String name) {
        if (name.length() < 2 || name.charAt(0) != '_') return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Whether {@code v} is the {@code Nothing} omission value (pontif.core) — a
     * zero-field record of that nominal. The check is by type name: {@code Nothing}
     * is the one universal omission value (docs/stream-war.md §3), so any value
     * carrying that nominal omits at a stream channel.
     */
    private static boolean isNothing(Object v) {
        if (!(v instanceof RecordValue rv) || rv.typeName() == null) return false;
        String n = rv.typeName();
        // Cross-module construction qualifies the nominal ("pontif.core/Nothing");
        // a same-module use is bare ("Nothing"). Match either.
        return n.equals("Nothing") || n.endsWith("/Nothing");
    }

    /**
     * Routes one written value to its output by kind: a STREAM appends (dropping the
     * {@code Nothing} omission value — the lossy filter shape, docs/stream-war.md §3);
     * an ACCUMULATOR threads the next revision (the prior was read via the frame).
     */
    private void routeWrite(
            String output, Object v, java.util.Map<String, IrExpr.OutputKind> kinds,
            java.util.Map<String, java.util.List<Object>> streams,
            java.util.Map<String, Object> accumulators, sibarum.pontif.core.Origin origin) {
        IrExpr.OutputKind k = kinds.get(output);
        if (k == null) throw new RuntimeCheckException(
                "Iterate: write to unknown output '" + output + "'", origin);
        switch (k) {
            case STREAM -> { if (!isNothing(v)) streams.get(output).add(v); }
            case ACCUMULATOR -> accumulators.put(output, v);
            default -> throw new RuntimeCheckException(
                    "Iterate: write to " + k + " not yet implemented", origin);
        }
    }

    /** Concatenates two positional streams (tuples) into one, renumbering keys _0.._n. */
    private static Object concatTuples(RecordValue a, RecordValue b) {
        Map<String, Object> m = new LinkedHashMap<>();
        int i = 0;
        for (Object v : a.members().values()) m.put("_" + i++, v);
        for (Object v : b.members().values()) m.put("_" + i++, v);
        return new RecordValue("_tuple", m);
    }

    /** Seals an accumulated stream into a positional record (tuple) — the native sequence value. */
    private static Object sealStream(java.util.List<Object> elems) {
        java.util.Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < elems.size(); i++) m.put("_" + i, elems.get(i));
        return new RecordValue("_tuple", m);
    }

    /**
     * Runtime backstop on the unfold driver: a generator whose domain refinement
     * never goes false would loop forever. Until the static halting proof (the bound
     * engine, docs/stream-war.md §7.9) gates non-terminating generators at compile
     * time, the driver caps the step count and fails loudly rather than hanging. This
     * is a backstop, not the proof — a well-typed generator never reaches it.
     */
    private static final int UNFOLD_STEP_CAP = 1_000_000;

    /**
     * Whether {@code returnSort} is a <em>generator codomain</em> (docs/stream-war.md
     * §7.9): a tuple carrying at least one {@code Stream[T]} channel <b>and</b> at
     * least one bare (accumulator) channel. That mix is the unfold signal — a stream
     * <em>output</em> threaded by accumulator state, with no {@code &} input. A pure
     * stream codomain (a plain map fragment) or an all-stream tuple (fork) is not a
     * generator; nor is a plain {@code T} / tuple-of-{@code T} return.
     */
    static boolean isGeneratorCodomain(IrSort returnSort) {
        if (!(returnSort instanceof IrSort.Structural st)
                || !TUPLE_SENTINEL.equals(st.name())) {
            return false;
        }
        boolean hasStream = false, hasAccumulator = false;
        for (IrSort member : st.members().values()) {
            if (isStreamChannel(member)) hasStream = true; else hasAccumulator = true;
        }
        return hasStream && hasAccumulator;
    }

    private static final String TUPLE_SENTINEL = "_tuple";

    /**
     * Whether a codomain channel sort is a stream channel ({@code Stream[T]}). The
     * base name is bare ({@code "Stream"}) in a single file but qualified
     * ({@code "pontif.core/Stream"}) once the linker resolves the imported trait —
     * match either, the same bare-or-qualified rule {@link #isNothing} uses.
     */
    private static boolean isStreamChannel(IrSort s) {
        String n = sortBaseName(s);
        return n != null && (n.equals("Stream") || n.endsWith("/Stream"));
    }

    /** The base (head) name of a sort, or {@code null} for the nameless composites. */
    private static String sortBaseName(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            case IrSort.Trait t -> t.name();
            case IrSort.Method f -> null;
            case IrSort.Dispatch d -> "Dispatch";
            case IrSort.Union u -> null;
            case IrSort.Intersection i -> null;
        };
    }

    /**
     * The unfold / generator driver (docs/stream-war.md §7.9, slice 2f) — the
     * <em>dual of fold</em> and a new execution driver, NOT sugar over the
     * source-driven {@link IrExpr.Iterate}. There is no {@code &} source; the fragment
     * has accumulator inputs (seeded by the call args) and a tuple codomain mixing
     * {@code Stream[T]} output channel(s) with bare accumulator channel(s). Each step
     * binds the params to the current accumulator state, emits the stream channels, and
     * threads the accumulator channels to the next step. <b>The domain refinement is
     * the base case</b> (the universal per-channel guard, §3): the unfold halts exactly
     * when the current state would make a param ill-typed — the same guard
     * {@code filter}/{@code takeWhile} apply to source elements, here on the threaded
     * accumulators with the <em>stop</em> disposition.
     */
    Object driveGenerator(IrExpr.Lambda lambda, List<Object> seeds,
                          Environment captured, CompiledModule module) {
        List<IrParam> params = lambda.params();
        IrSort.Structural codomain = (IrSort.Structural) lambda.returnSort();
        List<IrSort> channels = new ArrayList<>(codomain.members().values());
        boolean[] isStream = new boolean[channels.size()];
        int accCount = 0;
        for (int c = 0; c < channels.size(); c++) {
            isStream[c] = isStreamChannel(channels.get(c));
            if (!isStream[c]) accCount++;
        }
        if (accCount != params.size()) {
            throw new RuntimeCheckException(
                    "Generator: the number of accumulator channels (" + accCount
                            + ") must equal the number of parameters (" + params.size()
                            + ") — each accumulator channel threads one parameter; "
                            + "docs/stream-war.md §7.9", lambda.origin());
        }

        Object[] state = seeds.toArray();
        Map<Integer, java.util.List<Object>> streamBufs = new LinkedHashMap<>();
        for (int c = 0; c < channels.size(); c++) {
            if (isStream[c]) streamBufs.put(c, new ArrayList<>());
        }
        Simplifier checker = checker(module);

        int step = 0;
        while (guardHolds(params, state, module, checker)) {
            if (step++ > UNFOLD_STEP_CAP) {
                throw new RuntimeCheckException(
                        "Generator did not halt within " + UNFOLD_STEP_CAP + " steps — its "
                                + "domain refinement never went false (the static halting proof "
                                + "is pending; docs/stream-war.md §7.9)", lambda.origin());
            }
            Environment frame = captured;
            for (int i = 0; i < params.size(); i++) {
                frame = frame.extend(params.get(i).name(), state[i]);
            }
            Object res = eval(lambda.body(), frame, module);
            if (!(res instanceof RecordValue rv)) {
                throw new RuntimeCheckException(
                        "Generator body must return a tuple matching its codomain, got "
                                + (res == null ? "null" : res.getClass().getSimpleName()),
                        lambda.origin());
            }
            Object[] nextState = new Object[params.size()];
            int accIdx = 0;
            for (int c = 0; c < channels.size(); c++) {
                Object cv = rv.members().get("_" + c);
                if (isStream[c]) {
                    if (!isNothing(cv)) streamBufs.get(c).add(cv);
                } else {
                    nextState[accIdx++] = cv;
                }
            }
            state = nextState;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        int accIdx = 0;
        for (int c = 0; c < channels.size(); c++) {
            result.put("_" + c, isStream[c]
                    ? sealStream(streamBufs.get(c))
                    : state[accIdx++]);
        }
        return new RecordValue(TUPLE_SENTINEL, result);
    }

    /**
     * The per-step domain guard: do all parameter refinements hold at the current
     * accumulator state? Each param's refinement predicate gets {@code @} bound to
     * its own current value (via {@link Substitute#applySelf}) and every cross-
     * reference to a sibling parameter ({@code to:[Int:@>=from]}) substituted to that
     * sibling's current value, then simplified — the guard holds iff every predicate
     * reduces to true. The first state that fails ends the unfold (the base case).
     */
    private boolean guardHolds(List<IrParam> params, Object[] state,
                               CompiledModule module, Simplifier checker) {
        Map<String, SymExpr> binds = new LinkedHashMap<>();
        for (int i = 0; i < params.size(); i++) {
            binds.put(params.get(i).name(), toSymExpr(state[i]));
        }
        for (int i = 0; i < params.size(); i++) {
            Sort coreSort = module.sortFor(params.get(i).sort());
            if (!coreSort.isRefined()) continue;
            SymExpr pred = Substitute.applySelf(coreSort.predicate(), toSymExpr(state[i]));
            pred = Substitute.apply(pred, binds);
            if (!(checker.simplify(pred) instanceof SymExpr.Bool b && b.value())) {
                return false;
            }
        }
        return true;
    }

    private Object evalMatch(IrExpr.Match match, Environment env, CompiledModule module) {
        Object value = eval(match.scrutinee(), env, module);
        SymExpr symbolicValue = toSymExpr(value);
        for (int i = 0; i < match.branches().size(); i++) {
            IrExpr.MatchBranch branch = match.branches().get(i);
            Sort pattern = module.sortFor(branch.pattern());
            ProofResult result = Refinements.satisfies(symbolicValue, pattern, checker(module));
            if (result instanceof ProofResult.Passed) {
                try {
                    return eval(branch.result(), env, module);
                } catch (RuntimeCheckException rce) {
                    if (rce.origin().isPresent()) {
                        throw rce;
                    }
                    throw new RuntimeCheckException(rce.getMessage(), match.origin(), rce);
                }
            }
            if (result instanceof ProofResult.Residual residual) {
                throw new RuntimeCheckException(
                        "Match branch " + i + " (pattern " + pattern
                                + ") could not be decided at runtime against value " + value
                                + "; residual obligation: " + residual.obligation(),
                        match.origin());
            }
        }
        StringBuilder patterns = new StringBuilder("[");
        for (int i = 0; i < match.branches().size(); i++) {
            if (i > 0) patterns.append(", ");
            patterns.append(module.sortFor(match.branches().get(i).pattern()));
        }
        patterns.append("]");
        throw new RuntimeCheckException(
                "No match branch accepted value " + value + " against patterns " + patterns,
                match.origin());
    }

    private Object evalApply(IrExpr.Apply apply, Environment env, CompiledModule module) {
        Object fnValue = eval(apply.fn(), env, module);
        if (!(fnValue instanceof Closure closure)) {
            throw new sibarum.pontif.core.symbolic.RuntimeCheckException(
                    "Apply expects a closure value, got "
                            + (fnValue == null ? "null" : fnValue.getClass().getSimpleName())
                            + ": " + fnValue,
                    apply.origin());
        }
        List<Object> argValues = new ArrayList<>();
        for (IrExpr argExpr : apply.args()) {
            argValues.add(eval(argExpr, env, module));
        }
        try {
            return closure.invoke(argValues, this, module);
        } catch (sibarum.pontif.core.symbolic.RuntimeCheckException rce) {
            if (rce.origin().isPresent()) {
                throw rce;
            }
            throw new sibarum.pontif.core.symbolic.RuntimeCheckException(
                    rce.getMessage(), apply.origin(), rce);
        }
    }

    /**
     * The dispatch symbol an operator routes to when applied to struct operands,
     * or null for operators that are never user-overloaded at runtime. Mirrors
     * {@link MethodOperatorResolver}'s static routing table: arithmetic and
     * ordering route; {@code ==}/{@code !=} stay built-in structural equality,
     * and {@code &}/{@code |} are always primitive Bool ops.
     */
    private static String dispatchOperatorSymbol(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case MOD -> "%"; case POW -> "^";
            case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ, NE, APPROX, AND, OR -> null;
        };
    }

    /**
     * The dispatch-table key to route a struct-operand operator under. The
     * compile-time {@link MethodOperatorResolver} keys an operator overload by
     * its declaration name, which is module-qualified in a linked module
     * ({@code gen.vecmod/+}) and bare in a single file ({@code +}). A BinOp that
     * survives to runtime (a generic body's {@code a + b}) has only the bare
     * symbol, so we reconstruct the qualified key from an operand's FQN type
     * name. Prefers a key the dispatch table actually declares; falls back to
     * the bare symbol (single-file, or for the table to surface a clean
     * "no function" error).
     *
     * <p>STOPGAP: this hand-reconstruction exists only because the dispatch table
     * is FQN-keyed with no "resolve operator symbol over operand types" entry
     * point. The import-by-association <b>association index</b>
     * (docs/cross-module-dispatch.md §6 phase 2 — overloads indexed by each
     * signature type) replaces it with a direct (symbol, operand-types) lookup,
     * at which point this method goes away.
     */
    private static String operatorDispatchName(
            String sym, Object l, Object r, CompiledModule module) {
        if (!module.dispatch().declarationsFor(sym).isEmpty()) return sym;
        for (Object operand : new Object[]{l, r}) {
            if (operand instanceof RecordValue rv && rv.typeName() != null) {
                String mod = sibarum.pontif.core.QualifiedName.parse(rv.typeName()).module();
                if (!mod.isEmpty()) {
                    String qualified = sibarum.pontif.core.QualifiedName.of(mod, sym).fqn();
                    if (!module.dispatch().declarationsFor(qualified).isEmpty()) return qualified;
                }
            }
        }
        return sym;
    }

    private Object evalBinOp(IrExpr.BinOp op, Environment env, CompiledModule module) {
        Object l = eval(op.left(), env, module);
        Object r = eval(op.right(), env, module);
        // Decimal operands use BigDecimal arithmetic and compareTo-based
        // comparison/equality, so 2.0 == 2.00. `Decimal op Int` promotes the Int
        // to Decimal — the lossless direction of the embedding, matching the
        // static sort (inferMaximalSort already types mixed arithmetic Decimal).
        // Non-numeric operands meeting a Decimal stay a clear error.
        // A String operand wins, checked BEFORE Decimal/Char: `+` concatenates,
        // rendering the other operand (so `"x=" + d` concatenates rather than
        // trying to promote the String to Decimal); ordering/equality still
        // require both operands String.
        if (l instanceof sibarum.pontif.core.types.StringValue
                || r instanceof sibarum.pontif.core.types.StringValue) {
            return evalStringBinOp(op, l, r);
        }
        if (l instanceof BigDecimal || r instanceof BigDecimal) {
            return evalDecimalBinOp(op.op(), asDecimal(l, op), asDecimal(r, op), op.origin());
        }
        // Char compares only with Char, by code point — no arithmetic, no
        // Char/Int tower. The undefined cases are now rejected at compile time
        // by MethodOperatorResolver's checkOperatorComplete, so evalCharBinOp's
        // throws are unreachable-through-a-checked-compile backstops (see its
        // javadoc) rather than the primary guard.
        if (l instanceof sibarum.pontif.core.types.CharValue
                || r instanceof sibarum.pontif.core.types.CharValue) {
            return evalCharBinOp(op, l, r);
        }
        // Operator over struct operands: route to the user's operator overload by
        // dispatch on the runtime operand types. This is the runtime home of
        // symmetric operator dispatch for the cases the static
        // MethodOperatorResolver left as BinOp — chiefly a generic body `a + b`
        // over a trait-bounded type parameter E, whose operands are abstract at
        // compile time but concrete struct values here. Built-in Int/Bool/Decimal
        // operands never reach this point.
        // Stream concatenation: `a + b` on two positional streams (tuple-backed)
        // appends b's elements after a's — generalizing String `+` (a String is a
        // Char stream), the same +-concatenates-sequences rule lifted to any Stream
        // (docs/stream-war.md §7, slice 2e). Structural, not per-element.
        if (op.op() == IrExpr.Op.ADD
                && l instanceof RecordValue lr && "_tuple".equals(lr.typeName())
                && r instanceof RecordValue rr && "_tuple".equals(rr.typeName())) {
            return concatTuples(lr, rr);
        }
        if (l instanceof RecordValue || r instanceof RecordValue) {
            String sym = dispatchOperatorSymbol(op.op());
            if (sym != null) {
                String name = operatorDispatchName(sym, l, r, module);
                return dispatchValues(name, List.of(l, r),
                        new IrExpr.Call(name, List.of(op.left(), op.right()), op.origin()),
                        env, module);
            }
        }
        return switch (op.op()) {
            case ADD -> (Long) l + (Long) r;
            case MUL -> (Long) l * (Long) r;
            case SUB -> (Long) l - (Long) r;
            case DIV -> {
                if ((Long) r == 0L) throw new RuntimeCheckException(
                        "Integer division by zero: " + l + " / 0", op.origin());
                yield (Long) l / (Long) r;   // truncates toward zero
            }
            case MOD -> {
                if ((Long) r == 0L) throw new RuntimeCheckException(
                        "Integer remainder by zero: " + l + " % 0", op.origin());
                yield (Long) l % (Long) r;   // sign of dividend; pairs with DIV (a == (a/b)*b + a%b)
            }
            case POW -> {
                long base = (Long) l;
                long e = (Long) r;
                if (e < 0L) throw new RuntimeCheckException(
                        "Negative exponent on Int: " + base + " ^ " + e + " — not an integer",
                        op.origin());
                long acc = 1L;
                for (long i = 0; i < e; i++) acc *= base;
                yield acc;
            }
            case LT -> (Long) l < (Long) r;
            case LE -> (Long) l <= (Long) r;
            case GT -> (Long) l > (Long) r;
            case GE -> (Long) l >= (Long) r;
            case EQ -> java.util.Objects.equals(l, r);
            case NE -> !java.util.Objects.equals(l, r);
            // Without rounding in play, ~= coincides with == .
            case APPROX -> java.util.Objects.equals(l, r);
            case AND -> (Boolean) l && (Boolean) r;
            case OR -> (Boolean) l || (Boolean) r;
        };
    }

    /**
     * Char operations: ordering and equality by code point, both operands
     * Char. Arithmetic/logical ops on chars and mixed Char/non-Char operands
     * are undefined — but they are now rejected at compile time by
     * {@link MethodOperatorResolver}'s {@code checkOperatorComplete} (which
     * consults the same {@link BuiltinOperators#acceptsPrimitive} predicate, so
     * gate and runtime cannot drift). The throws below are therefore
     * unreachable through a checked compile; they stay as defense-in-depth for
     * hand-built IR (the operator analog of the match no-match safety net kept
     * honest by {@code IrMatchTest}). An ord/chr conversion pair is still unruled.
     */
    private static Object evalCharBinOp(IrExpr.BinOp op, Object l, Object r) {
        if (!(l instanceof sibarum.pontif.core.types.CharValue lc)
                || !(r instanceof sibarum.pontif.core.types.CharValue rc)) {
            throw new RuntimeCheckException(
                    "Char compares only with Char — got " + l + " " + opSymbol(op.op())
                            + " " + r + " (no Char/Int tower; ord/chr conversion is not "
                            + "yet a ruled operation)", op.origin());
        }
        return switch (op.op()) {
            case LT -> lc.codePoint() < rc.codePoint();
            case LE -> lc.codePoint() <= rc.codePoint();
            case GT -> lc.codePoint() > rc.codePoint();
            case GE -> lc.codePoint() >= rc.codePoint();
            case EQ -> lc.codePoint() == rc.codePoint();
            case NE -> lc.codePoint() != rc.codePoint();
            // Code points are exact values — ~= coincides with == .
            case APPROX -> lc.codePoint() == rc.codePoint();
            case ADD, SUB, MUL, DIV, MOD, POW, AND, OR -> throw new RuntimeCheckException(
                    "Operator '" + opSymbol(op.op()) + "' is not defined for Char — "
                            + "chars order and compare; they don't compute", op.origin());
        };
    }

    /**
     * String operations. {@code +} is concatenation: at least one operand is a
     * String and the other (Int/Decimal/Char/Bool/String) is rendered to its
     * canonical string (strings.md slice 2). Ordering/equality compare
     * lexicographically by code point and require BOTH operands String (no
     * String/Int tower). Other arithmetic/logical ops and mixed non-String
     * comparisons are undefined — now rejected at compile time by
     * {@link MethodOperatorResolver}'s {@code checkOperatorComplete} (mirrored
     * by {@link BuiltinOperators#acceptsPrimitive}), so the throws below are
     * unreachable-through-a-checked-compile backstops, kept as defense-in-depth
     * for hand-built IR.
     */
    private static Object evalStringBinOp(IrExpr.BinOp op, Object l, Object r) {
        if (op.op() == IrExpr.Op.ADD) {
            return new sibarum.pontif.core.types.StringValue(
                    renderForConcat(l, op) + renderForConcat(r, op));
        }
        if (!(l instanceof sibarum.pontif.core.types.StringValue ls)
                || !(r instanceof sibarum.pontif.core.types.StringValue rs)) {
            throw new RuntimeCheckException(
                    "String compares only with String — got " + l + " " + opSymbol(op.op())
                            + " " + r + " (no String/Char or String/Int tower)", op.origin());
        }
        int c = compareStringsByCodePoint(ls.content(), rs.content());
        return switch (op.op()) {
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
            case EQ -> c == 0;
            case NE -> c != 0;
            // Code points are exact values — ~= coincides with == .
            case APPROX -> c == 0;
            // ADD handled above; the rest don't compute over strings.
            case ADD, SUB, MUL, DIV, MOD, POW, AND, OR -> throw new RuntimeCheckException(
                    "Operator '" + opSymbol(op.op()) + "' is not defined for String — "
                            + "strings order and compare; only '+' concatenates", op.origin());
        };
    }

    /**
     * Renders an operand of a String {@code +} to its canonical string form,
     * failing closed (as a concat error) when the value has no canonical render.
     */
    private static String renderForConcat(Object v, IrExpr.BinOp op) {
        String s = renderToStringOrNull(v);
        if (s == null) {
            throw new RuntimeCheckException(
                    "Cannot concatenate " + (v == null ? "null" : v.getClass().getSimpleName())
                            + " with a String", op.origin());
        }
        return s;
    }

    /**
     * Canonical string rendering shared by String {@code +} concatenation and
     * the {@code (String:value)} cast: a String verbatim, an Int/Decimal/Char/
     * Bool to its display. Decimal uses plain (non-scientific) notation,
     * matching its literal form. Returns {@code null} for a value with no
     * canonical render, so each caller can fail closed with its own message.
     */
    private static String renderToStringOrNull(Object v) {
        if (v instanceof sibarum.pontif.core.types.StringValue s) return s.content();
        if (v instanceof Long n) return Long.toString(n);
        if (v instanceof java.math.BigDecimal d) return d.toPlainString();
        if (v instanceof sibarum.pontif.core.types.CharValue c) {
            return new String(Character.toChars(c.codePoint()));
        }
        if (v instanceof Boolean b) return b.toString();
        return null;
    }

    /**
     * Evaluates a cast {@code (Type:value)} — explicit coercion. Slice 1
     * supports only the built-in renders to {@code String}
     * (Int/Decimal/Char/Bool/String → String); every other target fails closed,
     * honoring the cast law's <em>fabricate-never</em> promise (we never invent
     * a value for a coercion we cannot perform). User-defined {@code Type → Type}
     * coercions and refinement-target casts are later slices.
     */
    private Object evalCast(IrExpr.Cast cast, Environment env, CompiledModule module) {
        Object value = eval(cast.value(), env, module);
        String targetBase = castTargetBaseName(cast.targetSort());
        if ("String".equals(targetBase)) {
            String rendered = renderToStringOrNull(value);
            if (rendered == null) {
                throw new RuntimeCheckException(
                        "Cannot cast "
                                + (value == null ? "null" : value.getClass().getSimpleName())
                                + " to String — only Int, Decimal, Char, Bool and String render",
                        cast.origin());
            }
            return new sibarum.pontif.core.types.StringValue(rendered);
        }
        // User-defined coercion: resolve (source → target) by dispatching the
        // already-evaluated value under the reserved coercion key on the target's
        // base (Coercions.coerceKey — handles a refined target like [Int:@>0] via its
        // base). The shared engine selects the matching source overload (most-specific
        // + source-refinement check); the body runs and its result is returned (the
        // trusted stance — an explicit user transform is the author's responsibility,
        // not the language fabricating). Re-uses the already-evaluated `value`.
        String coercionBase = Coercions.baseName(cast.targetSort());
        if (coercionBase != null) {
            String key = Coercions.coerceKey(coercionBase);
            if (!module.dispatch().declarationsFor(key).isEmpty()) {
                IrExpr.Call synthetic = new IrExpr.Call(
                        key, java.util.List.of(cast.value()), cast.origin());
                return dispatchValues(key, java.util.List.of(value), synthetic, env, module);
            }
        }
        throw new RuntimeCheckException(
                "No coercion to '" + (coercionBase == null ? cast.targetSort() : coercionBase)
                        + "' from " + (value == null ? "null" : value.getClass().getSimpleName())
                        + " — define `cast " + (coercionBase == null ? cast.targetSort() : coercionBase)
                        + ":(x:Source) -> …`, or use a built-in render to String", cast.origin());
    }

    /** Base (head) name of a cast's target sort, or null for a refinement/structural target. */
    private static String castTargetBaseName(IrSort sort) {
        return sort instanceof IrSort.Named n ? n.name() : null;
    }

    /** Lexicographic by Unicode code point (not UTF-16 char) — see Cmp. */
    private static int compareStringsByCodePoint(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static String opSymbol(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/"; case MOD -> "%"; case POW -> "^";
            case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ -> "=="; case NE -> "!="; case APPROX -> "~=";
            case AND -> "&"; case OR -> "|";
        };
    }

    /**
     * Coerces an operand of decimal arithmetic to BigDecimal. Int promotes —
     * the lossless direction ({@code Int → Decimal} embeds exactly; the reverse
     * stays forbidden). Anything else is a clear, origin-carrying error.
     */
    private static BigDecimal asDecimal(Object v, IrExpr.BinOp op) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Long n) return BigDecimal.valueOf(n);
        if (v instanceof Integer n) return BigDecimal.valueOf(n);
        throw new RuntimeCheckException(
                "Operator '" + symbol(op.op()) + "' applied to " + runtimeTypeName(v)
                        + " and Decimal operands — only Int promotes to Decimal.",
                op.origin());
    }

    /** Surface symbol for an operator, for error messages. */
    private static String symbol(IrExpr.Op op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*";
            case DIV -> "/"; case MOD -> "%"; case POW -> "^";
            case LT -> "<"; case LE -> "<="; case GT -> ">"; case GE -> ">=";
            case EQ -> "=="; case NE -> "!="; case APPROX -> "~=";
            case AND -> "&"; case OR -> "|";
        };
    }

    /** Pontif-facing name of a runtime value's type, for error messages. */
    private static String runtimeTypeName(Object v) {
        if (v instanceof Long || v instanceof Integer) return "Int";
        if (v instanceof BigDecimal) return "Decimal";
        if (v instanceof Boolean) return "Bool";
        if (v instanceof RecordValue r) return r.typeName();
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    private static Object evalDecimalBinOp(IrExpr.Op op, BigDecimal l, BigDecimal r, Origin origin) {
        return switch (op) {
            case ADD -> l.add(r);
            case SUB -> l.subtract(r);
            case MUL -> l.multiply(r);
            case DIV -> {
                if (r.signum() == 0) throw new RuntimeCheckException(
                        "Decimal division by zero: " + l.toPlainString() + " / 0", origin);
                yield l.divide(r, MathContext.DECIMAL128);   // lossy by explicit policy
            }
            case MOD -> {
                if (r.signum() == 0) throw new RuntimeCheckException(
                        "Decimal remainder by zero: " + l.toPlainString() + " % 0", origin);
                yield l.remainder(r);
            }
            case POW -> {
                int e;
                try {
                    e = r.intValueExact();
                } catch (ArithmeticException notInt) {
                    throw new RuntimeCheckException(
                            "Non-integer exponent " + r.toPlainString()
                                    + " — a Decimal to a non-integer power is transcendental "
                                    + "(out of scope)", origin);
                }
                if (e < 0) throw new RuntimeCheckException(
                        "Negative exponent " + e + " — non-negative integer powers only", origin);
                yield l.pow(e);
            }
            case LT -> l.compareTo(r) < 0;
            case LE -> l.compareTo(r) <= 0;
            case GT -> l.compareTo(r) > 0;
            case GE -> l.compareTo(r) >= 0;
            case EQ -> l.compareTo(r) == 0;
            case NE -> l.compareTo(r) != 0;
            // Equal within one ulp at the working precision — the tolerance is
            // exactly the loss the division policy declared (see Decimals).
            case APPROX -> sibarum.pontif.core.Decimals.approxEqual(l, r);
            // Logical ops never have Decimal operands (they're Bool-typed).
            case AND, OR -> throw new IllegalStateException(
                    "Logical operator " + op + " applied to Decimal operands");
        };
    }

    private Object evalCall(IrExpr.Call call, Environment env, CompiledModule module) {
        // Lexical scope wins: if the name is locally bound (let / param), invoke
        // the bound value as a closure rather than dispatching by name.
        if (env.contains(call.functionName())) {
            Object fnValue = env.lookup(call.functionName());
            // A bound metareference: application reruns registry dispatch
            // under the REFERENCED name — `ref(2)` does what `inc(2)` does,
            // candidates and narrowings intact.
            if (fnValue instanceof sibarum.pontif.core.types.DispatchValue dv) {
                if (call.args().size() != dv.keySorts().size()) {
                    throw new RuntimeCheckException(
                            "Metareference " + dv + " takes " + dv.keySorts().size()
                                    + " argument(s); got " + call.args().size(),
                            call.origin());
                }
                return dispatchByName(dv.functionName(), call, env, module);
            }
            if (!(fnValue instanceof Closure closure)) {
                throw new RuntimeCheckException(
                        "'" + call.functionName() + "' is bound locally but is not a closure; got "
                                + (fnValue == null ? "null" : fnValue.getClass().getSimpleName())
                                + ": " + fnValue,
                        call.origin());
            }
            List<Object> args = new ArrayList<>();
            for (IrExpr argExpr : call.args()) {
                args.add(eval(argExpr, env, module));
            }
            try {
                return closure.invoke(args, this, module);
            } catch (RuntimeCheckException rce) {
                if (rce.origin().isPresent()) {
                    throw rce;
                }
                throw new RuntimeCheckException(rce.getMessage(), call.origin(), rce);
            }
        }

        return dispatchByName(call.functionName(), call, env, module);
    }

    /**
     * Registry dispatch under {@code name} — the shared tail for direct
     * calls and metareference application (where {@code name} is the
     * referenced function, not the bound variable).
     */
    private Object dispatchByName(
            String name, IrExpr.Call call, Environment env, CompiledModule module) {
        List<Object> argValues = new ArrayList<>();
        for (IrExpr argExpr : call.args()) {
            argValues.add(eval(argExpr, env, module));
        }
        return dispatchValues(name, argValues, call, env, module);
    }

    /**
     * Registry dispatch under {@code name} over ALREADY-EVALUATED argument
     * values — the shared core for direct calls, metareference application, and
     * runtime operator routing (a {@code BinOp} whose operands turn out to be
     * struct values dispatches its operator overload through here, the runtime
     * analog of {@link MethodOperatorResolver}'s static routing for the generic
     * case where the operand sorts were abstract type parameters).
     */
    private Object dispatchValues(
            String name, List<Object> argValues, IrExpr.Call call,
            Environment env, CompiledModule module) {
        List<SymExpr> argSymbolics = new ArrayList<>(argValues.size());
        for (Object argValue : argValues) argSymbolics.add(toSymExpr(argValue));

        DispatchResult dr = module.dispatch().resolve(name, argSymbolics, checker(module));
        switch (dr) {
            case DispatchResult.NoMatch nm -> {
                // Application through a top-level binding: a module-level
                // `let ref = inc[Int]` declares a ZERO-ARG function; applying
                // it with args is the ()-law reaching through that sugar —
                // evaluate the binding, and if it holds a metareference,
                // re-dispatch under the referenced name.
                if (!call.args().isEmpty()) {
                    DispatchResult zero = module.dispatch().resolve(
                            name, List.of(), checker(module));
                    if (zero instanceof DispatchResult.Resolved z) {
                        CompiledModule.CompiledFunction zf = module.functions().get(z.decl());
                        if (zf != null) {
                            Object bound = eval(zf.body(), Environment.empty(), module);
                            if (bound instanceof sibarum.pontif.core.types.DispatchValue dv) {
                                return dispatchByName(dv.functionName(), call, env, module);
                            }
                            // A let bound to a fragment/lambda VALUE: applying it
                            // with args invokes the closure — the synthesis fragment
                            // as a first-class value (docs/stream-war.md §3).
                            if (bound instanceof Closure closure) {
                                return closure.invoke(argValues, this, module);
                            }
                        }
                    }
                }
                throw new RuntimeCheckException(
                        "Dispatch failed for '" + name + "': " + nm.reason(),
                        call.origin());
            }
            case DispatchResult.Ambiguous a -> throw new RuntimeCheckException(
                    "Ambiguous dispatch for '" + name + "' between "
                            + a.candidates().size() + " candidate(s)",
                    call.origin());
            case DispatchResult.Resolved resolved -> {
                // A native source (docs/events.md): a resolved call to the builtin
                // `stdin` (and friends) yields a fresh live, demand-driven source rather
                // than running its placeholder body — the inbound counterpart to the
                // `emit`→NativeFunctions sink. Import-gated: the decl resolves only when
                // `pontif.events.{stdin}` was required, so this is no global.
                if (call.args().isEmpty()) {
                    LiveSource source = NativeSources.get(resolved.decl().name());
                    if (source != null) {
                        return source;
                    }
                }
                try {
                    resolved.call().executeChecks(Map.of(), checker(module));
                } catch (RuntimeCheckException rce) {
                    if (rce.origin().isPresent()) {
                        throw rce;
                    }
                    throw new RuntimeCheckException(rce.getMessage(), call.origin(), rce);
                }
                CompiledModule.CompiledFunction func = module.functions().get(resolved.decl());
                if (func == null) {
                    throw new IllegalStateException(
                            "Dispatch resolved to '" + resolved.decl().name()
                                    + "' but no body was registered in the compiled module");
                }
                Environment funcEnv = Environment.empty();
                for (int i = 0; i < func.params().size(); i++) {
                    funcEnv = funcEnv.extend(func.params().get(i).name(), argValues.get(i));
                }
                return eval(func.body(), funcEnv, module);
            }
        }
    }

    /** Sentinel: no attribute producer is registered for the accessed name. */
    private static final Object NO_ATTRIBUTE = new Object();

    /**
     * Resolves a trait attribute access {@code rec.name} to the satisfier's
     * computed producer {@code <typeName>.<name>(this)} — a 0-user-arg function
     * registered by an {@code assign trait} block. Returns {@link #NO_ATTRIBUTE}
     * if no such producer is dispatchable (the caller then surfaces the normal
     * "no field" error).
     */
    private Object tryAttributeProducer(RecordValue rec, String name, CompiledModule module) {
        if (rec.typeName() == null) return NO_ATTRIBUTE;
        DispatchResult dr = module.dispatch().resolve(
                rec.typeName() + "." + name, List.of(toSymExpr(rec)), checker(module));
        if (!(dr instanceof DispatchResult.Resolved resolved)) return NO_ATTRIBUTE;
        CompiledModule.CompiledFunction func = module.functions().get(resolved.decl());
        if (func == null || func.params().size() != 1) return NO_ATTRIBUTE;
        Environment env = Environment.empty().extend(func.params().get(0).name(), rec);
        return eval(func.body(), env, module);
    }

    private static SymExpr toSymExpr(Object value) {
        // A fragment value (a Closure) passed as an argument or bound: represent it as a
        // curried Lam chain of its arity so a [Method(…):R] parameter check
        // (Refinements.satisfiesFunction) matches by depth — the lambda-replacement
        // becoming a first-class, passable/returnable value (docs/stream-war.md §8b).
        // The body is opaque: satisfiesFunction only needs the arity and a value in
        // return position, not the closure's actual (IrExpr) body.
        if (value instanceof Closure cl) {
            SymExpr body = SymExpr.var("$fragmentBody");
            java.util.List<IrParam> ps = cl.lambda().params();
            for (int i = ps.size() - 1; i >= 0; i--) {
                body = SymExpr.lam(ps.get(i).name(), body);
            }
            return body;
        }
        if (value instanceof Long l) return SymExpr.lit(l);
        if (value instanceof Integer i) return SymExpr.lit(i.longValue());
        if (value instanceof BigDecimal d) return SymExpr.dec(d);
        if (value instanceof sibarum.pontif.core.types.CharValue c) {
            return SymExpr.chr(c.codePoint());
        }
        if (value instanceof sibarum.pontif.core.types.StringValue s) {
            return SymExpr.str(s.content());
        }
        if (value instanceof sibarum.pontif.core.types.DispatchValue dv) {
            return new SymExpr.DispatchRef(dv.functionName(), dv.keySorts());
        }
        if (value instanceof Boolean b) return SymExpr.bool(b);
        if (value instanceof RecordValue r) {
            LinkedHashMap<String, SymExpr> members = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : r.members().entrySet()) {
                members.put(e.getKey(), toSymExpr(e.getValue()));
            }
            return SymExpr.record(r.typeName(), members);
        }
        throw new IllegalArgumentException(
                "Cannot convert runtime value to SymExpr (type "
                        + (value == null ? "null" : value.getClass().getSimpleName()) + "): " + value);
    }
}

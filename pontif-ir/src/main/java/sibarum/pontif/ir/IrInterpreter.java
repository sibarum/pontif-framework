package sibarum.pontif.ir;

import sibarum.pontif.core.types.Metaref;
import sibarum.pontif.core.types.RecordValue;
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

    /**
     * The result of a <b>for-effect drive</b> — a {@code LiveSource} iterate that ran for
     * its side effects (the {@code emit}s woven through its arms) and discarded its output
     * rather than sealing a tuple (docs/events.md). It is the <b>seam for the future
     * payload-free completion handle</b>: {@code main} is a special {@code emit}, and both
     * are ruled to return such a handle (identity + liveness, never the result — the purity
     * membrane forbids a result-bearing Promise). That handle type is its own design pass
     * (the real slice 2); for now this is a deliberately inert placeholder, carrying nothing
     * inspectable, which the runner renders as no output.
     */
    public record DriveResult() {}

    private static final DriveResult DRIVE_RAN = new DriveResult();

    /**
     * An optional observer of the event substrate — the seam a debugger or telemetry channel taps
     * to watch a program's {@code emit} fan-out without changing its behaviour (docs/events.md). It
     * is notified for every event fired through {@link #fireEvent} (both a program {@code emit} and
     * a native re-entry such as a GUI click), in the interpreter's own single thread, before the
     * reactions run. A no-op by default; the Pontif Editor's debug port installs one via
     * {@link #installEventListener}. Purely observational: it must not mutate the event or throw.
     */
    public interface EventListener {

        /** A {@code rec} event was fired; {@code seq} is its monotonic index within this run. */
        void onEmit(RecordValue event, long seq, Origin origin);

        /** A registered {@code action} (named {@code reactionName}) matched and is about to run. */
        default void onActionFired(String reactionName, RecordValue event) {}

        /**
         * A fired {@code event} engaged <b>nothing</b> in its pipeline — no middleware conduit folded
         * it, no consumer {@code action} responded, and no native sink claimed its type — so it was a
         * runtime no-op (docs/orchestration.md, §"Honest edges → Dead letters"). (In the role model:
         * the <em>Action</em> is the consumer; the conduit is middleware a conductor orchestrates; the
         * sink is an Instrument — a dead letter is when none of the three engaged.) Fires once per such
         * emit, every time (an unhandled emit in a loop dead-letters on each iteration). Purely
         * observational — the {@link IrInterpreter} already logs it; this hook lets a journal/debug port
         * record the dead letter too.
         */
        default void onDeadLetter(RecordValue event, Origin origin) {}
    }

    private static volatile EventListener globalListener;

    /** Installs the process-wide {@link EventListener} new interpreters pick up. Pass null to clear. */
    public static void installEventListener(EventListener listener) {
        globalListener = listener;
    }

    private final EventListener eventListener;
    private final java.util.concurrent.atomic.AtomicLong emitSeq = new java.util.concurrent.atomic.AtomicLong();

    /**
     * The persistent state cell of each conduit (docs/reactive-gui.md, the stateful-fold
     * leg), keyed by the conduit's unique key (its fold decl name — unique via the parser's
     * per-conduit sequence). A conduit is a {@code scan} over the temporal stream of a type's
     * events: {@link #fireEvent} threads {@code S} across emits here, seeding it lazily from
     * the conduit's {@code init} the first time the type is emitted. Not shared across runs —
     * one interpreter per program, like {@link #outstanding}.
     */
    private final Map<String, Object> conduitState = new java.util.HashMap<>();

    /**
     * The mutable single-owner state cell of each conductor (docs/orchestration.md), keyed by
     * conductor name; the value is a {@link RecordValue} of its state fields. Seeded lazily from the
     * conductor's compiled state-seed the first time one of its handlers fires. A handler reads it
     * via {@code this.field} and mutates it via {@code this.field = …} (replacing the cell with an
     * updated record — the value stays immutable; only the cell binding moves), always while
     * {@link #currentConductor} names it. Not shared across runs, like {@link #conduitState}.
     */
    private final Map<String, RecordValue> conductorState = new java.util.HashMap<>();

    /**
     * The conductor whose handler is currently firing, or {@code null} outside a conductor reaction.
     * Set by {@link #dispatchToActions} around a {@code #caction#} reaction so {@code this.field}
     * reads/writes resolve to that conductor's {@link #conductorState} cell.
     */
    private String currentConductor;

    /**
     * The runtime value bound to {@code this} inside a conductor handler — a sentinel, not a real
     * record, so a field read/write on it is routed to the live {@link #conductorState} cell of
     * {@link #currentConductor} (giving correct read-after-write within one handler) rather than to
     * a stale snapshot.
     */
    private static final Object CONDUCTOR_SELF = new Object();

    /**
     * Async work dispatched but not yet delivered — the {@link Pending} handles a {@code … on Gpu}
     * iteration produces (docs/gpu-kernels.md, slice 2). The program stays live until every one
     * resolves; {@link #eval(CompiledModule)} drains them after {@code main} (drive-to-quiescence),
     * awaiting each on the main thread and firing its completion event through the substrate. Not
     * shared across runs — one interpreter per program.
     */
    private final List<Pending> outstanding = new ArrayList<>();

    /**
     * The resolved routing table (docs/orchestration.md, §"The conductor graph") — per emitted type, its owning
     * conduit(s) + subscriber actions, resolved once and cached instead of re-scanned every {@code emit}. Lazily
     * built per module via {@link #routing}; one interpreter typically evaluates one module.
     */
    private RoutingTable routing;

    /** The routing table for {@code module}, rebuilding it if a different module is seen. */
    private RoutingTable routing(CompiledModule module) {
        if (routing == null || routing.module() != module) {
            routing = new RoutingTable(module);
        }
        return routing;
    }

    public IrInterpreter(Simplifier simplifier) {
        this.simplifier = simplifier;
        this.eventListener = globalListener;
    }

    public Object eval(CompiledModule module) {
        // Prove the conductor graph is single-owner before any event flows (docs/orchestration.md): two
        // conduits whose event types are in an ancestry relation would both match one emit — caught here,
        // at load, rather than latently when such an event is first fired.
        module.validateSingleOwnerConduits();
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
        Object mainValue = eval(module.main(), Environment.empty(), module);

        // Drive to quiescence (docs/gpu-kernels.md, slice 2): the program is not done until every
        // async GPU dispatch resolves. For each, await its per-element results on THIS thread — the
        // event substrate's single thread — and fire the woven completion emit once per element: bind
        // the kernel-computed value to the emit's placeholder, evaluate the event construction (so it
        // routes exactly like an author-written `emit`), and fire it. The result is thus consumed by a
        // reacting `action` (forward only; no `await` reads it back — RULED James 2026-07-05). A device
        // Rejection surfaces here as the !! hazard (Pending.values). `… on Gpu` is for-effect — its
        // value went to the reactions — so it renders as a DriveResult (no output), like `main echo`.
        for (int i = 0; i < outstanding.size(); i++) {  // indexed: a reaction may itself dispatch more
            Pending p = outstanding.get(i);
            // A spread already synchronized this handle and delivered its results (the sync leg) —
            // nothing to drive.
            if (p.consumed()) {
                continue;
            }
            // Never observed: not spread into a consumer, and no woven emit to replay. Fail closed —
            // observability is "spread-consumed OR emitted" (docs/gpu-kernels.md, the execution model).
            if (!p.hasEmit()) {
                throw new RuntimeCheckException(
                        "`… on Gpu` produced a result that is never observed — spread it into a consumer "
                                + "(synchronous) or weave an `emit` for an `action` (asynchronous).",
                        p.origin());
            }
            // The asynchronous leg: replay the woven emit per element on the host (forward-only).
            for (Object value : p.values()) {
                Object event = eval(p.eventTemplate(),
                        Environment.empty().extend(p.argVar(), value), module);
                if (!(event instanceof RecordValue rec)) {
                    throw new RuntimeCheckException(
                            "`… on Gpu` completion event must construct an event struct, got "
                                    + (event == null ? "null" : event.getClass().getSimpleName()),
                            p.origin());
                }
                fireEvent(rec, module, p.origin());
            }
        }
        outstanding.clear();
        return mainValue instanceof Pending ? DRIVE_RAN : mainValue;
    }

    /**
     * A resolver the GPU kernel runner uses to inline a user-function call in a kernel body
     * (docs/gpu-kernels.md, slice 2): the woven {@code emit} lives inside such a function, and
     * {@code ExprLowering} can't lower a {@code Call}, so the runner substitutes the function's body
     * in first. Matches by bare name + arity (kernel fragments are monomorphic in v1); returns
     * {@code null} for anything that isn't a user function (a native/builtin call, left as-is).
     */
    private static KernelRunners.FunctionResolver gpuFunctionResolver(CompiledModule module) {
        return (name, arity) -> {
            String bare = bareName(name);
            for (CompiledModule.CompiledFunction fn : module.functions().values()) {
                if (fn.params().size() == arity && bareName(fn.decl().name()).equals(bare)) {
                    return new KernelRunners.ResolvedFunction(
                            fn.params().stream().map(IrParam::name).toList(), fn.body());
                }
            }
            return null;
        };
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
                // A metareference is a first-class OBJECT — a RecordValue tagged with its
                // concrete dispatch nominal (AlgebraicDispatch when the referent is proven
                // algebraic, else DispatchBase), carrying the dispatch payload. Its `.ast`
                // then resolves through the stock RecordValue attribute-producer path.
                yield Metaref.of(d.functionName(), keys,
                        module.algebraicFunctions().contains(d.functionName()));
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
                // A GPU stream (an eager `… on Gpu` dispatch) can't be inspected without awaiting it, and
                // the bind must not block — the spread synchronizes, not the bind (docs/gpu-kernels.md).
                // Its element type is fixed at lowering (Int-only in v1) and the claim's element type was
                // reconciled against the iteration's compile-time type, so the claim is discharged
                // structurally here rather than by materializing the not-yet-computed batch.
                if (l.claim() != null && !(value instanceof Pending)) {
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
                // `this.field` inside a conductor handler — read the live state cell of the firing
                // conductor (docs/orchestration.md). Live, not a snapshot, so a read after a
                // `this.field = …` in the same handler sees the new value.
                if (baseValue == CONDUCTOR_SELF) {
                    yield conductorStateCell(fa.origin()).get(fa.fieldName(), fa.origin());
                }
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
     * Evaluates an {@code emit EVENT  BODY} statement (docs/events.md): evaluate the event,
     * route it <b>by its type name</b> to its consumers — every declared {@code action}
     * whose match-filter the event satisfies (the reaction leg, fired in declaration order)
     * and/or the builtin native sink ({@link NativeFunctions} — {@code StdOut}/{@code StdErr}).
     * The write-only result is discarded; the body continues. An event type with no consumer
     * at all (no sink, no registered action) fails closed; a registered action that simply
     * doesn't match <i>this</i> instance is a legitimate no-op. Dispatch is synchronous in
     * this slice (the scheduler / mailbox is a later slice).
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
        // No fail-closed guard (docs/reactive-gui.md): an event with no matching action / conduit /
        // sink is a deliberate no-op — fireEvent simply fires nothing. emit is a fire-and-forget
        // broadcast; a consumer may or may not exist.
        fireEvent(rec, module, emit.origin());
        return eval(emit.body(), env, module);
    }

    /**
     * Fires an event through the substrate (docs/events.md): run every declared {@code action}
     * whose match-filter {@code rec} satisfies (declaration order; each reaction is a 1-param
     * function — the event — run for effect, mirroring tryAttributeProducer), then apply the
     * native {@link NativeFunctions} sink if any. <b>Public</b> so a long-running native call —
     * the GUI {@code window} loop — can re-enter on a click via {@link NativeCalls.Context}
     * (the click builds a {@code ClickEvent} and fires it here). No fail-closed: an event with
     * no consumer is a no-op (the {@code emit} statement does the typo guard upstream).
     */
    public void fireEvent(RecordValue rec, CompiledModule module, Origin origin) {
        String typeName = rec.typeName();
        if (eventListener != null) {
            eventListener.onEmit(rec, emitSeq.incrementAndGet(), origin);
        }
        // The conduit leg (docs/reactive-gui.md) sits BETWEEN emit and the actions: a stateful
        // fold over the temporal stream of the type's events. Trait-aware match, exactly like
        // actions — the emitted type's own conduit plus any keyed by a trait it satisfies.
        List<CompiledModule.CompiledConduit> conduits = routing(module).routeFor(typeName).conduits();
        if (conduits.size() > 1) {
            // Backstop: the ancestry conflict (a conduit's key is-a another's) is now caught at link
            // (IrCompiler, the static-graph single-owner rule). This still fires only for the residual
            // "diamond" case — a concrete type satisfying two otherwise-unrelated conduit-key traits.
            throw new RuntimeCheckException(
                    "multiple conduits match event type '" + typeName + "' — a single event "
                            + "matching several conduits (the ordered pipeline) is not yet "
                            + "supported; declare at most one conduit for a type-or-ancestor",
                    origin);
        }
        if (conduits.size() == 1) {
            CompiledModule.CompiledConduit conduit = conduits.get(0);
            RecordValue dispatched = foldThroughConduit(conduit, rec, module);
            if (dispatched != null) dispatchToActions(dispatched, module, origin);  // null = dropped
            return;
        }
        // No conduit: the event reaches its consumer actions (+ native sink) directly.
        dispatchToActions(rec, module, origin);
        // Dead letter = the CONFIG GAP (docs/orchestration.md, §"Honest edges"): the emitted TYPE has no
        // registered consumer AT ALL — no conduit (none on this branch), no action bucket, no sink. That
        // "nothing is configured to handle this type" miss is worth surfacing. It is NOT a dead letter
        // when a consumer IS registered but its refinement rejects THIS instance (the intentional muted
        // instrument) — that stays silent, per the ruling. Logged every fire of an uncovered type.
        if (!hasCoverage(typeName, module)) {
            deadLetter(rec, origin);
        }
    }

    /**
     * Whether the emitted {@code typeName} has ANY registered consumer — a subscriber action bucket
     * (trait-aware) or a native sink. Conduits are handled before the dead-letter path, so they are not
     * re-checked here. The CONFIG-GAP predicate: the dead letter fires only when this is false, so a
     * registered handler whose refinement rejects the current instance (the muted instrument) is covered
     * and stays silent (docs/orchestration.md, §"Honest edges").
     */
    private boolean hasCoverage(String typeName, CompiledModule module) {
        return !routing(module).routeFor(typeName).subscribers().isEmpty()
                || NativeFunctions.get(typeName) != null;
    }

    /**
     * Records a <b>dead letter</b> — a fired event whose <b>type has no registered consumer at all</b>
     * (no conduit, no action bucket, no sink): the runtime, as configured, has nothing that handles this
     * type — a config gap, not the intentional muted-instrument no-op (a registered handler that merely
     * rejects the instance stays silent — see {@link #hasCoverage}). Writes a runtime log line every time
     * (the emit still no-ops; this is pure observability) and notifies the {@link EventListener}. This is
     * the minimal runtime signal; the full design (compile warning for the static case + an overridable
     * dead-letter conductor, docs/orchestration.md §"Honest edges") is a later refinement.
     */
    private void deadLetter(RecordValue rec, Origin origin) {
        System.err.println("[pontif] dead letter: emit " + rec.typeName()
                + " reached no consumer" + (origin == null ? "" : " (" + origin + ")"));
        if (eventListener != null) {
            eventListener.onDeadLetter(rec, origin);
        }
    }

    /**
     * Runs one emitted event through its conduit (docs/reactive-gui.md, Step 2): reads the
     * conduit's current state {@code S} from its persistent cell (seeding it lazily from
     * {@code init} on first sight), evaluates the fold body with {@code e} = the event and
     * {@code s} = the state, extracts the positional {@code {R, S'}} tuple, threads {@code S'}
     * back into the cell, and returns {@code R} — the same-type event dispatched onward to the
     * actions. {@code R} must be the SAME event type as the incoming event (transform the data, not
     * the type) or the {@code Nothing} omission value; {@code Nothing} drops the event (returns
     * {@code null} — no action fires — with the state still threaded). To change the event type,
     * re-emit a new event from the fold body ({@code emit …}), which routes independently.
     */
    private RecordValue foldThroughConduit(
            CompiledModule.CompiledConduit conduit, RecordValue rec, CompiledModule module) {
        String key = conduit.fold().decl().name();
        if (!conduitState.containsKey(key)) {
            conduitState.put(key, eval(conduit.init().body(), Environment.empty(), module));
        }
        Object state = conduitState.get(key);
        CompiledModule.CompiledFunction fold = conduit.fold();
        Environment foldEnv = Environment.empty()
                .extend(fold.params().get(0).name(), rec)
                .extend(fold.params().get(1).name(), state);
        Object folded = eval(fold.body(), foldEnv, module);
        if (!(folded instanceof RecordValue tuple) || !"_tuple".equals(tuple.typeName())) {
            throw new RuntimeCheckException(
                    "conduit '" + conduitDisplayName(fold) + "' must return a {R, S'} tuple, got "
                            + (folded == null ? "null" : folded.getClass().getSimpleName()),
                    fold.body().origin());
        }
        conduitState.put(key, tuple.members().get("_1"));   // thread S' (even when the event is dropped)
        Object r = tuple.members().get("_0");
        // Drop (docs/reactive-gui.md): Nothing in the dispatched slot swallows the event — no action
        // fires — while the new state still threads. The lossy-filter face of scan.
        if (isNothing(r)) return null;
        if (!(r instanceof RecordValue out)) {
            throw new RuntimeCheckException(
                    "conduit '" + conduitDisplayName(fold) + "' dispatched slot must be the same "
                            + "event type or Nothing, got "
                            + (r == null ? "null" : r.getClass().getSimpleName()),
                    fold.body().origin());
        }
        // A conduit transforms an event's DATA but not its TYPE — to change type, re-emit. The
        // dispatched value must carry the same (bare) type as the event that entered the fold.
        if (!bareName(out.typeName()).equals(bareName(rec.typeName()))) {
            throw new RuntimeCheckException(
                    "conduit '" + conduitDisplayName(fold) + "' must return the same event type it "
                            + "received (" + bareName(rec.typeName()) + ") or Nothing — transform the "
                            + "data, not the type; to change type, re-emit. Got "
                            + bareName(out.typeName()),
                    fold.body().origin());
        }
        return out;
    }

    /**
     * Dispatches an event value to the reaction leg (docs/events.md): every declared
     * {@code action} whose match-filter {@code rec} satisfies (declaration order; each a
     * 1-param function run for effect), then the native {@link NativeFunctions} sink if any.
     * Called with either a conduit's output {@code R} or, when no conduit matches, the raw
     * emitted event. Returns whether it was <b>handled</b> — a consumer {@code action} responded
     * or a native sink claimed the type — so {@link #fireEvent} can dead-letter the event when
     * nothing did (an action bucket that exists but whose refinement rejects this instance counts
     * as unhandled: this instance genuinely reached no consumer).
     */
    private void dispatchToActions(RecordValue rec, CompiledModule module, Origin origin) {
        String typeName = rec.typeName();
        SymExpr sym = toSymExpr(rec);
        // Trait-aware routing (docs/reactive-gui.md §1): the emitted type's own bucket plus every
        // trait bucket it is-a member of, most-specific first. The per-action matchSort test below
        // still gates refinements, so a supertype Action only fires on instances it truly matches.
        List<CompiledModule.CompiledAction> actions = routing(module).routeFor(typeName).subscribers();
        for (CompiledModule.CompiledAction action : actions) {
            if (Refinements.satisfies(sym, action.matchSort(), checker(module))
                    instanceof ProofResult.Passed) {
                CompiledModule.CompiledFunction fn = action.reaction();
                if (eventListener != null) {
                    eventListener.onActionFired(actionDisplayName(fn), rec);
                }
                Environment reactionEnv = Environment.empty()
                        .extend(fn.params().get(0).name(), rec);
                if (action.conductorName() == null) {
                    eval(fn.body(), reactionEnv, module);
                } else {
                    // A conductor handler: seed its state cell once, bind `this` to the self
                    // sentinel (so this.field reads/writes hit the live cell), and record which
                    // conductor is firing for the duration (docs/orchestration.md).
                    seedConductorState(action.conductorName(), module, origin);
                    String savedConductor = currentConductor;
                    currentConductor = action.conductorName();
                    try {
                        eval(fn.body(), reactionEnv.extend("this", CONDUCTOR_SELF), module);
                    } finally {
                        currentConductor = savedConductor;
                    }
                }
            }
        }
        NativeFunctions.Effect sink = NativeFunctions.get(typeName);
        if (sink != null) {
            sink.apply(rec, origin);
        }
    }

    /** The author-visible name of a conduit, recovered from its {@code #conduit#N#name} key. */
    private static String conduitDisplayName(CompiledModule.CompiledFunction fn) {
        String key = fn.decl().name();
        int hash = key.lastIndexOf('#');
        return hash < 0 ? key : key.substring(hash + 1);
    }

    /** The author-visible name of an action reaction, recovered from its {@code #action#N#name} key. */
    private static String actionDisplayName(CompiledModule.CompiledFunction fn) {
        String key = fn.decl().name();
        int hash = key.lastIndexOf('#');
        return hash < 0 ? key : key.substring(hash + 1);
    }

    /** The bare suffix of a possibly module-qualified type name ({@code mod/Tick} → {@code Tick}). */
    private static String bareName(String typeName) {
        int slash = typeName.lastIndexOf('/');
        return slash < 0 ? typeName : typeName.substring(slash + 1);
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
        // `… on Gpu` (docs/gpu-kernels.md): a gpu-marked iteration runs as a compute kernel on the
        // GPU instead of the CPU drive below. The runner is injected by the opt-in pontif-gpu module;
        // with none loaded this is an honest error, never a silent CPU fallback. The sources are
        // evaluated to their (finite, materialized) stream values and handed to the runner, which
        // lowers the iteration to a SuperVast kernel and dispatches it.
        if (it.gpu()) {
            KernelRunners.KernelRunner runner = KernelRunners.get();
            if (runner == null) {
                throw new RuntimeCheckException(
                        "`… on Gpu` needs GPU support on the classpath (the pontif-gpu module); "
                                + "none is loaded", it.origin());
            }
            List<Object> sourceValues = new ArrayList<>(1 + it.coSources().size());
            sourceValues.add(eval(it.source(), env, module));
            for (IrExpr cs : it.coSources()) sourceValues.add(eval(cs, env, module));
            // Async (docs/gpu-kernels.md, slice 2): the runner dispatches on a worker thread and
            // returns a Pending immediately — it never blocks eval. Registering it here lets the
            // drive-to-quiescence loop (see eval(CompiledModule)) fire the deferred completion emits
            // on THIS (the main) thread, so all event firing stays single-threaded. The resolver lets
            // the runner inline the user function the kernel body calls (the woven emit lives inside).
            Object dispatched = runner.run(it, sourceValues, gpuFunctionResolver(module));
            if (dispatched instanceof Pending p) outstanding.add(p);
            return dispatched;
        }

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
        //
        // FOR-EFFECT: a live source is potentially unbounded, so collecting its output
        // would grow without limit — collecting it is the unsafe operation we decline to
        // support (productivity, project_infinite_streams). So this drive runs each arm for
        // its writes (the `emit`s still fire — the write value is evaluated either way) but
        // DISCARDS rather than accumulating (forEffect=true skips the stream append), and
        // returns the inert DriveResult instead of a sealed tuple. (A bounded prefix —
        // takeWhile over a live source, collected to a value — is not yet supported; it
        // would materialise a finite tuple and ride the eager path below.)
        // Evaluate every source EXACTLY ONCE — re-evaluating a source expression would re-run its
        // effects (a source that emits, or an eager `… on Gpu` dispatch: a second eval = a second GPU
        // batch, orphaning the first). The single-source live-source check reads this one evaluation.
        List<IrExpr> sourceExprs = new ArrayList<>();
        sourceExprs.add(it.source());
        sourceExprs.addAll(it.coSources());
        List<Object> sourceValues = new ArrayList<>(sourceExprs.size());
        for (IrExpr se : sourceExprs) {
            sourceValues.add(eval(se, env, module));
        }

        if (it.coSources().isEmpty() && sourceValues.get(0) instanceof LiveSource live) {
            java.util.Optional<Object> next;
            while ((next = live.pull()).isPresent()) {
                if (iterateStep(it, next.get(), env, module, kinds, streams, accumulators, true)) {
                    break;  // STOP disposition
                }
            }
            return DRIVE_RAN;  // for-effect: discard, never seal
        }

        List<List<Object>> columns = new ArrayList<>(sourceExprs.size());
        int steps = Integer.MAX_VALUE;
        for (Object sourceValue : sourceValues) {
            Object sv = sourceValue;
            // A GPU stream (an eager `… on Gpu` dispatch) is synchronized HERE — the spread is the join
            // point (docs/gpu-kernels.md, "eager dispatch, synchronize on spread"): mark it consumed so
            // drive-to-quiescence won't also replay it, and await its per-element results (blocks; a device
            // failure surfaces as the !! hazard). The awaited values then iterate like any other stream.
            if (sv instanceof Pending gpu) {
                gpu.markConsumed();
                List<Object> awaited = gpu.values();
                java.util.Map<String, Object> members = new LinkedHashMap<>();
                for (int i = 0; i < awaited.size(); i++) members.put("_" + i, awaited.get(i));
                sv = new RecordValue("_stream", members);
            }
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
            if (iterateStep(it, element, env, module, kinds, streams, accumulators, false)) {
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
     *
     * <p>{@code forEffect} (the demand-driven live-source drive) evaluates each write's
     * value — so the {@code emit}s woven through it still fire — but {@link #routeWrite}
     * then DISCARDS a STREAM write rather than accumulating it, so an unbounded source does
     * not grow without limit (docs/events.md). Bounded ACCUMULATOR / STOP / FAN dispositions
     * are unaffected (an accumulator is a single threaded value, not a growing list).
     */
    private boolean iterateStep(IrExpr.Iterate it, Object element, Environment env,
            CompiledModule module, Map<String, IrExpr.OutputKind> kinds,
            Map<String, List<Object>> streams, Map<String, Object> accumulators,
            boolean forEffect) {
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
                            Object cv = rv.members().get(os.name());
                            // A `Break` in any channel terminates the stream (§3) —
                            // triggering element not emitted, like the STOP disposition.
                            if (isBreak(cv)) return true;
                            routeWrite(os.name(), cv, kinds,
                                    streams, accumulators, it.origin(), forEffect);
                        }
                        continue;
                    }
                    IrExpr.OutputKind k = kinds.get(w.output());
                    if (k == null) throw new RuntimeCheckException(
                            "Iterate: write to unknown output '" + w.output() + "'", it.origin());
                    Object v = eval(w.value(), frame, module);
                    // A returned `Break` value halts the stream (docs/stream-war.md §3,
                    // the takeWhile / infinite-cutoff shape) — the triggering element is
                    // not emitted. This is the returned-value face of the STOP disposition.
                    if (isBreak(v)) return true;
                    routeWrite(w.output(), v, kinds,
                            streams, accumulators, it.origin(), forEffect);
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
    /**
     * The live state cell of the currently-firing conductor ({@link #currentConductor}), seeding it
     * lazily from the conductor's compiled state-seed on first access. Throws if reached outside a
     * conductor handler (a {@code this.field} access with no firing conductor) — a guard against a
     * mis-lowered reference, not a user-facing path.
     */
    private RecordValue conductorStateCell(Origin origin) {
        if (currentConductor == null) {
            throw new RuntimeCheckException(
                    "`this` state access outside a conductor handler", origin);
        }
        return conductorState.get(currentConductor);
    }

    /**
     * Seeds a conductor's state cell on first sight by evaluating its compiled state-seed (the
     * record of its fields at their initializers), then leaves it — subsequent fires reuse the
     * threaded cell. No-op once seeded.
     */
    private void seedConductorState(String conductorName, CompiledModule module, Origin origin) {
        if (conductorState.containsKey(conductorName)) return;
        CompiledModule.CompiledConductor cc = module.conductors().get(conductorName);
        if (cc == null) {
            throw new RuntimeCheckException(
                    "no compiled state for conductor '" + conductorName + "'", origin);
        }
        Object seeded = eval(cc.stateInit().body(), Environment.empty(), module);
        if (!(seeded instanceof RecordValue rec)) {
            throw new RuntimeCheckException(
                    "conductor '" + conductorName + "' state seed must be a record", origin);
        }
        conductorState.put(conductorName, rec);
    }

    private static boolean isNothing(Object v) {
        if (!(v instanceof RecordValue rv) || rv.typeName() == null) return false;
        String n = rv.typeName();
        // Cross-module construction qualifies the nominal ("pontif.core/Nothing");
        // a same-module use is bare ("Nothing"). Match either.
        return n.equals("Nothing") || n.endsWith("/Nothing");
    }

    /**
     * Whether {@code v} is the {@code Break} termination value (pontif.core) — the sibling
     * of {@link #isNothing} in the stream control-value family (docs/stream-war.md §3).
     * Returning {@code Break} at a stream channel <b>halts</b> the stream (the takeWhile
     * shape / infinite-stream cutoff), where {@code Nothing} only drops the one element.
     * Same bare-or-{@code pontif.core/}-qualified nominal match as {@code isNothing}.
     */
    private static boolean isBreak(Object v) {
        if (!(v instanceof RecordValue rv) || rv.typeName() == null) return false;
        String n = rv.typeName();
        return n.equals("Break") || n.endsWith("/Break");
    }

    /**
     * Routes one written value to its output by kind: a STREAM appends (dropping the
     * {@code Nothing} omission value — the lossy filter shape, docs/stream-war.md §3);
     * an ACCUMULATOR threads the next revision (the prior was read via the frame). Under
     * {@code forEffect} a STREAM write is discarded (the value was already evaluated by the
     * caller, so its {@code emit}s fired) — the unbounded-source guard, docs/events.md.
     */
    private void routeWrite(
            String output, Object v, java.util.Map<String, IrExpr.OutputKind> kinds,
            java.util.Map<String, java.util.List<Object>> streams,
            java.util.Map<String, Object> accumulators, sibarum.pontif.core.Origin origin,
            boolean forEffect) {
        IrExpr.OutputKind k = kinds.get(output);
        if (k == null) throw new RuntimeCheckException(
                "Iterate: write to unknown output '" + output + "'", origin);
        switch (k) {
            case STREAM -> { if (!forEffect && !isNothing(v)) streams.get(output).add(v); }
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
            // A function-style call sig (a closure) has no nominal base name; a
            // dispatch-style one is named by its head type (the old "Dispatch").
            case IrSort.CallSig c -> CallKinds.builtin(c.typeName()) == CallKinds.Kind.FUNCTION
                    ? null : c.typeName();
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

    /**
     * Reflect a first-class function value to its parameters + IR body — the
     * runtime backing of {@link NativeCalls.Context#reflectFunction}. A {@link Closure}
     * carries its lambda directly; a metareference is resolved by name to its
     * single (non-overloaded) declaration in the compiled module. Null otherwise.
     */
    private NativeCalls.ReflectedFunction reflectFunction(Object fnValue, CompiledModule module) {
        if (fnValue instanceof Closure c) {
            return new NativeCalls.ReflectedFunction(c.lambda().params(), c.lambda().body());
        }
        if (Metaref.is(fnValue)) {
            return reflectFunctionByName(
                    Metaref.functionName(fnValue), Metaref.keySorts(fnValue).size(), module);
        }
        return null;
    }

    /** Resolve a function by name + arity to its (params, body); null if none. */
    private NativeCalls.ReflectedFunction reflectFunctionByName(
            String name, int arity, CompiledModule module) {
        for (Map.Entry<sibarum.pontif.core.symbolic.FunctionDecl,
                CompiledModule.CompiledFunction> e : module.functions().entrySet()) {
            if (e.getKey().name().equals(name) && e.getValue().params().size() == arity) {
                return new NativeCalls.ReflectedFunction(
                        e.getValue().params(), e.getValue().body());
            }
        }
        return null;
    }

    private Object evalApply(IrExpr.Apply apply, Environment env, CompiledModule module) {
        Object fnValue = eval(apply.fn(), env, module);
        List<Object> argValues = new ArrayList<>();
        for (IrExpr argExpr : apply.args()) {
            argValues.add(eval(argExpr, env, module));
        }
        // A metareference reached as a bare EXPRESSION (a returned value, a field, a
        // let) is applied by re-running dispatch under its referenced name — the same
        // thing the name-lookup Call path does, so function values are uniformly
        // first-class however they're reached (docs/metatypes.md).
        if (Metaref.is(fnValue)) {
            String fn = Metaref.functionName(fnValue);
            int arity = Metaref.keySorts(fnValue).size();
            if (apply.args().size() != arity) {
                throw new sibarum.pontif.core.symbolic.RuntimeCheckException(
                        "Metareference '" + fn + "' takes "
                                + arity + " argument(s), got " + apply.args().size(),
                        apply.origin());
            }
            IrExpr.Call synthetic = new IrExpr.Call(fn, apply.args(), apply.origin());
            return dispatchValues(fn, argValues, synthetic, env, module);
        }
        if (!(fnValue instanceof Closure closure)) {
            throw new sibarum.pontif.core.symbolic.RuntimeCheckException(
                    "Apply expects a function value, got "
                            + (fnValue == null ? "null" : fnValue.getClass().getSimpleName())
                            + ": " + fnValue,
                    apply.origin());
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
        // Conductor state assignment `this.field = value` (docs/orchestration.md) — the parser's
        // desugaring of the statement, reserved and non-lexable so it can never collide with a user
        // function. args = [Str(field), valueExpr]; replace that field in the firing conductor's
        // live cell with the evaluated value (the record stays immutable — the cell binding moves).
        if ("#assign-self#".equals(call.functionName())) {
            String field = ((IrExpr.Str) call.args().get(0)).value();
            Object value = eval(call.args().get(1), env, module);
            RecordValue cell = conductorStateCell(call.origin());
            Map<String, Object> next = new LinkedHashMap<>(cell.members());
            if (!next.containsKey(field)) {
                throw new RuntimeCheckException(
                        "conductor '" + currentConductor + "' has no state field '" + field + "'",
                        call.origin());
            }
            next.put(field, value);
            conductorState.put(currentConductor, new RecordValue(next));
            return new RecordValue("Nothing", Map.of());   // write-only; the desugaring discards it
        }
        // Lexical scope wins: if the name is locally bound (let / param), invoke
        // the bound value as a closure rather than dispatching by name.
        if (env.contains(call.functionName())) {
            Object fnValue = env.lookup(call.functionName());
            // A bound metareference: application reruns registry dispatch
            // under the REFERENCED name — `ref(2)` does what `inc(2)` does,
            // candidates and narrowings intact.
            if (Metaref.is(fnValue)) {
                int arity = Metaref.keySorts(fnValue).size();
                if (call.args().size() != arity) {
                    throw new RuntimeCheckException(
                            "Metareference " + fnValue + " takes " + arity
                                    + " argument(s); got " + call.args().size(),
                            call.origin());
                }
                return dispatchByName(Metaref.functionName(fnValue), call, env, module);
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
                            if (Metaref.is(bound)) {
                                return dispatchByName(Metaref.functionName(bound), call, env, module);
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
                // A native call (docs/extensions.md): a resolved call to an extension-backed
                // function (`stdin`, the GUI `window`, …) runs its Java object against the
                // evaluated args instead of the placeholder body. Import-gated: the decl
                // resolves only when its module was required, so this is no global. `stdin`
                // returns a LiveSource (the demand-driven source); other calls return their
                // result (e.g. the window fn blocks, then returns).
                NativeCalls.NativeCall nativeCall = NativeCalls.get(resolved.decl().name());
                if (nativeCall != null) {
                    NativeCalls.Context ctx = new NativeCalls.Context() {
                        @Override public void fireEvent(RecordValue ev) {
                            IrInterpreter.this.fireEvent(ev, module, Origin.NONE);
                        }
                        @Override public boolean satisfies(RecordValue value, String traitName) {
                            return satisfiesTrait(value, traitName, module);
                        }
                        @Override public Object invoke(RecordValue value, String methodName) {
                            return invokeMethod(value, methodName, module);
                        }
                        @Override public CompiledModule.CompiledFunction methodImpl(
                                RecordValue value, String methodName) {
                            return resolveMethodImpl(value, methodName, module);
                        }
                        @Override public NativeCalls.ReflectedFunction reflectFunction(
                                Object fnValue) {
                            return IrInterpreter.this.reflectFunction(fnValue, module);
                        }
                        @Override public NativeCalls.ReflectedFunction reflectFunctionByName(
                                String name, int arity) {
                            return IrInterpreter.this.reflectFunctionByName(name, arity, module);
                        }
                    };
                    return nativeCall.call(argValues, ctx);
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
        // Dispatch resolution under both the qualified and bare type spellings (an
        // attribute producer registers like a 0-user-arg method).
        for (String key : List.of(rec.typeName() + "." + name,
                bareName(rec.typeName()) + "." + name)) {
            DispatchResult dr = module.dispatch().resolve(
                    key, List.of(toSymExpr(rec)), checker(module));
            if (dr instanceof DispatchResult.Resolved resolved) {
                CompiledModule.CompiledFunction func = module.functions().get(resolved.decl());
                if (func != null && func.params().size() == 1) {
                    Environment env = Environment.empty().extend(func.params().get(0).name(), rec);
                    return eval(func.body(), env, module);
                }
            }
        }
        // Fallback: scan compiled functions by decl-name suffix — robust to the linker
        // module-qualifying a bare-builtin-typed impl's producer (e.g. the required
        // pontif.algebra's `AlgebraicDispatch.ast`, keyed `pontif.algebra/AlgebraicDispatch.ast`).
        String suffix = bareName(rec.typeName()) + "." + name;
        for (Map.Entry<FunctionDecl, CompiledModule.CompiledFunction> e
                : module.functions().entrySet()) {
            String n = e.getKey().name();
            if ((n.equals(suffix) || n.endsWith("/" + suffix)) && e.getValue().params().size() == 1) {
                Environment env = Environment.empty()
                        .extend(e.getValue().params().get(0).name(), rec);
                return eval(e.getValue().body(), env, module);
            }
        }
        return NO_ATTRIBUTE;
    }

    /**
     * Whether {@code value}'s type satisfies {@code traitName} — the GUI bridge's "is this a
     * {@code Clickable}?" check ({@link NativeCalls.Context#satisfies}). Robust to bare vs
     * module-qualified spellings of both the trait and the value's type (the registry and the
     * runtime value may carry either form), mirroring the action keying.
     */
    private boolean satisfiesTrait(RecordValue value, String traitName, CompiledModule module) {
        if (value == null || value.typeName() == null || traitName == null) return false;
        sibarum.pontif.core.symbolic.TraitRegistry tr = module.dispatch().traitRegistry();
        String type = value.typeName();
        String bareType = bareName(type);
        String bareTrait = bareName(traitName);
        return tr.satisfies(traitName, type) || tr.satisfies(bareTrait, type)
                || tr.satisfies(traitName, bareType) || tr.satisfies(bareTrait, bareType);
    }

    /**
     * Invokes a 0-user-arg instance method {@code <type>.<methodName>(this)} on {@code value} and
     * returns its result — the GUI bridge's "run this widget's {@code onClick}"
     * ({@link NativeCalls.Context#invoke}). Mirrors {@link #tryAttributeProducer}; tries the
     * value's qualified type name then its bare suffix as the dispatch key (a trait-impl method is
     * registered under the bare type). Throws if no such method resolves, so a misconfigured
     * handler is loud rather than a silent no-op.
     */
    private Object invokeMethod(RecordValue value, String methodName, CompiledModule module) {
        if (value == null || value.typeName() == null) {
            throw new RuntimeCheckException("invoke on a value with no type name", Origin.NONE);
        }
        for (String key : List.of(value.typeName() + "." + methodName,
                bareName(value.typeName()) + "." + methodName)) {
            DispatchResult dr = module.dispatch().resolve(key, List.of(toSymExpr(value)), checker(module));
            if (dr instanceof DispatchResult.Resolved resolved) {
                CompiledModule.CompiledFunction func = module.functions().get(resolved.decl());
                if (func != null && !func.params().isEmpty()) {
                    Environment env = Environment.empty().extend(func.params().get(0).name(), value);
                    return eval(func.body(), env, module);
                }
            }
        }
        throw new RuntimeCheckException(
                "no method '" + methodName + "' on '" + value.typeName() + "'", Origin.NONE);
    }

    /**
     * Resolves the {@link CompiledModule.CompiledFunction} for a trait-impl instance method on
     * {@code value}'s type, or null — the {@code NativeCalls.Context.methodImpl} seam (a code
     * generator READS the body rather than evaluating it, docs/sdf-glsl.md). A trait-impl method
     * compiles to a {@link CompiledModule.CompiledFunction} whose decl is named
     * {@code "<qualified-type>.<method>"} (e.g. {@code "pontif.shape/Sphere.distance"}), so the
     * lookup is a scan of {@link CompiledModule#functions()} by decl-name suffix — the receiver's
     * bare type plus the method name. (Distinct from {@link #invokeMethod}'s dispatch-based path,
     * which resolves an overload against argument types; here we want the definition itself.)
     */
    private CompiledModule.CompiledFunction resolveMethodImpl(
            RecordValue value, String methodName, CompiledModule module) {
        if (value == null || value.typeName() == null) return null;
        String suffix = bareName(value.typeName()) + "." + methodName;   // e.g. "Sphere.distance"
        for (Map.Entry<FunctionDecl, CompiledModule.CompiledFunction> e : module.functions().entrySet()) {
            String n = e.getKey().name();
            if (n.equals(suffix) || n.endsWith("/" + suffix)) return e.getValue();
        }
        return null;
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
        // A metareference round-trips as a SymExpr.DispatchRef carrying its concrete nominal
        // (Dispatch / AlgebraicDispatch) — key-matched against a [Dispatch(…)] sort, and its
        // nominal lets trait-param dispatch (e.g. astOf's Algebraic param) see
        // `AlgebraicDispatch is-a Algebraic`. Caught before the generic RecordValue case.
        if (value instanceof RecordValue r && Metaref.is(r)) {
            return new SymExpr.DispatchRef(
                    Metaref.functionName(r), Metaref.keySorts(r), r.typeName());
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

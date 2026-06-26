package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public sealed interface IrExpr
        permits IrExpr.Lit, IrExpr.Dec, IrExpr.Chr, IrExpr.Str, IrExpr.Bool, IrExpr.Var, IrExpr.SelfRef,
                IrExpr.BinOp, IrExpr.LetIn, IrExpr.Call, IrExpr.DispatchRef,
                IrExpr.Lambda, IrExpr.Apply, IrExpr.Match,
                IrExpr.Record, IrExpr.FieldAccess, IrExpr.MethodCall,
                IrExpr.Iterate, IrExpr.Emit, IrExpr.Cast {

    Origin origin();

    enum Op {
        ADD, MUL, SUB, DIV, MOD, POW,
        LT, LE, GT, GE, EQ, NE, APPROX,
        AND, OR
    }

    static Lit lit(long value) { return new Lit(value, Origin.NONE); }
    static Dec dec(BigDecimal value) { return new Dec(value, Origin.NONE); }
    static Chr chr(int codePoint) { return new Chr(codePoint, Origin.NONE); }
    static Str str(String value) { return new Str(value, Origin.NONE); }
    static Bool bool(boolean value) { return new Bool(value, Origin.NONE); }
    static Var var(String name) { return new Var(name, Origin.NONE); }
    static SelfRef self() { return new SelfRef(Origin.NONE); }
    static BinOp binOp(Op op, IrExpr left, IrExpr right) { return new BinOp(op, left, right, Origin.NONE); }
    static LetIn letIn(String name, IrSort sort, IrExpr value, IrExpr body) { return new LetIn(name, sort, value, body, Origin.NONE); }
    static Call call(String functionName, List<IrExpr> args) { return new Call(functionName, args, Origin.NONE); }
    static Lambda lambda(List<IrParam> params, IrSort returnSort, IrExpr body) { return new Lambda(params, returnSort, body, Origin.NONE); }
    static Apply apply(IrExpr fn, List<IrExpr> args) { return new Apply(fn, args, Origin.NONE); }
    static Match match(IrExpr scrutinee, List<MatchBranch> branches) { return new Match(scrutinee, branches, Origin.NONE); }
    static MatchBranch matchBranch(IrSort pattern, IrExpr result) { return new MatchBranch(pattern, result); }
    static Record record(Map<String, IrExpr> members) { return new Record(null, members, Origin.NONE); }
    static Record record(String typeName, Map<String, IrExpr> members) { return new Record(typeName, members, Origin.NONE); }
    static FieldAccess fieldAccess(IrExpr base, String fieldName) { return new FieldAccess(base, fieldName, Origin.NONE); }

    static Cast cast(IrSort targetSort, IrExpr value) { return new Cast(targetSort, value, Origin.NONE); }

    record Lit(long value, Origin origin) implements IrExpr {}

    /**
     * Explicit coercion — the value-space cast {@code (Type:value)} (general →
     * specific, type on the left; docs/dispatch-unification.md → "Coercion").
     * Pontif's answer to Julia-style implicit promotion: the target is
     * <em>named</em>, so nothing is searched and nothing is ambiguous. A cast
     * resolves a coercion {@code (source sort → target sort)} — conceptually a
     * dispatched call on the one shared engine — and so is NOT desugared onto
     * {@code +}/concat: the conservation ledger must record a coercion, not an
     * empty-string concatenation.
     *
     * <p>Slice 1 supports only the built-in renders to {@code String}
     * (Int/Decimal/Char/Bool/String → String); every other target fails closed.
     * User-defined {@code Type → Type} coercions and refinement-target casts
     * are later slices.
     */
    record Cast(IrSort targetSort, IrExpr value, Origin origin) implements IrExpr {
        public Cast {
            if (targetSort == null) {
                throw new IllegalArgumentException("Cast targetSort must be non-null");
            }
            if (value == null) {
                throw new IllegalArgumentException("Cast value must be non-null");
            }
        }
    }

    /** Decimal literal — arbitrary-precision exact decimal (BigDecimal-backed). */
    record Dec(BigDecimal value, Origin origin) implements IrExpr {
        public Dec {
            if (value == null) {
                throw new IllegalArgumentException("Dec value must be non-null");
            }
        }
    }

    /**
     * Character literal — a Unicode code point (full range, not just the
     * BMP). The fourth scalar; ordered by code point, no arithmetic.
     */
    record Chr(int codePoint, Origin origin) implements IrExpr {
        public Chr {
            if (!Character.isValidCodePoint(codePoint)) {
                throw new IllegalArgumentException(
                        "Chr code point out of Unicode range: " + codePoint);
            }
        }
    }

    /**
     * String literal — the first Char <em>collection</em>. Storage only
     * (native-backed); ordered lexicographically by code point, no arithmetic
     * and no indexing — the stream view is the iteration API (a later slice).
     */
    record Str(String value, Origin origin) implements IrExpr {
        public Str {
            if (value == null) {
                throw new IllegalArgumentException("Str value must be non-null");
            }
        }
    }

    record Bool(boolean value, Origin origin) implements IrExpr {}

    /**
     * Metareference to a dispatch: {@code inc[Int]} reifies the DISPATCH
     * SITE keyed at the given sorts — not a function pointer. Invocation
     * (application: {@code ref(2)}) reruns runtime dispatch over the name's
     * candidates, narrowings intact — it resolves exactly how {@code inc(2)}
     * resolves. Bare function names in value position are a compile error;
     * this is the blessed form.
     */
    record DispatchRef(String functionName, List<IrSort> keySorts, Origin origin)
            implements IrExpr {
        public DispatchRef {
            if (functionName == null || functionName.isEmpty()) {
                throw new IllegalArgumentException("DispatchRef functionName must be non-empty");
            }
            keySorts = List.copyOf(keySorts);
        }
    }

    record Var(String name, Origin origin) implements IrExpr {}

    record SelfRef(Origin origin) implements IrExpr {}

    record BinOp(Op op, IrExpr left, IrExpr right, Origin origin) implements IrExpr {}

    /**
     * Let binding. {@code declaredSort} is the binding's <em>narrowing</em> —
     * the tightest sort the parser could derive for the value (the declared
     * sort only when the value's shape is anonymous; see promotion).
     *
     * <p>{@code claim} carries the user's declared sort verbatim when the
     * binding was written {@code let x:Sort = v} — the claim rule's binding
     * half: a declared sort at a let is a claim made where the binding is
     * made, judged by {@link ConstructionGate} exactly like a constructor
     * argument (provable fit passes clean and the claim is stripped, provable
     * miss is a compile error, genuine overlap keeps the claim and the
     * runtime validates at bind). Null when the let is undeclared or the
     * claim was discharged.
     */
    record LetIn(String name, IrSort declaredSort, IrExpr value, IrExpr body,
                 Origin origin, IrSort claim) implements IrExpr {

        /** Claim-free constructor — undeclared lets and pre-claim passes. */
        public LetIn(String name, IrSort declaredSort, IrExpr value, IrExpr body, Origin origin) {
            this(name, declaredSort, value, body, origin, null);
        }
    }

    record Call(String functionName, List<IrExpr> args, Origin origin) implements IrExpr {
        public Call {
            args = List.copyOf(args);
        }
    }

    record Lambda(List<IrParam> params, IrSort returnSort, IrExpr body, Origin origin) implements IrExpr {
        public Lambda {
            params = List.copyOf(params);
            if (returnSort == null) {
                throw new IllegalArgumentException("Lambda returnSort must be non-null");
            }
            if (body == null) {
                throw new IllegalArgumentException("Lambda body must be non-null");
            }
        }
    }

    record Apply(IrExpr fn, List<IrExpr> args, Origin origin) implements IrExpr {
        public Apply {
            args = List.copyOf(args);
            if (fn == null) {
                throw new IllegalArgumentException("Apply function expression must be non-null");
            }
        }
    }

    record Match(IrExpr scrutinee, List<MatchBranch> branches, Origin origin) implements IrExpr {
        public Match {
            if (scrutinee == null) {
                throw new IllegalArgumentException("Match scrutinee must be non-null");
            }
            branches = List.copyOf(branches);
            if (branches.isEmpty()) {
                throw new IllegalArgumentException("Match must have at least one branch");
            }
        }
    }

    record MatchBranch(IrSort pattern, IrExpr result) {
        public MatchBranch {
            if (pattern == null) {
                throw new IllegalArgumentException("MatchBranch pattern must be non-null");
            }
            if (result == null) {
                throw new IllegalArgumentException("MatchBranch result must be non-null");
            }
        }
    }

    /**
     * Record literal. {@code typeName} carries the nominal struct type
     * when known (e.g., {@code "Point"} for {@code Point(1, 2)}), null
     * for anonymous records (S-expr {@code (record ...)} construction).
     * Used downstream by the dispatch table's trait-fallback rule to
     * identify the concrete type of an argument.
     *
     * <p>{@code runtimeChecks} (field → declared sort) is stamped by
     * {@link ConstructionGate} on exactly the members whose fit against the
     * declared field sort could not be decided at compile time — the runtime
     * validates those at construction (the claim rule's middle verdict:
     * provable fit passes unchecked, provable miss is a compile error,
     * genuine overlap carries a runtime check). Empty for unstamped records.
     */
    record Record(String typeName, Map<String, IrExpr> members,
                  Map<String, IrSort> runtimeChecks, Origin origin) implements IrExpr {
        public Record {
            if (members == null) {
                throw new IllegalArgumentException("Record members must be non-null");
            }
            // Preserve insertion order — record-literal field iteration order is
            // load-bearing for destructure desugar, error messages, and Truffle
            // lowering. Map.copyOf would silently strip the order.
            members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
            runtimeChecks = runtimeChecks == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(runtimeChecks));
        }

        /** Unstamped constructor — the shape every pass builds; the gate stamps. */
        public Record(String typeName, Map<String, IrExpr> members, Origin origin) {
            this(typeName, members, Map.of(), origin);
        }

        /** Backward-compat constructor used by tests and S-expr parser. */
        public Record(Map<String, IrExpr> members, Origin origin) {
            this(null, members, Map.of(), origin);
        }
    }

    record FieldAccess(IrExpr base, String fieldName, Origin origin) implements IrExpr {
        public FieldAccess {
            if (base == null) {
                throw new IllegalArgumentException("FieldAccess base must be non-null");
            }
            if (fieldName == null || fieldName.isEmpty()) {
                throw new IllegalArgumentException("FieldAccess field name must be non-empty");
            }
        }
    }

    /**
     * An unresolved instance-method call {@code receiver.methodName(args)}.
     * <strong>Transient:</strong> the parser emits it (it cannot know the
     * receiver's type or whether the method is declared yet), and
     * {@link MethodResolver} eliminates it — rewriting to
     * {@code Call("Type.methodName", [receiver, ...args])} — after every
     * declaration is registered, so method resolution is order-independent
     * (forward references, self- and mutual recursion all work). No phase past
     * {@code MethodResolver} should ever see one.
     */
    record MethodCall(IrExpr receiver, String methodName, List<IrExpr> args, Origin origin) implements IrExpr {
        public MethodCall {
            if (receiver == null) {
                throw new IllegalArgumentException("MethodCall receiver must be non-null");
            }
            if (methodName == null || methodName.isEmpty()) {
                throw new IllegalArgumentException("MethodCall method name must be non-empty");
            }
            args = List.copyOf(args);
        }
    }

    /**
     * The iteration construct (docs/iteration.md): drive a total per-element
     * matcher over a read-stream {@code source}, the body writing to named
     * write-streams ({@code outputs}); the result is the tuple/record of their
     * sealed values. No surface syntax yet — hand-constructed in tests (slice 1).
     *
     * <p>One {@code IrExpr} variant; {@link OutputSpec}/{@link Arm}/{@link Write}
     * are plain records (not variants), so an exhaustive {@code IrExpr} switch
     * needs a single new case. The sub-expressions a pass must recurse into are
     * {@code source} and each {@code coSources} entry, each {@code OutputSpec.init},
     * and each {@code Write.key} / {@code Write.value}; the patterns are
     * {@code Arm.pattern} sorts.
     *
     * <p><b>{@code coSources}</b> are additional sources walked in <em>lockstep</em>
     * with {@code source} — the zip / fan-in shape (docs/stream-war.md §3). When
     * non-empty, {@code element} binds, per step, to a positional tuple of the i-th
     * value from {@code source} then each co-source (stopping at the shortest), which
     * the body destructures. Empty for the ordinary single-source iteration.
     */
    record Iterate(IrExpr source, List<IrExpr> coSources, String element,
                   List<OutputSpec> outputs, List<Arm> arms, Origin origin) implements IrExpr {
        public Iterate {
            if (source == null) {
                throw new IllegalArgumentException("Iterate source must be non-null");
            }
            if (element == null || element.isEmpty()) {
                throw new IllegalArgumentException("Iterate element binding must be non-empty");
            }
            coSources = List.copyOf(coSources);
            outputs = List.copyOf(outputs);
            arms = List.copyOf(arms);
        }

        /** Single-source iteration (no zip) — the common case; co-sources empty. */
        public Iterate(IrExpr source, String element, List<OutputSpec> outputs,
                       List<Arm> arms, Origin origin) {
            this(source, List.of(), element, outputs, arms, origin);
        }
    }

    /**
     * The event-emission statement (docs/events.md): {@code emit EVENT  BODY}.
     * Statement-shaped (like {@code let}): evaluate {@code event} for its routing
     * side-effect — the runtime routes it <b>by event type</b> to its conduit —
     * discard the (Nothing) result, then continue with {@code body} (the rest of the
     * enclosing expression). <b>Write-only / uninspectable:</b> the routing result is
     * never bound, so emission is observationally invisible to the emitting code (the
     * purity membrane); the whole node's value is {@code body}'s.
     *
     * <p>A dedicated node — not a {@code Call} — precisely so it bypasses
     * function-name resolution: {@code emit} is a reserved statement keyword, not an
     * imported symbol, so a routing {@code Call} would be an "unknown function" to the
     * sort checker and unresolvable under module scoping. Passes recurse into
     * {@code event} and {@code body}.
     *
     * <p>Slice 1b (output IO, docs/events.md §Slices): the only conduits are the
     * builtin {@code StdOut}/{@code StdErr} events, routed via {@link NativeFunctions}
     * by the event's type name; the stateful conduit fold, the {@code triggered}/
     * {@code next} contracts, and the mailbox boundary land in later slices.
     */
    record Emit(IrExpr event, IrExpr body, Origin origin) implements IrExpr {
        public Emit {
            if (event == null) {
                throw new IllegalArgumentException("Emit event must be non-null");
            }
            if (body == null) {
                throw new IllegalArgumentException("Emit body must be non-null");
            }
        }
    }

    /** Output-stream kinds (docs/iteration.md §2.4). Slice-1 eval: STREAM + ACCUMULATOR. */
    enum OutputKind { STREAM, KEYED, ACCUMULATOR, REWRITE }

    /**
     * A declared output stream: a {@code name} in the iteration's scope, a
     * {@code kind} that supplies the write effect, and (for ACCUMULATOR) an
     * {@code init} value; null for the kinds that start empty.
     */
    record OutputSpec(String name, OutputKind kind, IrExpr init) {
        public OutputSpec {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("OutputSpec name must be non-empty");
            }
            if (kind == null) {
                throw new IllegalArgumentException("OutputSpec kind must be non-null");
            }
        }
    }

    /** One arm of the per-element matcher: a {@code pattern} and its writes (empty = no-op). */
    record Arm(IrSort pattern, List<Write> writes) {
        public Arm {
            if (pattern == null) {
                throw new IllegalArgumentException("Arm pattern must be non-null");
            }
            writes = List.copyOf(writes);
        }
    }

    /**
     * A single write command — *send {@code value} to the output {@code output}*.
     * The output's {@link OutputKind} supplies the effect (append / route-by-key /
     * revise). {@code key} is non-null only for a KEYED target.
     */
    record Write(String output, IrExpr key, IrExpr value) {
        /**
         * Reserved output name for a <em>fan</em> write: {@code value} is a tuple
         * whose position <i>i</i> is routed to output <i>i</i> (the multi-channel
         * synthesis-fragment return, docs/stream-war.md §3). Evaluated once, then
         * distributed — so the fragment runs a single time per element.
         */
        public static final String FAN = "*";

        /**
         * Reserved output name for a <em>stop</em> write: when this arm matches, the
         * source-driven iteration halts after sealing what's been emitted so far — the
         * <b>stop disposition</b> (docs/stream-war.md §3, takeWhile). The element that
         * triggered the stop is NOT emitted; {@code value} is ignored. This is the
         * source-driven dual of the generator's domain-refinement halt (§7.9): a guard
         * arm emits while the element is in-domain, the catch-all stop arm fires at the
         * first out-of-domain element.
         */
        public static final String STOP = "!stop";

        public Write {
            if (output == null || output.isEmpty()) {
                throw new IllegalArgumentException("Write output must be non-empty");
            }
            if (value == null) {
                throw new IllegalArgumentException("Write value must be non-null");
            }
        }
    }
}

package sibarum.pontif.parser;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.InferenceContext;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.ir.NarrowingInference;
import sibarum.pontif.predicates.ComplementResult;
import sibarum.pontif.predicates.PredicateArithmetic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive-descent parser for the alt syntax — see {@code docs/alternative-syntax.ptf}.
 *
 * <p>Load-bearing principles the parser enforces:
 * <ol>
 *   <li><b>Brackets wrap sorts.</b> {@code [...]} is the universal sort delimiter.
 *   <li><b>Colon narrows.</b> {@code :} is "narrows to" — param sorts, refinement
 *       predicates, struct field constraints, function return types.
 *   <li><b>{@code @} is the principal subject</b> of the enclosing refinement
 *       (value being refined / scrutinee). Each refinement binds its own
 *       {@code @}; nested refinements shadow.
 *   <li><b>{@code |}/{@code &}/{@code !}</b> are the logical operators at every
 *       level. There are no {@code &&}/{@code ||} — bitwise doesn't exist.
 *   <li><b>Implicit {@code @==EXPR}</b> sugar applies when a refinement
 *       predicate has no top-level comparison.
 *   <li><b>Base-inference</b>: {@code [pred]} (no base) is legal where context
 *       provides exactly one base sort (e.g., a match arm whose scrutinee has a
 *       single inferred base). Required to be explicit otherwise.
 *   <li><b>Module lists use {@code X.{a, b, c}}</b> — brackets are reserved for sorts.
 *   <li><b>Match is total</b> — every match must exhaustively cover its scrutinee's
 *       sort, proved at compile time.
 * </ol>
 *
 * <p>Built in stages (vertical slices). The current slice's scope is documented
 * at each method.
 */
public final class AltParser {

    /**
     * Reserved sentinel name for anonymous positional aggregates (tuples) —
     * the positional sibling of the {@code "_record"} sentinel used for
     * anonymous by-name records. Tuple members are keyed positionally
     * ({@code _0 .. _n}); the name marks the aggregate as a tuple for display
     * and keeps tuples from being mistaken for a declared struct.
     */
    static final String TUPLE_SENTINEL = "_tuple";

    /**
     * Keywords are recognized by text at parse time, not by token kind.
     * Public because tooling (the playground's syntax highlighter) consumes
     * this set as the single source of truth for the keyword vocabulary —
     * a new keyword added here highlights everywhere without a second list
     * to update.
     */
    public static final Set<String> KEYWORDS = Set.of(
            "module", "requires", "exports",
            "function", "method", "struct", "let", "cast",
            "assign", "trait", "Type",
            "match", "proof",
            "true", "false");

    /** Standard precedence for binary operators (higher = tighter). */
    private static int precedence(String op) {
        return switch (op) {
            case "|" -> 1;                              // logical/union OR
            case "&" -> 2;                              // logical/intersection AND
            case "==", "!=", "~=" -> 3;
            case "<", "<=", ">", ">=" -> 4;
            case "+", "-" -> 5;
            case "*", "/", "%" -> 6;
            case "^" -> 7;                              // power — binds tighter than * (left-assoc for now)
            default -> -1;
        };
    }

    private static IrExpr.Op opKind(String op) {
        return switch (op) {
            case "+" -> IrExpr.Op.ADD;
            case "-" -> IrExpr.Op.SUB;
            case "*" -> IrExpr.Op.MUL;
            case "/" -> IrExpr.Op.DIV;
            case "%" -> IrExpr.Op.MOD;
            case "^" -> IrExpr.Op.POW;
            case "<" -> IrExpr.Op.LT;
            case "<=" -> IrExpr.Op.LE;
            case ">" -> IrExpr.Op.GT;
            case ">=" -> IrExpr.Op.GE;
            case "==" -> IrExpr.Op.EQ;
            case "!=" -> IrExpr.Op.NE;
            case "~=" -> IrExpr.Op.APPROX;
            case "&" -> IrExpr.Op.AND;
            case "|" -> IrExpr.Op.OR;
            default -> throw new IllegalArgumentException("Unknown operator: " + op);
        };
    }


    private final List<AltToken> tokens;
    private int pos;
    /** Counter for synthesizing fresh names — used by the structural-destructure desugar. */
    private int syntheticCounter = 0;

    /**
     * Scope of names → declared sort, used to infer contextual base sorts for
     * match-arm patterns. Populated from function/method/lambda params; cleared
     * on body exit.
     */
    private final Map<String, IrSort> currentScope = new LinkedHashMap<>();

    /**
     * Stack of "expected base sort name" frames pushed by {@link #parseMatch}.
     * When a bracket-form refinement omits the base (contextual form
     * {@code [pred]}), the parser pops the stack top to use as the base.
     * Empty-string entries mark frames where no base could be inferred — the
     * contextual form is then rejected with a helpful error.
     */
    private final Deque<String> contextualBaseStack = new ArrayDeque<>();

    /**
     * Structs declared so far in the current parse. Populated by {@code struct
     * Name(...)}; consulted by struct-pattern parsing to resolve bare field
     * idents (e.g., {@code [Point(x, y)]}) to declared field sorts. Required
     * to be populated BEFORE the struct is used — slice-5 restriction.
     */
    private final Map<String, IrSort.Structural> declaredStructs = new LinkedHashMap<>();

    /**
     * Struct-pattern members that were given as positional <em>literals</em>
     * ({@code [Ternion(z, 0, w)]} — constrain the field, bind nothing). Keyed by
     * the pattern's identity; the destructure desugar skips these in its
     * let-wrap so the field name isn't silently bound (which could shadow an
     * outer binding the user never asked to shadow).
     */
    private final Map<IrSort, java.util.Set<String>> literalConstrainedFields =
            new java.util.IdentityHashMap<>();

    /** Extra declarations emitted by a top-level destructuring let (one per bound field). */
    private final List<IrStmt> pendingTopLevelDecls = new ArrayList<>();

    /**
     * Positional rename binders per struct pattern: field name → binder name.
     * A bare ident clause that is NOT a declared field name binds the field at
     * its clause position under the given name ({@code [Ternion(first, second,
     * third)]}); idents that ARE field names stay name-keyed as before.
     */
    private final Map<IrSort, Map<String, String>> destructureRenames =
            new java.util.IdentityHashMap<>();

    /**
     * Identity set of {@link IrSort.Structural} patterns whose struct was NOT in
     * {@link #declaredStructs} at parse time (cross-module / imported). Their
     * slots are keyed positionally ({@code _0.._n}) with binder/discard roles
     * encoded in the slot sorts via {@link #DEFERRED_BIND}/{@link #DEFERRED_SKIP};
     * the post-link {@code DestructureResolver} maps slots to declared field
     * names and runs the arity-total check. Used here only to skip the
     * <em>parse-time</em> binding desugar for these patterns (the resolver does
     * it post-link, when field names are known).
     */
    private final java.util.Set<IrSort> deferredStructPatterns =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Slot-sort encodings for a DEFERRED positional struct pattern (see
     * {@link #deferredStructPatterns}). A slot bound to binder {@code x} carries
     * sort {@code Named("_$bind$x")}; a {@code _} discard carries
     * {@code Named("_$skip$")}. {@code DestructureResolver} decodes them after
     * the struct's declared fields are known. The constants live in
     * {@code pontif-ir} (which the parser depends on) so both sides share one
     * source of truth.
     */
    static final String DEFERRED_BIND = sibarum.pontif.ir.DestructureResolver.DEFERRED_BIND;
    static final String DEFERRED_SKIP = sibarum.pontif.ir.DestructureResolver.DEFERRED_SKIP;

    /**
     * True while parsing a destructure/match <em>pattern</em> (as opposed to a
     * type annotation). Tuples — unlike structs — have no declared type to
     * disambiguate sort-elements from binder-elements, so the tuple-sort parser
     * ({@link #parseTupleSortBody}) consults this flag: when set, {@code (a, b)}
     * elements are positional binders / {@code _} discards; when clear, they are
     * component sorts. Set around the pattern-parse calls in {@link #parseMatch},
     * {@link #parseDestructuringLetExpr}, and {@link #parseDestructuringLetTop}.
     */
    private boolean parsingTuplePattern = false;

    /**
     * Top-level let-bindings declared so far, keyed by their (possibly dotted)
     * name. Maps to the binding's inferred sort. Two uses: (1) bare references
     * to a let name in expression position get rewritten to a 0-arg
     * {@link IrExpr.Call} so dispatch can find the binding; (2) feeds
     * {@link #inferMaximalSort} when a later let references an earlier one.
     */
    private final Map<String, IrSort> declaredTopLevelLets = new LinkedHashMap<>();

    /**
     * Latest declared return sort for each function/method name (most-recent
     * overload wins). Consulted by {@link #inferMaximalSort} for {@link
     * IrExpr.Call} expressions, where the per-call narrowing isn't computed yet
     * (that's the in-progress "Dispatch as the star" priority work — see
     * TODO.md). For now, inference uses the declared return sort as a lossy
     * upper bound on the call's actual narrowing.
     */
    private final Map<String, IrSort> declaredFunctionReturns = new LinkedHashMap<>();

    /**
     * Names of declared 0-arg functions (functions whose param list is empty).
     * Treated equivalently to top-level lets at bare-access sites — both are
     * 0-arg dispatch entries, both auto-Call on unqualified or dotted bare
     * reference. Populated only for {@code function} decls; methods always
     * have at least the {@code self} param.
     */
    private final Set<String> declaredZeroArgFunctions = new java.util.HashSet<>();

    /**
     * Names declared as reusable sort aliases via {@code let NAME:Type[...]}.
     * Tracked so the parse-time base-sort-mismatch check defers on an alias-typed
     * let (the alias's real base is only known after {@code AliasResolver} runs).
     */
    private final Set<String> declaredSortAliases = new java.util.HashSet<>();

    /**
     * Names declared as traits via {@code trait NAME{...}}. Tracked so the
     * base-sort-mismatch check accepts a struct→trait upcast (`let h:Trait = s`):
     * a trait coerces implicitly in both directions because its attributes are
     * computed projections (information-conserving). Satisfaction is enforced by
     * SortChecker (the impl) and dispatch (only satisfiers resolve).
     */
    private final Set<String> declaredTraits = new java.util.HashSet<>();

    public AltParser(List<AltToken> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    public static IrModule parseModule(String src, String source) throws ParseException {
        AltParser p = new AltParser(new AltLexer(src, source).tokenize());
        return p.parseModule();
    }

    // --- Token cursor ---

    private AltToken peek() {
        return tokens.get(pos);
    }

    private AltToken peek(int offset) {
        int idx = pos + offset;
        if (idx >= tokens.size()) return tokens.get(tokens.size() - 1);
        return tokens.get(idx);
    }

    private AltToken consume() {
        return tokens.get(pos++);
    }

    private AltToken expect(AltToken.Kind kind) throws ParseException {
        AltToken t = peek();
        if (t.kind() != kind) {
            throw new ParseException(
                    "Expected " + kind + " but got " + t.kind() + " ('" + t.text() + "')",
                    t.origin());
        }
        return consume();
    }

    private AltToken expectKeyword(String text) throws ParseException {
        AltToken t = expect(AltToken.Kind.IDENT);
        if (!t.text().equals(text)) {
            throw new ParseException(
                    "Expected keyword '" + text + "' but got '" + t.text() + "'",
                    t.origin());
        }
        return t;
    }

    private boolean checkKeyword(String text) {
        AltToken t = peek();
        return t.kind() == AltToken.Kind.IDENT && t.text().equals(text);
    }

    // --- Top-level ---

    public IrModule parseModule() throws ParseException {
        String moduleName = "_anonymous";
        if (checkKeyword("module")) {
            consume();
            moduleName = parseDottedName();
        }
        List<IrStmt> stmts = new ArrayList<>();
        while (peek().kind() != AltToken.Kind.EOF && !isMainExpressionStart()) {
            stmts.add(parseTopLevelDecl());
            // A top-level destructuring let emits one decl per bound field on
            // top of the decl returned above — drain them in order.
            if (!pendingTopLevelDecls.isEmpty()) {
                stmts.addAll(pendingTopLevelDecls);
                pendingTopLevelDecls.clear();
            }
        }
        IrExpr main;
        if (peek().kind() == AltToken.Kind.EOF) {
            // No explicit main — use a benign placeholder.
            main = new IrExpr.Lit(0, Origin.NONE);
        } else {
            main = parseExpr();
            if (peek().kind() != AltToken.Kind.EOF) {
                throw new ParseException(
                        "Trailing tokens after main expression; got " + peek().kind() + " '" + peek().text() + "'",
                        peek().origin());
            }
        }
        return new IrModule(moduleName, stmts, main);
    }

    /** Heuristic: a top-level construct starts with a known decl keyword. */
    private boolean isMainExpressionStart() {
        AltToken t = peek();
        if (t.kind() != AltToken.Kind.IDENT) return true;
        return !Set.of("module", "requires", "exports",
                "function", "method", "struct", "let", "trait", "cast", "assign", "proof").contains(t.text());
    }

    private String parseDottedName() throws ParseException {
        AltToken first = expect(AltToken.Kind.IDENT);
        StringBuilder sb = new StringBuilder(first.text());
        while (peek().kind() == AltToken.Kind.DOT && peek(1).kind() == AltToken.Kind.IDENT) {
            consume();  // DOT
            sb.append('.').append(consume().text());
        }
        return sb.toString();
    }

    /**
     * Parses a declaration name. Three shapes:
     * <ul>
     *   <li>dotted identifier — {@code factorial}, {@code Point.zero};</li>
     *   <li>a <b>bare operator</b> — {@code function +(l, r)} registers the
     *       operator as a free generic under the bare key {@code +}. Only
     *       {@link #OVERLOADABLE_OPS overloadable operators} are valid as
     *       names ({@code &}/{@code |} and unknown ops are rejected here);</li>
     *   <li>a trailing {@code .OP} segment — {@code Point.+} registers under
     *       the dispatch key {@code Point.+} (the legacy method-style form,
     *       kept as a routing fallback).</li>
     * </ul>
     */
    private String parseDeclarationName() throws ParseException {
        if (peek().kind() == AltToken.Kind.OP) {
            AltToken opTok = consume();
            if (!OVERLOADABLE_OPS.contains(opTok.text())) {
                throw new ParseException(
                        "'" + opTok.text() + "' is not an overloadable operator "
                                + "(overloadable: + - * / % < <= > >= == !=)",
                        opTok.origin());
            }
            return opTok.text();
        }
        String name = parseDottedName();
        if (peek().kind() == AltToken.Kind.DOT && peek(1).kind() == AltToken.Kind.OP) {
            consume();  // DOT
            AltToken opTok = consume();
            return name + "." + opTok.text();
        }
        return name;
    }

    private IrStmt parseTopLevelDecl() throws ParseException {
        AltToken head = peek();
        if (head.kind() != AltToken.Kind.IDENT) {
            throw new ParseException(
                    "Expected a top-level declaration (function / struct / let / requires / "
                            + "exports / method); got " + head.kind() + " '" + head.text() + "'",
                    head.origin());
        }
        return switch (head.text()) {
            case "requires" -> parseRequires();
            case "exports"  -> parseExports();
            case "function" -> parseFunction();
            case "struct"   -> parseStruct();
            case "method"   -> parseMethod();
            case "cast"     -> parseCoercion();
            case "let"      -> parseLet();
            case "trait"    -> parseTrait();
            case "assign"   -> parseAssign();
            case "proof"    -> parseProof();
            default -> throw new ParseException(
                    "Unknown top-level keyword '" + head.text() + "'",
                    head.origin());
        };
    }

    // --- Declarations: requires / exports ---
    // Form: `requires X.{name, name, ...}` and `exports @.{name, name, ...}`.
    // Lower to structured IrStmt.Requires/Exports; the module loader/linker and
    // name resolver consume them. Inert when a single file compiles on its own.

    private IrStmt parseRequires() throws ParseException {
        AltToken start = expectKeyword("requires");
        String target = parseDottedName();
        List<IrStmt.RequireEntry> entries = parseDotBraceEntryList();
        AltToken last = tokens.get(pos - 1);
        return new IrStmt.Requires(target, entries, start.spanTo(last));
    }

    private IrStmt parseExports() throws ParseException {
        AltToken start = expectKeyword("exports");
        boolean self = false;
        if (peek().kind() == AltToken.Kind.AT) {
            consume();
            self = true;
        } else {
            // A non-`@` qualifier (re-export of another module) — parse and keep
            // the names; the qualifier itself is unused until re-exports land.
            parseDottedName();
        }
        List<IrStmt.RequireEntry> entries = parseDotBraceEntryList();
        List<String> names = new ArrayList<>(entries.size());
        for (IrStmt.RequireEntry entry : entries) {
            if (!entry.remoteName().equals(entry.localName())) {
                // Exports rename (public name != internal name) needs a
                // public→internal mapping in the export tables — parked.
                throw new ParseException(
                        "Exports rename ('" + entry.remoteName() + " -> "
                                + entry.localName() + "') is not yet supported — "
                                + "an exports list takes plain names for now",
                        start.origin());
            }
            names.add(entry.localName());
        }
        AltToken last = tokens.get(pos - 1);
        return new IrStmt.Exports(names, self, start.spanTo(last));
    }

    /**
     * Parses the {@code .{entry, entry, ...}} decomposition tail. Each entry is
     * {@code name} (shorthand: same name in the receiving context) or
     * {@code name -> alias} (rename: LHS is the name where the symbol already
     * lives, RHS is its name here — the arrow reads "becomes", uniformly with
     * match arms and function bodies).
     */
    private List<IrStmt.RequireEntry> parseDotBraceEntryList() throws ParseException {
        expect(AltToken.Kind.DOT);
        expect(AltToken.Kind.LBRACE);
        List<IrStmt.RequireEntry> entries = new ArrayList<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RBRACE) {
            if (!first) {
                expect(AltToken.Kind.COMMA);
            }
            String remote = expect(AltToken.Kind.IDENT).text();
            String local = remote;
            if (peek().kind() == AltToken.Kind.ARROW) {
                consume();
                local = expect(AltToken.Kind.IDENT).text();
            }
            entries.add(new IrStmt.RequireEntry(remote, local));
            first = false;
        }
        expect(AltToken.Kind.RBRACE);
        return entries;
    }

    /**
     * Expression-level by-name decomposition let: {@code let SOURCE.{entry, …}
     * BODY} — the value consumer of the {@code .{}} payload ({@code requires}
     * and {@code exports} are the module consumers). Each entry is an
     * abbreviated let: {@code let p.{name -> u, age} BODY} desugars to
     * {@code let u = p.name let age = p.age BODY}. By-name reads are
     * <em>projections</em>, so partial is honest — there is no totality rule
     * (that's positional-only) — but an unknown key is a lie, rejected when the
     * source's sort is statically known. Positional keys ({@code _0 …}) are
     * rejected: tuples are destructure-only.
     */
    private IrExpr parseDictDecompositionLetExpr(AltToken start, AltToken sourceTok)
            throws ParseException {
        List<IrStmt.RequireEntry> entries = parseDotBraceEntryList();
        if (entries.isEmpty()) {
            throw new ParseException(
                    "Empty decomposition '.{}' binds nothing", start.origin());
        }
        String source = sourceTok.text();
        boolean scoped = currentScope.containsKey(source);
        IrSort sourceSort = scoped
                ? currentScope.get(source)
                : declaredTopLevelLets.containsKey(source)
                        ? declaredTopLevelLets.get(source)
                        : declaredFunctionReturns.get(source);
        // Params/locals are frame Vars; top-level lets and 0-arg functions
        // resolve through 0-arg dispatch Calls (the same routing bare
        // references get in rewriteTopLevelLetAccess).
        IrExpr sourceRef = scoped
                ? new IrExpr.Var(source, sourceTok.origin())
                : (declaredTopLevelLets.containsKey(source)
                        || declaredFunctionReturns.containsKey(source))
                        ? new IrExpr.Call(source, List.of(), sourceTok.origin())
                        : new IrExpr.Var(source, sourceTok.origin());
        validateDecompositionEntries(entries, sourceSort, source, start);

        // Bind the locals for body parsing; restore the scope afterwards.
        Map<String, IrSort> shadowed = new LinkedHashMap<>();
        java.util.Set<String> introduced = new java.util.LinkedHashSet<>();
        for (IrStmt.RequireEntry entry : entries) {
            if (currentScope.containsKey(entry.localName())) {
                shadowed.put(entry.localName(), currentScope.get(entry.localName()));
            } else {
                introduced.add(entry.localName());
            }
            currentScope.put(entry.localName(), memberSortFor(sourceSort, entry.remoteName()));
        }
        IrExpr body;
        try {
            body = parseExpr();
        } finally {
            for (IrStmt.RequireEntry entry : entries) {
                if (introduced.contains(entry.localName())) {
                    currentScope.remove(entry.localName());
                } else {
                    currentScope.put(entry.localName(), shadowed.get(entry.localName()));
                }
            }
        }
        // Wrap in reverse so the first entry becomes the outermost let.
        IrExpr result = body;
        for (int i = entries.size() - 1; i >= 0; i--) {
            IrStmt.RequireEntry entry = entries.get(i);
            result = new IrExpr.LetIn(
                    entry.localName(),
                    memberSortFor(sourceSort, entry.remoteName()),
                    new IrExpr.FieldAccess(sourceRef, entry.remoteName(), start.origin()),
                    result,
                    start.origin());
        }
        return result;
    }

    /**
     * Top-level by-name decomposition let: {@code let SOURCE.{entry, …}} — one
     * 0-arg accessor declaration per binder reading {@code SOURCE.key} (the
     * by-name twin of the positional top-level destructure; no match wrapper
     * needed — a by-name projection is honest-partial, and key existence is the
     * only obligation, checked statically when the source's sort is known).
     */
    private IrStmt parseDictDecompositionLetTop(AltToken start, String source)
            throws ParseException {
        List<IrStmt.RequireEntry> entries = parseDotBraceEntryList();
        if (entries.isEmpty()) {
            throw new ParseException(
                    "Empty decomposition '.{}' binds nothing", start.origin());
        }
        IrSort sourceSort = declaredTopLevelLets.containsKey(source)
                ? declaredTopLevelLets.get(source)
                : declaredFunctionReturns.get(source);
        if (sourceSort == null) {
            throw new ParseException(
                    "Decomposition source '" + source
                            + "' is not a declared let or 0-arg function",
                    start.origin());
        }
        validateDecompositionEntries(entries, sourceSort, source, start);
        IrStmt first = null;
        for (IrStmt.RequireEntry entry : entries) {
            IrSort memberSort = memberSortFor(sourceSort, entry.remoteName());
            IrExpr accessor = new IrExpr.FieldAccess(
                    new IrExpr.Call(source, List.of(), start.origin()),
                    entry.remoteName(), start.origin());
            declaredTopLevelLets.put(entry.localName(), memberSort);
            IrStmt decl = new IrStmt.FunctionDecl(
                    entry.localName(), List.of(), memberSort, accessor, start.origin(), true);
            if (first == null) {
                first = decl;
            } else {
                pendingTopLevelDecls.add(decl);
            }
        }
        return first;
    }

    /** The source's member sort for {@code key}, or the {@code "_"} placeholder. */
    private static IrSort memberSortFor(IrSort sourceSort, String key) {
        if (sourceSort instanceof IrSort.Structural ss) {
            IrSort member = ss.members().get(key);
            if (member != null) return member;
        }
        return IrSort.named("_");
    }

    /**
     * Honesty checks shared by both decomposition-let levels: positional keys
     * (and tuple sources) are destructure-only; an unknown key against a
     * statically-known structural source is a lie; duplicate local binders
     * collide.
     */
    private void validateDecompositionEntries(
            List<IrStmt.RequireEntry> entries, IrSort sourceSort, String source, AltToken start)
            throws ParseException {
        boolean tupleSource = sourceSort instanceof IrSort.Structural ss
                && TUPLE_SENTINEL.equals(ss.name());
        java.util.Set<String> locals = new java.util.HashSet<>();
        for (IrStmt.RequireEntry entry : entries) {
            if (tupleSource || isPositionalKey(entry.remoteName())) {
                throw new ParseException(
                        "Tuple components are destructure-only — use a positional pattern "
                                + "`let [(a, _, ...)] = " + source
                                + "` instead of by-name decomposition",
                        start.origin());
            }
            if (sourceSort instanceof IrSort.Structural ss
                    && !ss.members().containsKey(entry.remoteName())) {
                throw new ParseException(
                        "'" + source + "' has no member '" + entry.remoteName()
                                + "'; available: " + ss.members().keySet(),
                        start.origin());
            }
            if (!locals.add(entry.localName())) {
                throw new ParseException(
                        "Duplicate binder '" + entry.localName() + "' in decomposition",
                        start.origin());
            }
        }
    }

    // --- Function declarations ---
    // Form: `function NAME(PARAMS):RETURN_SORT [-> BODY]`
    //   - NAME may be dotted (e.g., `Point.manhattan`) — the whole dotted form
    //     becomes the dispatch identifier.
    //   - PARAMS is a comma-separated list of `name:Sort`.
    //   - If `-> BODY` is present, BODY is used directly.
    //   - If absent, body synthesis is attempted from the return sort: a return
    //     of shape `[Sort:@==EXPR]` (which is what the implicit @==EXPR sugar
    //     produces) yields BODY = EXPR. A return that doesn't pin a value
    //     (a plain base/struct sort, a range like `[Int:@>=0]`, or a
    //     self-referential `@==EXPR(@)`) can't synthesize a body and is a
    //     hard error at the declaration — see specOnlyWithoutSynthesis.

    private IrStmt parseFunction() throws ParseException {
        AltToken start = expectKeyword("function");
        String name = parseDeclarationName();
        // Optional `[type E, …]` type-parameter slot (docs/type-parameters.md
        // §2.1), after the name and before the value params. A bare `[` here is
        // the slot; value params follow in `(…)`.
        Map<String, IrSort> typeParams = peek().kind() == AltToken.Kind.LBRACKET
                ? parseTypeParamSlot()
                : new LinkedHashMap<>();
        expect(AltToken.Kind.LPAREN);
        List<IrParam> params = parseParamList(AltToken.Kind.RPAREN);
        expect(AltToken.Kind.RPAREN);
        List<ParamDestructure> destrs = drainParamDestructures();
        // Inline destructuring (docs/type-parameters.md §2.4): a bare unknown name
        // appearing as a TYPE ARGUMENT in a param sort (`b:Box[T]`) introduces a
        // type variable, merged into the slot params so it scopes identically.
        // (A top-level param name like `y:E` is NOT collected — an unknown sort
        // there is a typo, not a binder.)
        for (IrParam p : params) collectInlineTypeVars(p.sort(), typeParams);
        // A bare-operator function is a binary operator — exactly two operands
        // (left, right). The legacy `Type.op` form is naturally binary too
        // (receiver + one param), so this only guards the new bare form.
        if (OVERLOADABLE_OPS.contains(name) && params.size() != 2) {
            throw new ParseException(
                    "operator '" + name + "' must take exactly 2 parameters (left, right); got "
                            + params.size(),
                    start.origin());
        }
        expect(AltToken.Kind.COLON);
        IrSort returnSort = parseSort();

        if (peek().kind() == AltToken.Kind.ARROW) {
            consume();
            // Push function params into scope so match arms can infer contextual
            // base from `match paramName`. Body parses inside this scope.
            Map<String, IrSort> savedScope = new LinkedHashMap<>(currentScope);
            currentScope.clear();
            for (IrParam p : params) currentScope.put(p.name(), p.sort());
            bindParamDestructures(destrs);
            try {
                IrExpr body = wrapParamDestructures(parseExpr(), destrs);
                declaredFunctionReturns.put(name, returnSort);
                if (params.isEmpty()) declaredZeroArgFunctions.add(name);
                return new IrStmt.FunctionDecl(
                        name, params, returnSort, body, start.origin(), false, typeParams);
            } finally {
                currentScope.clear();
                currentScope.putAll(savedScope);
            }
        }
        // No body — synthesis requires the explicit `;` directive.
        if (peek().kind() == AltToken.Kind.SEMICOLON) {
            consume();
            IrExpr derived = tryDeriveBodyFromReturnSort(returnSort);
            if (derived != null) {
                // The synthesized body references destructured params (S4), so
                // wrap it in their `let local = param.field` bindings.
                IrExpr body = wrapParamDestructures(derived, destrs);
                IrSort effReturn = effectiveSynthesizedReturn(derived, returnSort);
                declaredFunctionReturns.put(name, effReturn);
                if (params.isEmpty()) declaredZeroArgFunctions.add(name);
                return new IrStmt.FunctionDecl(
                        name, params, effReturn, body, start.origin(), false, typeParams);
            }
            throw specOnlyWithoutSynthesis("function", name, returnSort, start.origin());
        }
        throw new ParseException(
                "function '" + name + "' needs a body ('-> expr') or a synthesis "
                        + "directive (';')",
                peek().origin());
    }

    /**
     * Parses a user-defined coercion: {@code cast Target:(name:Source) -> body}.
     * Target-first (mirroring the cast invocation {@code (Target:value)}), then a
     * single paren'd source binder, then {@code -> body}. The body parses with the
     * binder in scope (so it can read the source value's fields / match on it,
     * exactly as a function body sees its params). Only structural malformations are
     * rejected here; the semantic rules (no primitive↔primitive, orphan, one-per-pair)
     * are {@code CoercionCheck}'s job. The target may be a refinement sort
     * ({@code cast [Int:@>0]:(n:Int) -> …}).
     */
    private IrStmt parseCoercion() throws ParseException {
        AltToken start = expectKeyword("cast");
        IrSort targetSort = parseSort();
        expect(AltToken.Kind.COLON);
        expect(AltToken.Kind.LPAREN);
        AltToken paramName = expect(AltToken.Kind.IDENT);
        expect(AltToken.Kind.COLON);
        IrSort sourceSort = parseSort();
        if (peek().kind() == AltToken.Kind.COMMA) {
            throw new ParseException(
                    "a coercion takes exactly one source binder — `cast Target:(name:Source) -> …`",
                    peek().origin());
        }
        expect(AltToken.Kind.RPAREN);
        expect(AltToken.Kind.ARROW);
        // Push the binder into scope so the body resolves it (and match-arm
        // contextual base works), like a function body sees its params.
        Map<String, IrSort> savedScope = new LinkedHashMap<>(currentScope);
        currentScope.clear();
        currentScope.put(paramName.text(), sourceSort);
        try {
            IrExpr body = parseExpr();
            return new IrStmt.Coercion(
                    sourceSort, targetSort, paramName.text(), body, start.origin());
        } finally {
            currentScope.clear();
            currentScope.putAll(savedScope);
        }
    }

    /**
     * A body-less {@code function}/{@code method} whose return sort doesn't
     * pin a single value can't have its body synthesized (that would be
     * genuine program search, not desugar — deferred). Rather than silently
     * dropping the declaration (which made it look defined, skipped
     * sort-checking of its signature, and surfaced later as a misleading
     * "Unknown function"), reject it at the declaration site.
     */
    private static ParseException specOnlyWithoutSynthesis(
            String kind, String name, IrSort returnSort, Origin origin) {
        return new ParseException(
                kind + " '" + name + "' has no body, and its return type "
                        + describeSort(returnSort)
                        + " doesn't pin a value to synthesize one. Add a '-> body', or give a "
                        + "value-pinning return like [Int:@==EXPR].",
                origin);
    }

    /** A short, user-facing rendering of a sort for diagnostics. */
    /**
     * Method declaration: {@code method Type.name(params):RetSort [-> body]}.
     * Desugars to {@code function Type.name(self:Type, params):RetSort [-> body]}.
     *
     * <p>The receiver is bound to the name {@code self} (always). Inside the
     * body and return refinements, {@code self} is a regular in-scope name —
     * this is a deliberate departure from the old parser, which used {@code @}
     * to mean the receiver. Under the new design (principle 3), {@code @}
     * always means the value-under-refinement of the enclosing bracket-form,
     * and receivers are named explicitly to keep that meaning unambiguous.
     */
    private IrStmt parseMethod() throws ParseException {
        AltToken start = expectKeyword("method");
        String name = parseDeclarationName();
        int dotIdx = name.indexOf('.');
        if (dotIdx < 0) {
            throw new ParseException(
                    "Method name must be qualified with a receiver type "
                            + "(e.g. 'Type.name'); got '" + name + "'",
                    start.origin());
        }
        String receiverTypeName = name.substring(0, dotIdx);
        String memberName = name.substring(dotIdx + 1);
        // Operators are symmetric multi-dispatch free functions — the
        // receiver-rooted method form forces the receiver to be the FIRST
        // operand (you can never write `method Int./(c:Custom)`), exactly the
        // asymmetry the dispatch unification removes. Reject it and point the
        // author to the free-function form. Non-operator methods are unaffected.
        if (OVERLOADABLE_OPS.contains(memberName)) {
            throw new ParseException(
                    "operators are free functions, not methods: declare "
                            + "`function " + memberName + "(a:T, b:T):R` instead of "
                            + "`method " + receiverTypeName + "." + memberName + "`.",
                    start.origin());
        }

        expect(AltToken.Kind.LPAREN);
        List<IrParam> params = parseParamList(AltToken.Kind.RPAREN);
        expect(AltToken.Kind.RPAREN);
        List<ParamDestructure> destrs = drainParamDestructures();
        expect(AltToken.Kind.COLON);
        IrSort returnSort = parseSort();

        // Reject a user-declared `this` — would collide with the injected one.
        for (IrParam p : params) {
            if (p.name().equals("this")) {
                throw new ParseException(
                        "Method param cannot be named 'this' — that name is reserved for "
                                + "the implicit receiver injected by method desugar",
                        start.origin());
            }
        }

        IrSort receiverSort = new IrSort.Named(receiverTypeName, start.origin());
        List<IrParam> desugaredParams = new ArrayList<>(params.size() + 1);
        desugaredParams.add(new IrParam("this", receiverSort));
        desugaredParams.addAll(params);

        if (peek().kind() != AltToken.Kind.ARROW) {
            // Spec-only — synthesis requires the explicit `;` directive.
            if (peek().kind() != AltToken.Kind.SEMICOLON) {
                throw new ParseException(
                        "method '" + name + "' needs a body ('-> expr') or a "
                                + "synthesis directive (';')",
                        peek().origin());
            }
            consume();  // SEMICOLON
            IrExpr derived = tryDeriveBodyFromReturnSort(returnSort);
            if (derived != null) {
                IrExpr body = wrapParamDestructures(derived, destrs);
                IrSort effReturn = effectiveSynthesizedReturn(derived, returnSort);
                declaredFunctionReturns.put(name, effReturn);
                return new IrStmt.FunctionDecl(
                        name, desugaredParams, effReturn, body, start.origin());
            }
            throw specOnlyWithoutSynthesis("method", name, returnSort, start.origin());
        }
        consume();  // ARROW

        // Push desugared params (including self) into scope for body parsing.
        Map<String, IrSort> savedScope = new LinkedHashMap<>(currentScope);
        currentScope.clear();
        for (IrParam p : desugaredParams) currentScope.put(p.name(), p.sort());
        bindParamDestructures(destrs);
        try {
            IrExpr body = wrapParamDestructures(parseExpr(), destrs);
            declaredFunctionReturns.put(name, returnSort);
            return new IrStmt.FunctionDecl(
                    name, desugaredParams, returnSort, body, start.origin());
        } finally {
            currentScope.clear();
            currentScope.putAll(savedScope);
        }
    }

    /**
     * A param-sort {@code .{}} destructure (`point:[Point.{x, y -> py}]`): the
     * param keeps the base sort and each entry binds a local to a field read on
     * the param in the function body. Accumulated by {@link #parseParamList},
     * drained by the function/method parsers (S4).
     */
    private record ParamDestructure(String local, String paramName,
                                    String fieldName, IrSort fieldSort) {}

    private final List<ParamDestructure> pendingParamDestructures = new ArrayList<>();

    private List<ParamDestructure> drainParamDestructures() {
        List<ParamDestructure> d = new ArrayList<>(pendingParamDestructures);
        pendingParamDestructures.clear();
        return d;
    }

    /** Binds the destructured locals into the body's scope for parsing. */
    private void bindParamDestructures(List<ParamDestructure> destrs) {
        for (ParamDestructure d : destrs) currentScope.put(d.local(), d.fieldSort());
    }

    /** Wraps {@code body} in `let local = param.field` for each destructure. */
    private IrExpr wrapParamDestructures(IrExpr body, List<ParamDestructure> destrs) {
        IrExpr out = body;
        for (int i = destrs.size() - 1; i >= 0; i--) {
            ParamDestructure d = destrs.get(i);
            // A null fieldName marks a DEFERRED cross-module param destructure —
            // the binding is wrapped post-link by DestructureResolver, which knows
            // the declared field names. Skip it here (only seed scope).
            if (d.fieldName() == null) continue;
            out = new IrExpr.LetIn(d.local(), d.fieldSort(),
                    new IrExpr.FieldAccess(
                            new IrExpr.Var(d.paramName(), body.origin()), d.fieldName(), body.origin()),
                    out, body.origin());
        }
        return out;
    }

    private List<IrParam> parseParamList(AltToken.Kind terminator) throws ParseException {
        List<IrParam> params = new ArrayList<>();
        boolean first = true;
        while (peek().kind() != terminator) {
            if (!first) expect(AltToken.Kind.COMMA);
            AltToken name = expect(AltToken.Kind.IDENT);
            expect(AltToken.Kind.COLON);
            IrSort sort;
            // Param-sort `.{}` destructure: `point:[Point.{x, y}]` — the param
            // keeps base sort [Point]; x, y bind to point.x, point.y in the body.
            if (peek().kind() == AltToken.Kind.LBRACKET
                    && peek(1).kind() == AltToken.Kind.IDENT
                    && peek(2).kind() == AltToken.Kind.DOT
                    && peek(3).kind() == AltToken.Kind.LBRACE) {
                consume();  // [
                AltToken baseTok = expect(AltToken.Kind.IDENT);
                List<IrStmt.RequireEntry> entries = parseDotBraceEntryList();
                expect(AltToken.Kind.RBRACKET);
                sort = new IrSort.Named(baseTok.text(), baseTok.origin());
                IrSort.Structural baseStruct = declaredStructs.get(baseTok.text());
                for (IrStmt.RequireEntry e : entries) {
                    IrSort fieldSort = baseStruct != null
                            && baseStruct.members().get(e.remoteName()) != null
                            ? baseStruct.members().get(e.remoteName())
                            : IrSort.named("_");
                    pendingParamDestructures.add(new ParamDestructure(
                            e.localName(), name.text(), e.remoteName(), fieldSort));
                }
            } else {
                sort = parseSort();
                // Positional param destructure: `tcd:[TractionCD(n, d)]` binds the
                // fields by position in the body (parity with the `.{}` form and
                // with match patterns). Only a PURE-BINDER pattern over a declared
                // struct is wired — a tuple/record type (`[(Int, Int)]`), a
                // field-sort narrowing (`[Point(x:[Int:@>0])]`), or a nested
                // pattern is left as an ordinary sort (destructure those with
                // `match` in the body).
                if (sort instanceof IrSort.Structural sp) {
                    IrSort.Structural decl = patternShapeFor(sp.name());
                    if (decl != null && isPureBinderParamPattern(sp, decl)) {
                        // A positional struct param IS a destructure pattern, so the
                        // arity-total rule applies (verdict B) — same rule, same one
                        // place as the match form. Without this, the too-FEW case
                        // (`p:[P(a)]` over a 2-field struct) slipped through (the
                        // match form caught too-many but the param form caught
                        // neither too-few here).
                        requireArityTotal(sp.name(), sp.members().size(),
                                decl.members().size(), sp.origin());
                        sort = wirePositionalParamDestructure(name, sp, decl);
                    } else if (decl == null && isDeferredPattern(sp)) {
                        // Cross-module positional param destructure `v:[Vec(x, y)]`.
                        // Field names/order unknown at parse time: seed the binders
                        // into scope (sort `_`) so the body parses, but leave the
                        // param sort as the deferred pattern. DestructureResolver
                        // reduces it to `[Vec]` and wraps the body post-link.
                        seedDeferredParamBinders(name.text(), sp);
                    }
                }
            }
            params.add(new IrParam(name.text(), sort));
            first = false;
        }
        return params;
    }

    /**
     * True iff {@code sp} (a Structural param sort over the declared struct
     * {@code decl}) is a <em>pure-binder</em> positional destructure — every
     * clause is a bare binder (positional or field-named), no field constraint
     * and no nesting. Detected by reference-identity: the parser reuses the
     * DECLARED field-sort object for a bare binder, but builds a fresh sort for a
     * by-name narrowing (`x:[Int:@>0]`) — so a member that <em>is</em> the
     * declared sort is a binder, and anything else is a narrowing/type. Tuples,
     * records, narrowing types, and nested patterns are therefore NOT pure-binder
     * (destructure those with {@code match} in the body).
     */
    private boolean isPureBinderParamPattern(IrSort.Structural sp, IrSort.Structural decl) {
        if (!literalConstrainedFields.getOrDefault(sp, java.util.Set.of()).isEmpty()) {
            return false;
        }
        for (Map.Entry<String, IrSort> m : sp.members().entrySet()) {
            IrSort declared = decl.members().get(m.getKey());
            if (declared == null || m.getValue() != declared) {
                return false;
            }
        }
        return true;
    }

    /**
     * Wires a pure-binder positional param destructure {@code p:[Type(a, b)]}:
     * emits a {@link ParamDestructure} per field so the body sees
     * {@code let a = p.field} (reusing the {@code .{}} machinery), and returns the
     * param's reduced type {@code [Type]}. Caller guarantees pure-binder via
     * {@link #isPureBinderParamPattern}.
     */
    private IrSort wirePositionalParamDestructure(
            AltToken paramName, IrSort.Structural sp, IrSort.Structural decl) {
        Map<String, String> renames = destructureRenames.getOrDefault(sp, Map.of());
        for (Map.Entry<String, IrSort> m : sp.members().entrySet()) {
            String binder = renames.getOrDefault(m.getKey(), m.getKey());
            pendingParamDestructures.add(new ParamDestructure(
                    binder, paramName.text(), m.getKey(), m.getValue()));
        }
        return new IrSort.Named(sp.name(), paramName.origin());
    }

    /**
     * Seeds the binders of a DEFERRED positional param pattern {@code v:[Vec(x,
     * y)]} into scope so the function body can be parsed. A {@code fieldName} of
     * {@code null} marks the destructure as DEFERRED: {@link #wrapParamDestructures}
     * skips it (the field name isn't known yet), and {@code DestructureResolver}
     * reduces the param sort and wraps the body post-link. Nested patterns are
     * destructured by the resolver via the param sort, so only the top-level
     * slots are seeded here (matching how the local pure-binder form behaves).
     */
    private void seedDeferredParamBinders(String paramName, IrSort.Structural sp) {
        java.util.Set<String> skip =
                literalConstrainedFields.getOrDefault(sp, java.util.Set.of());
        Map<String, String> renames = destructureRenames.getOrDefault(sp, Map.of());
        for (Map.Entry<String, IrSort> m : sp.members().entrySet()) {
            if (skip.contains(m.getKey())) continue;          // discard / literal slot
            if (m.getValue() instanceof IrSort.Structural) continue;  // nested: resolver binds
            String binder = renames.getOrDefault(m.getKey(), m.getKey());
            pendingParamDestructures.add(new ParamDestructure(
                    binder, paramName, null, IrSort.named("_")));
        }
    }

    private static String paramSig(List<IrParam> params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(params.get(i).name()).append(":").append(params.get(i).sort());
        }
        return sb.toString();
    }

    /**
     * The synthesized body for a return sort: the EXPR of a top-level
     * {@code @==EXPR} (@-free) conjunct of its predicate — `[Int:@==n*2]`, or
     * the `@==r` half of `[Int:@==r & @>0]`. Returns null if no such definition
     * conjunct exists. The remaining conjuncts are the postcondition the gate
     * still proves (see {@link #removeDefinitionConjunct}) — so one pin can both
     * DEFINE the body and carry a property to VERIFY.
     */
    private static IrExpr tryDeriveBodyFromReturnSort(IrSort returnSort) {
        return returnSort instanceof IrSort.Refined r ? definitionWitness(r.predicate()) : null;
    }

    /** The EXPR of a top-level `@==EXPR` (@-free) conjunct of {@code pred}, or null. */
    private static IrExpr definitionWitness(IrExpr pred) {
        if (!(pred instanceof IrExpr.BinOp bop)) return null;
        if (bop.op() == IrExpr.Op.EQ) {
            // Accept either side: `@==EXPR` (the sugar's shape) or a flipped form.
            IrExpr candidate;
            if (bop.left() instanceof IrExpr.SelfRef && !(bop.right() instanceof IrExpr.SelfRef)) {
                candidate = bop.right();
            } else if (bop.right() instanceof IrExpr.SelfRef && !(bop.left() instanceof IrExpr.SelfRef)) {
                candidate = bop.left();
            } else {
                return null;
            }
            return containsSelfRef(candidate) ? null : candidate;
        }
        if (bop.op() == IrExpr.Op.AND) {
            IrExpr l = definitionWitness(bop.left());
            return l != null ? l : definitionWitness(bop.right());
        }
        return null;
    }

    /** True if {@code pred} is a `@==EXPR` definition conjunct (one side `@`, other @-free). */
    private static boolean isDefinitionPin(IrExpr pred) {
        return pred instanceof IrExpr.BinOp b && b.op() == IrExpr.Op.EQ
                && ((b.left() instanceof IrExpr.SelfRef && !containsSelfRef(b.right()))
                || (b.right() instanceof IrExpr.SelfRef && !containsSelfRef(b.left())));
    }

    /**
     * {@code pred} with its `@==EXPR` definition conjunct removed — the residual
     * is the postcondition the gate must prove. Null when the predicate is
     * nothing but the definition (a pure synthesis pin, no postcondition).
     */
    private static IrExpr removeDefinitionConjunct(IrExpr pred) {
        if (isDefinitionPin(pred)) return null;
        if (pred instanceof IrExpr.BinOp bop && bop.op() == IrExpr.Op.AND) {
            IrExpr l = removeDefinitionConjunct(bop.left());
            IrExpr r = removeDefinitionConjunct(bop.right());
            if (l == null) return r;
            if (r == null) return l;
            return new IrExpr.BinOp(IrExpr.Op.AND, l, r, bop.origin());
        }
        return pred;
    }

    /**
     * The declared return for a synthesized body — the postcondition the gate
     * still proves, with the definition consumed.
     * <ul>
     *   <li>{@code @==witness & POSTCOND} → {@code [base:POSTCOND]}: the witness
     *       is the body (DEFINE), POSTCOND is proven (VERIFY) — one pin, both.</li>
     *   <li>A pure construction- or pipeline-pin (Record / let-chain body) is
     *       DEFINITIONAL — the body IS the construction, and it carries opaque
     *       calls the gate can't reflexively prove — so declare the bare base.</li>
     *   <li>A pure value pin ({@code @==n*2}) keeps its refinement — the gate
     *       proves it reflexively.</li>
     * </ul>
     */
    private static IrSort effectiveSynthesizedReturn(IrExpr derived, IrSort declaredReturn) {
        if (declaredReturn instanceof IrSort.Refined r) {
            IrExpr residual = removeDefinitionConjunct(r.predicate());
            if (residual != null) {
                return new IrSort.Refined(r.name(), residual, r.origin());  // postcondition to prove
            }
        }
        if (derived instanceof IrExpr.Record rec && rec.typeName() != null) {
            return new IrSort.Named(rec.typeName(), declaredReturn.origin());
        }
        if (derived instanceof IrExpr.LetIn && declaredReturn instanceof IrSort.Refined r) {
            return new IrSort.Named(r.name(), declaredReturn.origin());
        }
        return declaredReturn;
    }

    /**
     * S6 merge: build a {@code Target(…)} construction from a partial
     * {@code value} (a different struct supplying some fields) plus the pin's
     * {@code @.f == EXPR} field-values. Each target field comes from the pin if
     * pinned, else a field read on the value, else it's an unspecified-field
     * error (fabricate-never — the merge must cover every field).
     */
    private IrExpr mergePartialWithPin(
            IrExpr value, String targetName, IrSort.Structural target,
            IrSort.Structural valueStruct, IrExpr pin, sibarum.pontif.core.Origin origin)
            throws ParseException {
        Map<String, IrExpr> pinValues = new LinkedHashMap<>();
        collectFieldPins(pin, pinValues);
        Map<String, IrExpr> members = new LinkedHashMap<>();
        for (String f : target.members().keySet()) {
            if (pinValues.containsKey(f)) {
                members.put(f, pinValues.get(f));
            } else if (valueStruct != null && valueStruct.members().containsKey(f)) {
                members.put(f, new IrExpr.FieldAccess(value, f, origin));
            } else {
                throw new ParseException(
                        "promotion to '" + targetName + "' leaves field '" + f
                                + "' unspecified — the value supplies "
                                + (valueStruct != null ? valueStruct.members().keySet() : "(non-struct)")
                                + " and the pin supplies " + pinValues.keySet(),
                        origin);
            }
        }
        return new IrExpr.Record(targetName, members, origin);
    }

    /** Collects {@code @.field == EXPR} conjuncts of a pin as field -> EXPR. */
    private static void collectFieldPins(IrExpr pred, Map<String, IrExpr> out) {
        if (pred instanceof IrExpr.BinOp op) {
            switch (op.op()) {
                case AND -> {
                    collectFieldPins(op.left(), out);
                    collectFieldPins(op.right(), out);
                }
                case EQ -> {
                    String lf = pinFieldName(op.left());
                    if (lf != null) { out.put(lf, op.right()); return; }
                    String rf = pinFieldName(op.right());
                    if (rf != null) out.put(rf, op.left());
                }
                default -> { }
            }
        }
    }

    private static String pinFieldName(IrExpr e) {
        return e instanceof IrExpr.FieldAccess fa && fa.base() instanceof IrExpr.SelfRef
                ? fa.fieldName() : null;
    }

    /** True if {@code expr} contains an {@link IrExpr.SelfRef} anywhere. */
    private static boolean containsSelfRef(IrExpr expr) {
        return switch (expr) {
            case IrExpr.SelfRef s -> true;
            case IrExpr.Lit l -> false;
            case IrExpr.Dec d -> false;
            case IrExpr.Chr c -> false;
            case IrExpr.Str s -> false;
            case IrExpr.Bool b -> false;
            case IrExpr.Var v -> false;
            case IrExpr.DispatchRef d -> false;
            case IrExpr.BinOp op -> containsSelfRef(op.left()) || containsSelfRef(op.right());
            case IrExpr.LetIn l -> containsSelfRef(l.value()) || containsSelfRef(l.body());
            case IrExpr.Call c -> c.args().stream().anyMatch(AltParser::containsSelfRef);
            case IrExpr.Lambda lam -> containsSelfRef(lam.body());
            case IrExpr.Apply app -> containsSelfRef(app.fn())
                    || app.args().stream().anyMatch(AltParser::containsSelfRef);
            case IrExpr.Match m -> containsSelfRef(m.scrutinee())
                    || m.branches().stream().anyMatch(b -> containsSelfRef(b.result()));
            case IrExpr.Record r -> r.members().values().stream().anyMatch(AltParser::containsSelfRef);
            case IrExpr.FieldAccess fa -> containsSelfRef(fa.base());
            case IrExpr.MethodCall mc -> containsSelfRef(mc.receiver())
                    || mc.args().stream().anyMatch(AltParser::containsSelfRef);
            case IrExpr.Iterate it -> containsSelfRef(it.source())
                    || it.outputs().stream().anyMatch(o -> o.init() != null && containsSelfRef(o.init()))
                    || it.arms().stream().anyMatch(a -> a.writes().stream().anyMatch(
                            w -> containsSelfRef(w.value()) || (w.key() != null && containsSelfRef(w.key()))));
            case IrExpr.Cast cast -> containsSelfRef(cast.value());
        };
    }

    // --- Top-level let ---
    // Form: `let qualified.name (:Sort)? (= value)?`
    //
    // Three cases:
    //   `let X:Sort = value`  — explicit sort + value.  Sort acts as a
    //                           sanity-check (its base name must match the
    //                           inferred value's base name); the binding's
    //                           effective sort is the value's maximally
    //                           specific inferred sort (so dispatch routing
    //                           has the tightest narrowing available).
    //   `let X = value`        — sort fully inferred from value via
    //                           {@link #inferMaximalSort}.
    //   `let X:Sort`           — spec-only.  Stays NoOp pending the proof
    //                           engine; the qualified-name synthesis path is
    //                           a separate TODO item.
    //
    // Lowering: the first two cases produce {@code IrStmt.FunctionDecl(name,
    // [], inferredSort, value)} — a 0-arg function in the dispatch table.
    // Bare references to the let name in expression position are rewritten
    // to {@code Call(name, [])} (see {@link #parsePrimary}) so users don't
    // have to write {@code origin()} everywhere.

    private IrStmt parseLet() throws ParseException {
        AltToken start = expectKeyword("let");
        if (peek().kind() == AltToken.Kind.LBRACKET) {
            return parseDestructuringLetTop(start);
        }
        String name = parseDottedName();
        // A retired parametric-trait form (`let X[type T]:Type{…}`) — consume the
        // slot so the `:Type{` below reaches the trait-migration error (in
        // parseSort) rather than a generic one. Traits are now declared with the
        // `trait` keyword (parseTrait); `let` no longer declares them.
        if (peek().kind() == AltToken.Kind.LBRACKET) parseTypeParamSlot();
        if (peek().kind() == AltToken.Kind.DOT && peek(1).kind() == AltToken.Kind.LBRACE) {
            return parseDictDecompositionLetTop(start, name);
        }
        IrSort declaredSort = null;
        if (peek().kind() == AltToken.Kind.COLON) {
            consume();
            // Fragment literal: `let f:[ (el:Int) -> body ]` — a first-class
            // synthesis-fragment value (docs/stream-war.md §3). The `[…]` carries
            // params + body (no `= value`); the binding lowers to a 0-arg let
            // returning the lambda, sorted as the Method contract. Distinguished
            // from an ordinary `[…]` sort by a NAMED param head (`( IDENT :`), which
            // no tuple/Method sort begins with.
            if (looksLikeFragmentLiteral()) {
                IrExpr.Lambda frag = parseFragmentLiteral();
                List<IrSort> paramSorts = new ArrayList<>();
                List<String> paramNames = new ArrayList<>();
                for (IrParam p : frag.params()) {
                    paramSorts.add(p.sort());
                    paramNames.add(p.name());
                }
                IrSort methodSort = new IrSort.Method(
                        paramSorts, paramNames, frag.returnSort(), start.origin());
                declaredTopLevelLets.put(name, methodSort);
                if (peek().kind() == AltToken.Kind.SEMICOLON) consume();
                return new IrStmt.FunctionDecl(
                        name, List.of(), methodSort, frag, start.origin(), true);
            }
            // `let NAME:Type[sortExpr]` — a reusable sort alias (refinements, unions
            // of refined bases, named sorts). It's a type-level declaration with no
            // value, lowered to a TypeAlias the AliasResolver inlines — the bracketed
            // sibling of the `Type{...}` trait form below.
            if (checkKeyword("Type") && peek(1).kind() == AltToken.Kind.LBRACKET) {
                consume();  // `Type`
                IrSort aliased = parseBracketSort();  // the `[...]` (unions/refinements)
                if (peek().kind() == AltToken.Kind.SEMICOLON) {
                    consume();  // optional declaration terminator
                }
                if (peek().kind() == AltToken.Kind.EQUALS) {
                    throw new ParseException(
                            "let '" + name + "' is a Type[...] sort alias — it declares a "
                                    + "sort, not a value, so it can't have '= EXPR'",
                            peek().origin());
                }
                declaredSortAliases.add(name);
                return new IrStmt.TypeAlias(name, aliased, start.origin());
            }
            // A NAMED trait uses the `trait` declarator, not `let` (a body-binding
            // form). Note the anonymous `Type{…}` sort itself stays usable in any
            // sort position (parseSort) — part of the unified type-spec system;
            // only naming one via `let` is redirected to `trait`.
            if (checkKeyword("Type") && peek(1).kind() == AltToken.Kind.LBRACE) {
                throw new ParseException(
                        "Declare a named trait with `trait " + name + "{ … }` — `let` is a "
                                + "binding form, not the declarator for traits.",
                        peek().origin());
            }
            declaredSort = parseSort();
        }
        IrExpr value = null;
        if (peek().kind() == AltToken.Kind.EQUALS) {
            consume();
            value = parseExpr();
        }
        // `;` is the explicit synthesis directive (mirrors function/method).
        // With a value present it requests partial-value + pin synthesis (S6,
        // below); with no value it requests pure pin synthesis (further down).
        boolean synthDirective = peek().kind() == AltToken.Kind.SEMICOLON;
        if (synthDirective) consume();
        if (declaredSort == null && value == null) {
            throw new ParseException(
                    "let '" + name + "' needs either a sort annotation (':Sort') "
                            + "or a value ('= EXPR')",
                    start.origin());
        }
        // S6: promotion via value synthesis — `let x:[Target:@.f==v] = partial;`.
        // The `;` requests synthesis; the value (a DIFFERENT struct) supplies the
        // base fields, the pin supplies the rest, merged into a Target
        // construction. The result IS a Target by construction (definitional), so
        // the binding becomes the bare struct sort and the base-mismatch check
        // below sees a match.
        if (synthDirective && value != null && declaredSort instanceof IrSort.Refined ref
                && declaredStructs.containsKey(ref.name())) {
            String valueBase = baseSortName(inferMaximalSort(value));
            if (!ref.name().equals(valueBase)) {
                IrSort.Structural target = declaredStructs.get(ref.name());
                IrSort.Structural valueStruct = valueBase == null
                        ? null : declaredStructs.get(valueBase);
                value = mergePartialWithPin(
                        value, ref.name(), target, valueStruct, ref.predicate(), start.origin());
                declaredSort = new IrSort.Named(ref.name(), declaredSort.origin());
            }
        }
        if (value == null) {
            // Spec-only let: a value-pinning sort IS the definition, but
            // synthesis must be requested explicitly with `;` (mirrors
            // function/method). A bare `let x:Sort` with no value and no
            // directive is an error, not an implicit synthesis.
            if (!synthDirective) {
                throw new ParseException(
                        "let '" + name + "' with a sort needs a value ('= EXPR') "
                                + "or a synthesis directive (';')",
                        start.origin());
            }
            // The predicate `@==EXPR` carries its witness as an expression, so
            // the body is synthesized verbatim from the pin (`let
            // zero:[Decimal:@==0.0];` means zero = 0.0; the claim wrapper below
            // still notarizes the synthesis at force).
            value = pinnedWitness(declaredSort);
            if (value == null) {
                // `;` given, but the sort pins no unique witness
                // (e.g. [Int:@>0]) — honest error, not a silent NoOp.
                throw new ParseException(
                        "let '" + name + "': declared sort " + declaredSort
                                + " does not pin a synthesizable value — `;` needs a "
                                + "value-pinning sort (e.g. [Int:0] or [Int:@==EXPR])",
                        start.origin());
            }
        }
        IrSort inferredSort = inferMaximalSort(value);
        boolean intToDecimal = false;
        boolean demotion = false;
        boolean traitUpcast = false;
        boolean streamAutobox = false;
        if (declaredSort != null) {
            String declaredBase = baseSortName(declaredSort);
            String inferredBase = baseSortName(inferredSort);
            // The lossless Int→Decimal embedding is not a mismatch —
            // DecimalPromotion promotes the literal at IR time and the
            // construction gate judges the claim (same leniency the record
            // gate already grants its fields).
            intToDecimal = "Decimal".equals(declaredBase) && "Int".equals(inferredBase);
            // An anonymous aggregate ("_record") against a declared name is the
            // promotion sugar, not a mismatch — `let p:Point = {x=1, y=2}` is
            // checked construction with the redundant name elided.
            // AggregatePromotion stamps and validates it at IR time (it also
            // sees imported structs this parser can't).
            if (declaredBase != null && inferredBase != null
                    && !inferredBase.equals("_record")
                    && !inferredBase.equals("_")  // unknown floor — parser can't prove a mismatch, so abstain
                    && !intToDecimal
                    && !declaredSortAliases.contains(declaredBase)  // alias base unknown until resolved
                    && !declaredBase.equals(inferredBase)) {
                // A declared DEMOTION: the value's struct carries a base sort
                // that demotes to the claimed base (`struct Point3D:[Point:…]`),
                // so `let b:Point = a` is a valid projection, not a mismatch —
                // ConstructionGate runs the morphism at IR time. The binding is
                // recorded at the demoted (base) sort.
                if (demotesTo(inferredBase, declaredBase)) {
                    demotion = true;
                } else if (declaredTraits.contains(declaredBase)
                        || declaredTraits.contains(inferredBase)) {
                    // Trait coercion, implicit in BOTH directions (the trait's
                    // attributes are computed projections — nothing fabricated
                    // upward, nothing lost downward). Struct→trait binds at the
                    // trait sort; trait→struct binds at the struct sort and the
                    // claim's runtime check confirms the value's concrete type
                    // (a downcast to a type the value isn't is rejected there).
                    // The value keeps its concrete type at runtime, so trait-view
                    // attribute access resolves to fields/producers.
                    traitUpcast = true;
                } else if ("Stream".equals(declaredBase) && TUPLE_SENTINEL.equals(inferredBase)) {
                    // tuple → Stream[T]: the one-way autobox (docs/iteration.md §8.6) —
                    // a clean forget of the tuple's arity/positional identity (in the
                    // cast law's lose-freely family), gated by every element being
                    // convertible to T. Figurative for now (a base-level element check
                    // here); the multi-dispatch promotion path will replace it.
                    requireStreamElements(declaredSort, inferredSort, start.origin());
                    streamAutobox = true;
                } else {
                    throw new ParseException(
                            "let '" + name + "' is declared " + describeSort(declaredSort)
                                    + " but its value is " + describeSort(inferredSort)
                                    + " — these are different types.",
                            start.origin());
                }
            }
        }
        // Promotion cases: an anonymous value's shape takes the declared
        // sort (stamped at IR time); an Int literal at a Decimal boundary
        // takes BARE Decimal — the value promotes at IR time, and the
        // refined claim (if any) travels in the wrapper below, NOT in the
        // 0-arg return sort, where it would be an obligation the integer-
        // only discharge kernel can never prove. Otherwise keep the tighter
        // inferred narrowing as before.
        IrSort binding = demotion || traitUpcast || streamAutobox
                ? declaredSort
                : declaredSort != null
                && "_record".equals(baseSortName(inferredSort))
                ? declaredSort
                : intToDecimal
                ? new IrSort.Named("Decimal", declaredSort.origin())
                : inferredSort;
        declaredTopLevelLets.put(name, binding);
        // A declared sort is a claim made where the binding is made. The
        // 0-arg lowering keeps the tight inferred narrowing as the return
        // sort; the claim itself travels inside, on a LetIn the construction
        // gate judges three-way (fit/miss/overlap) like a constructor arg.
        // The promotion-sugar case is exempt: the stamped record's own
        // construction gate judgment IS the claim check.
        IrExpr fnBody = value;
        if (declaredSort != null && !"_record".equals(baseSortName(inferredSort)) && !streamAutobox) {
            fnBody = new IrExpr.LetIn(
                    name, binding, value,
                    new IrExpr.Var(name, start.origin()),
                    start.origin(), declaredSort);
        }
        return new IrStmt.FunctionDecl(
                name, List.of(), binding, fnBody, start.origin(), true);
    }

    /**
     * The unique witness a value-pinning refinement carries, or null when
     * the sort doesn't pin one. Two pin shapes:
     *
     * <p><b>Syntactic</b> — {@code Refined(base, @ == EXPR)} (also what the
     * bare-expr sugar produces: {@code [Decimal:0.0]} ≡
     * {@code [Decimal:@==0.0]}): the witness is EXPR verbatim, any base,
     * provided EXPR is closed over {@code @} (a self-referential pin like
     * {@code @==@+1} has no extractable witness).
     *
     * <p><b>Semantic</b> — an Int refinement whose extension the bound
     * engine collapses to a single point: integer-strict cuts make
     * {@code [Int:@>-1 & @<1]} the singleton {@code {0}} — discreteness is
     * the license (a Decimal interval has no such witness; choosing one
     * would inject information the program never supplied). Sound to be
     * optimistic here: {@code bound} over-approximates, and the synthesized
     * binding still carries its claim — the construction gate verifies the
     * witness against the full predicate (compile-time FITS or a notarized
     * runtime check), so a wrong witness can never bind silently.
     */
    private IrExpr pinnedWitness(IrSort sort) {
        if (!(sort instanceof IrSort.Refined r)) return null;
        if (r.predicate() instanceof IrExpr.BinOp op
                && op.op() == IrExpr.Op.EQ
                && op.left() instanceof IrExpr.SelfRef
                && !containsSelfRef(op.right())) {
            return op.right();
        }
        if ("Int".equals(r.name())) {
            try {
                sibarum.pontif.core.symbolic.SymExpr pred =
                        sibarum.pontif.ir.IrCompiler.compileSymExpr(
                                substituteSelfWithVar(r.predicate(), "@spec"));
                sibarum.pontif.predicates.Interval range =
                        sibarum.pontif.predicates.BoundAnalysis.bound(
                                sibarum.pontif.core.symbolic.SymExpr.var("@spec"),
                                List.of(pred));
                if (!range.isEmpty()
                        && range.lo() == range.hi()
                        && range.lo() != sibarum.pontif.predicates.Interval.NEG_INF
                        && range.lo() != sibarum.pontif.predicates.Interval.POS_INF) {
                    return new IrExpr.Lit(range.lo(), r.origin());
                }
            } catch (sibarum.pontif.ir.CompileException outsideFragment) {
                // predicate outside the symbolic fragment — no witness derivable
            }
        }
        return null;
    }

    /** {@code @} → a named var, so the bound engine sees an ordinary subject. */
    private static IrExpr substituteSelfWithVar(IrExpr e, String varName) {
        return switch (e) {
            case IrExpr.SelfRef s -> new IrExpr.Var(varName, s.origin());
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(),
                    substituteSelfWithVar(op.left(), varName),
                    substituteSelfWithVar(op.right(), varName),
                    op.origin());
            default -> e;  // predicates are comparison trees; leaves pass through
        };
    }

    /**
     * Computes the maximally-specific sort for an expression. Used by
     * top-level let to give bindings the tightest narrowing the parser can
     * derive at parse time. Best-effort: falls back to coarser shapes when
     * tighter inference would require machinery that doesn't exist yet
     * (notably per-call dispatch return narrowing).
     *
     * <p>Coverage:
     * <ul>
     *   <li>{@code Lit v}        → {@code [Int:@==v]} singleton.
     *   <li>{@code Bool v}       → {@code [Bool:@==v]} singleton.
     *   <li>{@code Var name}     → scope lookup; falls back to declared
     *       top-level lets, else the loose {@code "_"} sort.
     *   <li>{@code Record}       → structural sort with recursively-inferred
     *       field sorts. Struct name is recovered via field-set lookup in
     *       {@link #declaredStructs}; if no unique match, the sort is
     *       anonymous (still useful for field access).
     *   <li>{@code FieldAccess}  → base's sort's field sort, if base inferable.
     *   <li>{@code BinOp}        → {@code [Int:@==expr]} or {@code [Bool:@==expr]}
     *       per the op kind, via the implicit-@==EXPR sugar shape Pontif uses
     *       elsewhere.
     *   <li>{@code Call name a*} → declared return sort of {@code name}
     *       (lossy — per-call narrowing waits on the dispatch-inference
     *       priority work).
     *   <li>{@code Apply / Lambda / Match / SelfRef / LetIn} → coarse
     *       fallback (the {@code "_"} sort). Tighter shapes can be added when
     *       a use case justifies them.
     * </ul>
     */
    /**
     * True when {@code fromBase}'s declared struct demotes (is-a) to
     * {@code toBase} — it carries a {@code :[toBase:…]} base sort. The coercion
     * {@code let b:toBase = a} (where {@code a : fromBase}) is then a valid
     * demotion projection, not a base-sort mismatch (ConstructionGate runs the
     * morphism at IR time).
     */
    private boolean demotesTo(String fromBase, String toBase) {
        IrSort.Structural from = declaredStructs.get(fromBase);
        if (from == null || from.baseSort() == null) return false;
        return toBase.equals(baseSortName(from.baseSort()));
    }

    // Parse-time best-effort typing — now ONE engine with the core. The parser
    // is no longer a separate reasoner: it runs NarrowingInference.inferFloor over
    // an InferenceContext built from its live scope maps, so every narrowing shape
    // the core can express (the exact value-pin, field projection, method/operator
    // return typing) is available here too. Parse-time weakness falls out only from
    // an emptier context (no imports yet → "_"), never a divergent strategy. See
    // docs/inference-unification.md.
    private IrSort inferMaximalSort(IrExpr expr) {
        // A record keeps its STRUCTURAL representation — the parser's canonical
        // aggregate shape (member name → sort), interchangeable with the
        // field-conjunct refinement the core's `infer` produces for the same value
        // (James 2026-06-18). Members and every other form type through the one
        // core engine, so there's no divergent reasoner — only a shape choice.
        if (expr instanceof IrExpr.Record r) {
            Map<String, IrSort> members = new LinkedHashMap<>();
            for (Map.Entry<String, IrExpr> e : r.members().entrySet()) {
                members.put(e.getKey(), inferMaximalSort(e.getValue()));
            }
            return new IrSort.Structural(
                    r.typeName() != null ? r.typeName() : "_record", members, r.origin());
        }
        IrSort inferred = NarrowingInference.inferFloor(expr, parseInferenceContext());
        // The parser's floor for "no narrowing" is the unknown sort "_", not null.
        return inferred != null ? inferred : IrSort.named("_");
    }

    /**
     * Builds the inference context from the parser's live scope maps. Mirrors the
     * old per-case lookup order: a Var resolves in {@code currentScope} over
     * {@code declaredTopLevelLets}; a 0-arg Call resolves in
     * {@code declaredFunctionReturns} over {@code declaredTopLevelLets} (a top-level
     * let lowers to a 0-arg call). Method/operator returns live in
     * {@code declaredFunctionReturns} keyed {@code Type.method} / the operator symbol.
     * Null-valued entries are stripped ({@link InferenceContext}'s canonical
     * constructor rejects nulls).
     */
    private InferenceContext parseInferenceContext() {
        Map<String, IrSort> typeEnv = new LinkedHashMap<>();
        typeEnv.putAll(declaredTopLevelLets);
        typeEnv.putAll(currentScope);  // local scope shadows top-level
        Map<String, IrSort> functionReturns = new LinkedHashMap<>();
        functionReturns.putAll(declaredTopLevelLets);
        functionReturns.putAll(declaredFunctionReturns);  // declared returns win
        typeEnv.values().removeIf(java.util.Objects::isNull);
        functionReturns.values().removeIf(java.util.Objects::isNull);
        return new InferenceContext(typeEnv, functionReturns, declaredStructs, Map.of(), Map.of());
    }


    // --- Struct declarations ---
    // Form: `struct Name(field:Sort, field:Sort, ...)`
    //
    // Desugars to an IrStmt.TypeAlias whose aliased sort is an IrSort.Structural.
    // The struct is also recorded in {@link #declaredStructs} so that subsequent
    // uses (`[Name(x, y)]` destructure, `[Name(field:val)]` constraint) can
    // resolve field names without re-stating their types. Slice-5 restriction:
    // the struct must be declared before any use that omits field types.

    // --- Trait impl blocks ---
    // Form: `assign trait TypeName:TraitName { method-decls... }`
    //
    // Each method inside the braces has the compact form
    // `name(params):returnSort -> body` (no `method` keyword, no `Type.`
    // prefix — both implicit from the block header). The parser desugars
    // to an `IrStmt.TraitImpl` whose methods are full FunctionDecls with
    // self prepended and the type-qualified name.

    /**
     * Dispatches the two {@code assign} forms by the keyword after {@code assign}:
     * {@code assign trait …} (trait impl) vs {@code assign proof …}
     * (return-refinement proof).
     */
    private IrStmt parseAssign() throws ParseException {
        AltToken next = peek(1);  // the token after "assign"
        if (next.kind() == AltToken.Kind.IDENT && next.text().equals("proof")) {
            return parseAssignProof();
        }
        return parseAssignTrait();
    }

    /**
     * Parses {@code assign proof NAME(params):PIN}. The PIN is either a
     * case-function proof {@code [ (match s …) -> [Sort] ]} — whose ordered
     * {@code [guard] -> expr} arms cut the domain into regions the engine can
     * discharge (the arm bodies are inert; only the guards matter) — or a bare
     * sort {@code [Sort]} for a bodyless proof asking for native discharge. The
     * granted sort is the return refinement the proof proves; the target
     * function declares only a base return.
     */
    private IrStmt parseAssignProof() throws ParseException {
        AltToken start = expectKeyword("assign");
        expectKeyword("proof");
        String functionName = parseDottedName();
        expect(AltToken.Kind.LPAREN);
        List<IrParam> params = parseParamList(AltToken.Kind.RPAREN);
        expect(AltToken.Kind.RPAREN);
        drainParamDestructures();  // proof params don't use `.{}`; keep the buffer clean
        expect(AltToken.Kind.COLON);

        // Push proof params into scope so the case-function's `[@…]` arms infer
        // the subject's base sort, exactly as a function body's match arms do.
        Map<String, IrSort> savedScope = new LinkedHashMap<>(currentScope);
        currentScope.clear();
        for (IrParam p : params) currentScope.put(p.name(), p.sort());
        try {
            // Case-function pin `[ (match s …) -> [Sort] ]`: the `[` is followed by
            // `(`. A bare sort (any other token after `[`, or no `[`) is a bodyless
            // proof.
            if (peek().kind() == AltToken.Kind.LBRACKET && peek(1).kind() == AltToken.Kind.LPAREN) {
                consume();  // the pin's opening '['
                expect(AltToken.Kind.LPAREN);
                IrExpr body = parseMatch();
                expect(AltToken.Kind.RPAREN);
                expect(AltToken.Kind.ARROW);
                IrSort grantedReturn = parseSort();
                AltToken close = expect(AltToken.Kind.RBRACKET);
                return new IrStmt.ReturnProof(
                        functionName, params, grantedReturn, body, start.spanTo(close));
            }
            IrSort grantedReturn = parseSort();
            return new IrStmt.ReturnProof(functionName, params, grantedReturn, null, start.origin());
        } finally {
            currentScope.clear();
            currentScope.putAll(savedScope);
        }
    }

    private IrStmt parseAssignTrait() throws ParseException {
        AltToken start = expectKeyword("assign");
        expectKeyword("trait");
        AltToken typeNameTok = expect(AltToken.Kind.IDENT);
        // The impl's own `[type T]` binder (`assign trait Element[type T]:…`,
        // docs/type-parameters.md §2.1): it INTRODUCES T as a variable into the
        // impl's scope — the toggle that tells `Stream[T]` apart from a concrete
        // `Stream[SomeType]`. Reuses the decl-site slot parser.
        Map<String, IrSort> implTypeParams = peek().kind() == AltToken.Kind.LBRACKET
                ? parseTypeParamSlot()
                : new LinkedHashMap<>();
        expect(AltToken.Kind.COLON);
        AltToken traitNameTok = expect(AltToken.Kind.IDENT);
        // Type arguments applied to the trait (`…:Stream[T]` / `…:Stream[Int]`):
        // a plain sort list, not a binder — the `[T]` here is a USE of the
        // variable bound by the slot above (or a concrete type).
        List<IrSort> traitTypeArgs = new ArrayList<>();
        if (peek().kind() == AltToken.Kind.LBRACKET) {
            expect(AltToken.Kind.LBRACKET);
            boolean firstArg = true;
            while (peek().kind() != AltToken.Kind.RBRACKET) {
                if (!firstArg) expect(AltToken.Kind.COMMA);
                traitTypeArgs.add(parseSort());
                firstArg = false;
            }
            expect(AltToken.Kind.RBRACKET);
        }
        expect(AltToken.Kind.LBRACE);

        String typeName = typeNameTok.text();
        // `this` is the (possibly parametric) subject — `Element[T]` when the
        // impl binds `[type T]`, so the self-type carries its variables.
        List<IrSort> selfArgs = new ArrayList<>(implTypeParams.size());
        for (String tp : implTypeParams.keySet()) {
            selfArgs.add(new IrSort.Named(tp, typeNameTok.origin()));
        }
        IrSort selfSort = new IrSort.Named(typeName, selfArgs, typeNameTok.origin());

        List<IrStmt.FunctionDecl> methods = new ArrayList<>();
        List<IrStmt.FunctionDecl> attributeProducers = new ArrayList<>();
        // Associated-type bindings: `type X = [Sort]` — supplies the concrete
        // type for the trait's `type X` member. Bound with `=` (a type, not a
        // `->` producer value).
        Map<String, IrSort> typeBindings = new LinkedHashMap<>();
        while (peek().kind() != AltToken.Kind.RBRACE) {
            if (peek().kind() == AltToken.Kind.IDENT && peek().text().equals("type")) {
                consume();  // `type`
                AltToken varName = expect(AltToken.Kind.IDENT);
                expect(AltToken.Kind.EQUALS);   // `=`
                IrSort boundType = parseSort();  // the supplied type (`[Int]`)
                if (typeBindings.containsKey(varName.text())) {
                    throw new ParseException(
                            "Duplicate associated-type binding '" + varName.text()
                                    + "' in trait impl",
                            varName.origin());
                }
                typeBindings.put(varName.text(), boundType);
                continue;
            }
            // A member is a METHOD if `(` follows its name (`ping():Int -> …`)
            // and an ATTRIBUTE producer if `:` does (`weight:Int -> …`). Both
            // are `member <- producer` arrows; only the `()` differs.
            if (peek(1).kind() == AltToken.Kind.COLON) {
                attributeProducers.add(
                        parseTraitImplAttribute(typeName, traitNameTok.text(), selfSort));
            } else {
                methods.add(parseTraitImplMethod(typeName, selfSort));
            }
        }
        AltToken close = expect(AltToken.Kind.RBRACE);
        return new IrStmt.TraitImpl(
                typeName, traitNameTok.text(), methods, attributeProducers,
                typeBindings, implTypeParams, traitTypeArgs, start.spanTo(close));
    }

    /**
     * Parses one attribute producer inside an {@code assign trait} block.
     * Surface form: {@code name:Sort -> producer}. The producer is an
     * expression over {@code this} (the instance); a trait attribute is a
     * computed projection. Lowered to a 0-user-arg {@link IrStmt.FunctionDecl}
     * {@code Type.name(this):Sort -> producer} so it registers in dispatch like
     * a 0-arg method and rides the return-refinement gate (the {@code Sort}
     * refinement, e.g. {@code [Int:@>0]}, is verified against the producer).
     */
    private IrStmt.FunctionDecl parseTraitImplAttribute(
            String typeName, String traitName, IrSort selfSort) throws ParseException {
        AltToken nameTok = expect(AltToken.Kind.IDENT);
        if (KEYWORDS.contains(nameTok.text())) {
            throw new ParseException(
                    "Cannot use keyword '" + nameTok.text() + "' as an attribute name",
                    nameTok.origin());
        }
        expect(AltToken.Kind.COLON);
        IrSort declaredSort = parseSort();
        expect(AltToken.Kind.ARROW);

        // The impl states the base type; the trait CONTRACT supplies the
        // refinement (`weight:Int -> 1` is checked against the contract's
        // `[Int:@>0]`, not bare `Int`). Adopt the contract attribute sort as the
        // producer's return obligation when the trait was declared earlier in
        // this file (the common case); otherwise fall back to the impl-declared
        // sort. `declaredFunctionReturns` holds `Trait.attr -> contractSort`
        // from the trait's `Type{…}` declaration.
        IrSort contractSort = declaredFunctionReturns.get(traitName + "." + nameTok.text());
        IrSort obligation = contractSort != null ? contractSort : declaredSort;

        List<IrParam> params = List.of(new IrParam("this", selfSort));
        Map<String, IrSort> savedScope = new LinkedHashMap<>(currentScope);
        currentScope.clear();
        currentScope.put("this", selfSort);
        try {
            IrExpr producer = parseExpr();
            String qualified = typeName + "." + nameTok.text();
            declaredFunctionReturns.put(qualified, obligation);
            return new IrStmt.FunctionDecl(
                    qualified, params, obligation, producer, nameTok.origin());
        } finally {
            currentScope.clear();
            currentScope.putAll(savedScope);
        }
    }

    /**
     * Parses one method inside an {@code assign trait} block.
     * Surface form: {@code methodName(params):returnSort -> body}.
     * The parser prepends a {@code self:TypeName} param and qualifies the
     * method's name to {@code TypeName.methodName}. Body parses with self
     * + user params in {@link #currentScope}.
     */
    private IrStmt.FunctionDecl parseTraitImplMethod(String typeName, IrSort selfSort)
            throws ParseException {
        AltToken nameTok = expect(AltToken.Kind.IDENT);
        if (KEYWORDS.contains(nameTok.text())) {
            throw new ParseException(
                    "Cannot use keyword '" + nameTok.text() + "' as a method name",
                    nameTok.origin());
        }
        expect(AltToken.Kind.LPAREN);
        List<IrParam> userParams = parseParamList(AltToken.Kind.RPAREN);
        expect(AltToken.Kind.RPAREN);
        List<ParamDestructure> destrs = drainParamDestructures();
        expect(AltToken.Kind.COLON);
        IrSort returnSort = parseSort();
        expect(AltToken.Kind.ARROW);

        List<IrParam> allParams = new ArrayList<>(userParams.size() + 1);
        allParams.add(new IrParam("this", selfSort));
        allParams.addAll(userParams);

        Map<String, IrSort> savedScope = new LinkedHashMap<>(currentScope);
        currentScope.clear();
        for (IrParam p : allParams) currentScope.put(p.name(), p.sort());
        bindParamDestructures(destrs);
        try {
            IrExpr body = wrapParamDestructures(parseExpr(), destrs);
            String qualified = typeName + "." + nameTok.text();
            declaredFunctionReturns.put(qualified, returnSort);
            return new IrStmt.FunctionDecl(
                    qualified, allParams, returnSort, body, nameTok.origin());
        } finally {
            currentScope.clear();
            currentScope.putAll(savedScope);
        }
    }

    /**
     * Proof declaration: {@code proof <functionName> = <structTree>}. The RHS
     * is an ordinary expression — a {@code Leaf}/{@code Split} struct-literal
     * tree — captured <b>unevaluated</b> as {@link IrStmt.Proof}'s tree. The
     * return-refinement gate translates and validates it; nothing here checks
     * its shape (so {@code Split}'s predicate stays a symbolic comparison). The
     * {@code Leaf}/{@code Split} structs must be declared earlier so the RHS
     * parses through the struct-literal path into an {@link IrExpr.Record}.
     */
    private IrStmt parseProof() throws ParseException {
        AltToken start = expectKeyword("proof");
        String functionName = parseDottedName();
        expect(AltToken.Kind.EQUALS);
        IrExpr tree = parseExpr();
        return new IrStmt.Proof(functionName, tree, start.origin());
    }

    private IrStmt parseStruct() throws ParseException {
        AltToken start = expectKeyword("struct");
        AltToken nameTok = expect(AltToken.Kind.IDENT);
        // Optional `[type T, …]` — the type-parameter slot (docs/type-parameters.md
        // §2.1), directly after the name and before the is-a/fields. A bare `[`
        // here is unambiguous: the is-a uses `:[…]` (colon), fields use `(…)`.
        Map<String, IrSort> typeParams = peek().kind() == AltToken.Kind.LBRACKET
                ? parseTypeParamSlot()
                : new LinkedHashMap<>();
        // Optional `:[Base:rel]` — the is-a relationship (S2). The parsed sort
        // (Named / Refined / Union) is stored whole on the Structural; its
        // refinement predicate, if any, is the demotion morphism. SortChecker
        // validates that the base resolves and the morphism is total.
        IrSort baseSort = null;
        if (peek().kind() == AltToken.Kind.COLON) {
            consume();
            baseSort = parseSort();
        }
        expect(AltToken.Kind.LPAREN);
        Map<String, IrSort> members = new LinkedHashMap<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RPAREN) {
            if (!first) expect(AltToken.Kind.COMMA);
            AltToken fieldName = expect(AltToken.Kind.IDENT);
            expect(AltToken.Kind.COLON);
            IrSort fieldSort = parseSort();
            members.put(fieldName.text(), fieldSort);
            first = false;
        }
        AltToken end = expect(AltToken.Kind.RPAREN);
        Origin origin = start.spanTo(end);
        if (sibarum.pontif.ir.NativeConstructors.has(nameTok.text())) {
            throw new ParseException(
                    "'" + nameTok.text() + "' is a native type — its constructor "
                            + "is built in and cannot be redeclared",
                    nameTok.origin());
        }
        IrSort.Structural structSort =
                new IrSort.Structural(nameTok.text(), members, baseSort, typeParams, origin);
        declaredStructs.put(nameTok.text(), structSort);
        return new IrStmt.TypeAlias(nameTok.text(), structSort, origin);
    }

    /**
     * Parses the {@code [type T, type R, …]} type-parameter slot that sits
     * directly after a struct/function/trait name (docs/type-parameters.md §2.1).
     * Returns name → bound, a {@code null} bound meaning an unbounded
     * {@code type T}. Assumes the next token is the opening {@code [}.
     */
    private static final java.util.Set<String> PRIMITIVE_SORTS =
            java.util.Set.of("Int", "Bool", "Char", "Decimal", "String");

    /**
     * Collects inline-destructured type variables (docs/type-parameters.md §2.4)
     * from {@code sort} into {@code typeParams}: a bare name appearing as a type
     * ARGUMENT (`Box[T]`) that names no known sort — not a primitive, declared
     * struct, trait, or alias, and not already a type parameter — is a fresh
     * destructured variable (bound unbounded, {@code null}). Recurses through
     * nesting; only argument positions are collected, never a sort's own head.
     */
    private void collectInlineTypeVars(IrSort sort, Map<String, IrSort> typeParams) {
        if (!(sort instanceof IrSort.Named n)) return;
        for (IrSort arg : n.typeArgs()) {
            if (arg instanceof IrSort.Named an && an.typeArgs().isEmpty()
                    && !PRIMITIVE_SORTS.contains(an.name())
                    && !declaredStructs.containsKey(an.name())
                    && !declaredTraits.contains(an.name())
                    && !declaredSortAliases.contains(an.name())
                    && !typeParams.containsKey(an.name())) {
                typeParams.put(an.name(), null);
            }
            collectInlineTypeVars(arg, typeParams);
        }
    }

    private Map<String, IrSort> parseTypeParamSlot() throws ParseException {
        expect(AltToken.Kind.LBRACKET);
        Map<String, IrSort> params = new LinkedHashMap<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RBRACKET) {
            if (!first) expect(AltToken.Kind.COMMA);
            if (peek().kind() != AltToken.Kind.IDENT || !peek().text().equals("type")) {
                throw new ParseException(
                        "type-parameter slot expects `type NAME`; got '" + peek().text() + "'",
                        peek().origin());
            }
            consume();  // `type`
            AltToken nameTok = expect(AltToken.Kind.IDENT);
            IrSort bound = null;
            if (peek().kind() == AltToken.Kind.COLON) {
                consume();  // `:`
                bound = parseSort();   // the bound — `[type T:R]`
            }
            if (params.containsKey(nameTok.text())) {
                throw new ParseException(
                        "Duplicate type parameter '" + nameTok.text() + "'", nameTok.origin());
            }
            params.put(nameTok.text(), bound);
            first = false;
        }
        expect(AltToken.Kind.RBRACKET);
        return params;
    }

    // --- Sorts ---
    // Slice 2 scope:
    //   - Bare ident: `Int`        ≡  `[Int]`     → IrSort.Named
    //   - Bracketed:  `[Int]`                      → IrSort.Named
    //   - Refined:    `[Base:pred]`                → IrSort.Refined(Base, cookedPred)
    //
    // The predicate goes through `applyPredicateSugar`:
    //   - Top-level comparison (==, !=, <, <=, >, >=) → used as-is
    //   - Top-level `|` or `&`                        → recurse into operands
    //   - Anything else                               → wrap as `@ == EXPR`
    //
    // So `[Int:0]` ≡ `[Int:@==0]`, `[Int:n*2]` ≡ `[Int:@==n*2]`,
    // `[Int:0|1]` ≡ `[Int:@==0 | @==1]`, but `[Int:@>0]` and
    // `[Int:@>0 & @<10]` stay untouched.

    /**
     * Speculatively reads a cast target — a sort immediately followed by `:` —
     * from inside an opening `(` (the cursor sits just past the LPAREN). On a
     * match it consumes the sort and the `:` and returns the sort; otherwise it
     * restores the cursor and returns {@code null} so the caller parses the
     * parens as an ordinary grouping/tuple. Gated by the capitalization law: a
     * cast target is a `[…]` refinement or a Capitalized type name, never a
     * lowercase binder name — this keeps `(x : …)` from ever reading as a cast.
     */
    private IrSort tryParseCastTarget() {
        AltToken t = peek();
        boolean plausible = t.kind() == AltToken.Kind.LBRACKET
                || (t.kind() == AltToken.Kind.IDENT
                        && !t.text().isEmpty()
                        && Character.isUpperCase(t.text().charAt(0)));
        if (!plausible) {
            return null;
        }
        int save = pos;
        try {
            IrSort sort = parseSort();
            if (peek().kind() == AltToken.Kind.COLON) {
                consume();   // the cast colon
                return sort;
            }
        } catch (ParseException e) {
            // Not a sort after all — fall through to grouping.
        }
        pos = save;
        return null;
    }

    public IrSort parseSort() throws ParseException {
        AltToken t = peek();
        // Bare tuple sort `(S0, S1, …)` — the bracket-free spelling of `[(…)]`.
        // A param type written `t:(Inner, Int)` (or a tuple PATTERN `(i, k)` in a
        // braceless match arm) starts at `(`; `parseBracketSort` only reaches the
        // tuple body after a `[`, so accept the bare form here for parity. The
        // body grammar (type elements vs positional binders) is governed by
        // parsingTuplePattern, exactly as in the bracketed path.
        if (t.kind() == AltToken.Kind.LPAREN) {
            return parseTupleSortBody(t);
        }
        if (t.kind() == AltToken.Kind.LBRACKET) {
            // In-type pipeline (S8): `[let x:S = E -> … -> Base:@==witness]` — a
            // staged synthesis directive. The leading `let` is unambiguous (no
            // sort starts with the `let` keyword).
            if (peek(1).kind() == AltToken.Kind.IDENT && peek(1).text().equals("let")) {
                return parsePipelineSort();
            }
            return parseBracketSort();
        }
        if (t.kind() == AltToken.Kind.IDENT) {
            // `this.type` — the self-type (the receiver's runtime-actual concrete
            // type; docs/associated-types.md §7.3). A reserved sentinel sort:
            // `this` is the instance, `.type` projects its type. Meaningful only
            // in a trait contract's member sorts (scoped there by SortChecker);
            // elsewhere it validates as an unknown sort.
            if (t.text().equals("this")
                    && peek(1).kind() == AltToken.Kind.DOT
                    && peek(2).kind() == AltToken.Kind.IDENT
                    && peek(2).text().equals("type")) {
                consume();              // this
                consume();              // .
                AltToken typeTok = consume();   // type
                return new IrSort.Named(IrSort.SELF_TYPE, t.spanTo(typeTok));
            }
            // `Type{...}` — an anonymous trait sort literal, usable in ANY sort
            // position (param/return/nested in unions/refinements), part of the
            // unified type-spec system (parallel to `[Int:@>0]`). The
            // `trait NAME{…}` declarator (parseTrait) names one; this is the
            // anonymous form. (Naming a trait via `let NAME:Type{…}` is redirected
            // to `trait` in parseLet, but the anonymous sort stays first-class.)
            if (t.text().equals("Type") && peek(1).kind() == AltToken.Kind.LBRACE) {
                consume();  // "Type"
                return parseTraitMembers(t);
            }
            // `Name{e1, e2, …}` — a construction-pin return sort over a declared
            // struct (S5): desugars to `[Name:@ == Name(e1, …)]`, so spec-only
            // synthesis derives the body `Name(e1, …)` via the @==EXPR path.
            if (peek(1).kind() == AltToken.Kind.LBRACE && declaredStructs.containsKey(t.text())) {
                return parseConstructionPinSort();
            }
            // `Name[arg, …]` — a parametric type application
            // (docs/type-parameters.md §2.3): `Element[Int]`, `Element[T]`. The
            // args are sorts. A bare `name[…]` (no `$`) is free here —
            // metareferences are `$`-prefixed. Distinct from the `[type T]`
            // declaration slot, which is parsed by parseTypeParamSlot at decl
            // sites, never inside a sort.
            if (peek(1).kind() == AltToken.Kind.LBRACKET) {
                AltToken nameTok = consume();      // the head name
                expect(AltToken.Kind.LBRACKET);
                List<IrSort> typeArgs = new ArrayList<>();
                boolean first = true;
                while (peek().kind() != AltToken.Kind.RBRACKET) {
                    if (!first) expect(AltToken.Kind.COMMA);
                    typeArgs.add(parseSort());
                    first = false;
                }
                AltToken close = expect(AltToken.Kind.RBRACKET);
                return new IrSort.Named(nameTok.text(), typeArgs, nameTok.spanTo(close));
            }
            // Bare-ident sugar: `Int` ≡ `[Int]`.
            AltToken nameTok = consume();
            return new IrSort.Named(nameTok.text(), nameTok.origin());
        }
        throw new ParseException(
                "Expected a sort (bare ident or '[...]'); got " + t.kind() + " '" + t.text() + "'",
                t.origin());
    }

    /**
     * Construction-pin return sort: {@code Name{e1, e2, …}} over a declared
     * struct desugars to {@code [Name:@ == Name(e1, …)]}, the values mapped
     * POSITIONALLY onto the struct's declared fields. Spec-only synthesis then
     * derives the body {@code Name(e1, …)} through the existing {@code @==EXPR}
     * path — how {@code function promote(…):Point3D{x,y,z};} gets its body.
     */
    /**
     * In-type pipeline (S8): {@code [let x:S = E -> … -> Base:@==witness]} — a
     * staged synthesis directive, the type-position equivalent of writing the
     * body with {@code ->}. Desugars to {@code [Base:@ == (let x = E in … in
     * witness)]}, riding the existing {@code @==EXPR} synthesis path; the
     * let-stages' expressions resolve names normally (params, global functions),
     * so no {@code requires} import is needed.
     */
    private IrSort parsePipelineSort() throws ParseException {
        AltToken open = expect(AltToken.Kind.LBRACKET);
        List<String> names = new ArrayList<>();
        List<IrSort> sorts = new ArrayList<>();
        List<IrExpr> exprs = new ArrayList<>();
        while (peek().kind() == AltToken.Kind.IDENT && peek().text().equals("let")) {
            consume();  // let
            AltToken n = expect(AltToken.Kind.IDENT);
            expect(AltToken.Kind.COLON);
            IrSort s = parseSort();
            expect(AltToken.Kind.EQUALS);
            IrExpr e = parseExpr();
            expect(AltToken.Kind.ARROW);
            names.add(n.text());
            sorts.add(s);
            exprs.add(e);
        }
        IrSort finalSort = parseBracketBranch();
        AltToken close = expect(AltToken.Kind.RBRACKET);
        IrExpr witness = tryDeriveBodyFromReturnSort(finalSort);
        if (witness == null) {
            throw new ParseException(
                    "in-type pipeline's final stage must be a value pin "
                            + "('Base:@==EXPR') — got " + finalSort, close.origin());
        }
        IrExpr chain = witness;
        for (int i = names.size() - 1; i >= 0; i--) {
            chain = new IrExpr.LetIn(names.get(i), sorts.get(i), exprs.get(i), chain, open.origin());
        }
        String base = baseSortName(finalSort);
        // `@ == let-chain` is the definition; any non-`@==` conjuncts of the final
        // pin (e.g. `@>0` in `[Int:@==r & @>0]`) ride along as the postcondition
        // the gate proves — define and verify in one pin.
        IrExpr defPred = new IrExpr.BinOp(
                IrExpr.Op.EQ, new IrExpr.SelfRef(open.origin()), chain, open.origin());
        IrExpr postcond = finalSort instanceof IrSort.Refined fr
                ? removeDefinitionConjunct(fr.predicate())
                : null;
        IrExpr fullPred = postcond == null
                ? defPred
                : new IrExpr.BinOp(IrExpr.Op.AND, defPred, postcond, open.origin());
        return new IrSort.Refined(base, fullPred, open.spanTo(close));
    }

    private IrSort parseConstructionPinSort() throws ParseException {
        AltToken nameTok = expect(AltToken.Kind.IDENT);
        IrSort.Structural struct = declaredStructs.get(nameTok.text());
        List<String> fields = new ArrayList<>(struct.members().keySet());
        expect(AltToken.Kind.LBRACE);
        List<IrExpr> values = new ArrayList<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RBRACE) {
            if (!first) expect(AltToken.Kind.COMMA);
            values.add(parseExpr());
            first = false;
        }
        expect(AltToken.Kind.RBRACE);
        if (values.size() != fields.size()) {
            throw new ParseException(
                    "construction pin '" + nameTok.text() + "{…}' has " + values.size()
                            + " value(s) but '" + nameTok.text() + "' has " + fields.size()
                            + " field(s) " + fields,
                    nameTok.origin());
        }
        Map<String, IrExpr> members = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) members.put(fields.get(i), values.get(i));
        IrExpr construction = new IrExpr.Record(nameTok.text(), members, nameTok.origin());
        IrExpr pin = new IrExpr.BinOp(IrExpr.Op.EQ,
                new IrExpr.SelfRef(nameTok.origin()), construction, nameTok.origin());
        return new IrSort.Refined(nameTok.text(), pin, nameTok.origin());
    }

    /**
     * Parses a trait declaration: {@code trait NAME[type T, …]{ members }}.
     * Lowers to a {@link IrStmt.TypeAlias} binding NAME to an {@link IrSort.Trait}
     * — the dedicated declarator that replaced the retired {@code let NAME:Type{…}}
     * form. The optional {@code [type T, …]} slot makes the trait parametric
     * (docs/type-parameters.md §2.1). Each contract method/attribute is also
     * registered in {@code declaredFunctionReturns} under {@code NAME.member} so
     * method-call routing on trait-typed receivers (e.g. {@code d.quack()} where
     * {@code d:Duck}) finds them.
     */
    private IrStmt parseTrait() throws ParseException {
        AltToken start = expectKeyword("trait");
        AltToken nameTok = expect(AltToken.Kind.IDENT);
        String name = nameTok.text();
        // Optional `[type T, …]` parametric slot — directly after the name, before
        // the `{` body. Unambiguous: a trait body is `{…}`, the slot is `[…]`.
        Map<String, IrSort> typeParams = peek().kind() == AltToken.Kind.LBRACKET
                ? parseTypeParamSlot()
                : new LinkedHashMap<>();
        // Optional `: BaseTrait` — trait extension (WAR(stream)). `trait B : A {…}`
        // makes B extend A: an impl of B must satisfy A's contract too, and a B is-a A
        // (validated/registered by SortChecker + the trait registry, where every trait
        // is visible — the base may be forward-declared or imported, so not checked here).
        String baseTrait = null;
        if (peek().kind() == AltToken.Kind.COLON) {
            expect(AltToken.Kind.COLON);
            baseTrait = expect(AltToken.Kind.IDENT).text();
        }
        IrSort.Trait body = parseTraitMembers(start);
        if (peek().kind() == AltToken.Kind.EQUALS) {
            throw new ParseException(
                    "A `trait` declaration is type-level only and cannot have a value "
                            + "(`= …`).", peek().origin());
        }
        declaredTraits.add(name);
        IrSort.Trait named = new IrSort.Trait(
                name, body.methods(), body.attributes(), body.associatedTypes(),
                typeParams, body.operators(), baseTrait, body.origin());
        for (Map.Entry<String, IrSort.Method> e : named.methods().entrySet()) {
            declaredFunctionReturns.put(name + "." + e.getKey(), e.getValue().returnSort());
        }
        // A data attribute is a computed projection — accessing it through the
        // trait view (`d.weight`, d:Duck) routes to the satisfier's
        // `Type.weight(this)` producer, so register `TraitName.attr` as a 0-arg
        // accessor return (mirrors the method registration above).
        for (Map.Entry<String, IrSort> e : named.attributes().entrySet()) {
            declaredFunctionReturns.put(name + "." + e.getKey(), e.getValue());
        }
        return new IrStmt.TypeAlias(name, named, named.origin());
    }

    /**
     * Parses the {@code { member, ... }} brace block of a trait sort, after the
     * head token. Used two ways: by {@link #parseTrait} for a named declaration
     * ({@code trait NAME{...}}, name patched in afterward), and by the sort parser
     * for the anonymous {@code Type{...}} sort literal usable in any sort position.
     * A member is {@code name:[Method(...):Ret]} (a method), {@code name:Sort}
     * (a data attribute), {@code op:[Dispatch(...)]} (an operator contract), or
     * {@code type X[:Bound]} (an associated type). The returned
     * {@link IrSort.Trait} has a placeholder name until a declaration patches it.
     */
    private IrSort.Trait parseTraitMembers(AltToken headTok) throws ParseException {
        expect(AltToken.Kind.LBRACE);
        Map<String, IrSort.Method> methods = new LinkedHashMap<>();
        Map<String, IrSort> attributes = new LinkedHashMap<>();
        // Associated types — member name → bound (null = unbounded `type X`; a
        // sort = the bound `type X:R`). LinkedHashMap permits the null value.
        Map<String, IrSort> associatedTypes = new LinkedHashMap<>();
        // Operator contract members — `+:[Dispatch(this.type, this.type):this.type]`
        // (dispatch-unification B1). Keyed by the operator symbol.
        Map<String, IrSort.Dispatch> operators = new LinkedHashMap<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RBRACE) {
            if (!first) expect(AltToken.Kind.COMMA);
            // An OPERATOR member key — `+:[Dispatch(this.type, this.type):this.type]`.
            // The key is an operator symbol (not an identifier); the sort must be a
            // homogeneous self-typed Dispatch (the v1 bound). This is a mechanism-1
            // bound the compiler verifies at `assign trait`, not a hosted method.
            if (peek().kind() == AltToken.Kind.OP) {
                AltToken opTok = consume();
                if (!OVERLOADABLE_OPS.contains(opTok.text())) {
                    throw new ParseException(
                            "'" + opTok.text() + "' is not an overloadable operator, so it "
                                    + "cannot be a trait contract member", opTok.origin());
                }
                expect(AltToken.Kind.COLON);
                IrSort opSort = parseSort();
                if (!(opSort instanceof IrSort.Dispatch dispatch)) {
                    throw new ParseException(
                            "Operator contract member '" + opTok.text() + "' must be a "
                                    + "[Dispatch(...)] sort (e.g. "
                                    + "[Dispatch(this.type, this.type):this.type]); operators "
                                    + "are mechanism-1 bounds, never methods", opTok.origin());
                }
                requireHomogeneousSelfOperatorContract(opTok, dispatch);
                if (methods.containsKey(opTok.text())
                        || attributes.containsKey(opTok.text())
                        || associatedTypes.containsKey(opTok.text())
                        || operators.containsKey(opTok.text())) {
                    throw new ParseException(
                            "Duplicate member '" + opTok.text() + "' in trait body",
                            opTok.origin());
                }
                operators.put(opTok.text(), dispatch);
                first = false;
                continue;
            }
            // `type X` / `type X:Bound` — an associated type declared with the
            // `type` declarator (a type-level member, not a value member).
            if (peek().kind() == AltToken.Kind.IDENT && peek().text().equals("type")) {
                consume();  // `type`
                AltToken varName = expect(AltToken.Kind.IDENT);
                if (methods.containsKey(varName.text())
                        || attributes.containsKey(varName.text())
                        || associatedTypes.containsKey(varName.text())
                        || operators.containsKey(varName.text())) {
                    throw new ParseException(
                            "Duplicate member '" + varName.text() + "' in trait body",
                            varName.origin());
                }
                IrSort bound = null;
                if (peek().kind() == AltToken.Kind.COLON) {
                    consume();  // `:`
                    bound = parseSort();   // the bound — `type X:R`
                }
                associatedTypes.put(varName.text(), bound);
                first = false;
                continue;
            }
            AltToken memberName = expect(AltToken.Kind.IDENT);
            expect(AltToken.Kind.COLON);
            IrSort memberSort = parseSort();
            if (methods.containsKey(memberName.text())
                    || attributes.containsKey(memberName.text())
                    || associatedTypes.containsKey(memberName.text())
                    || operators.containsKey(memberName.text())) {
                throw new ParseException(
                        "Duplicate member '" + memberName.text() + "' in trait body",
                        memberName.origin());
            }
            // A member is a METHOD if its sort is [Method(...):Ret]; otherwise
            // it is a typed data ATTRIBUTE — a value sort the satisfier must
            // supply (a field or a computed producer). Both live in `Type{…}`.
            if (memberSort instanceof IrSort.Method fn) {
                methods.put(memberName.text(), fn);
            } else {
                attributes.put(memberName.text(), memberSort);
            }
            first = false;
        }
        AltToken close = expect(AltToken.Kind.RBRACE);
        // Placeholder name; parseTrait patches it with the declared name.
        return new IrSort.Trait(
                "_pending", methods, attributes, associatedTypes, Map.of(), operators,
                headTok.spanTo(close));
    }

    /**
     * Enforces the v1 scope for an operator contract member: a <b>homogeneous,
     * self-typed</b> binary dispatch — {@code (this.type, this.type):this.type}.
     * Mixed-operand / promotion contracts (e.g. {@code (this.type, Int)}) are a
     * later slice; until then they fail here with a clear error rather than being
     * silently stored and ignored (no verifier consumes them yet).
     */
    private void requireHomogeneousSelfOperatorContract(AltToken opTok, IrSort.Dispatch d)
            throws ParseException {
        boolean homogeneous = d.keySorts().size() == 2
                && isSelfType(d.keySorts().get(0))
                && isSelfType(d.keySorts().get(1))
                && isSelfType(d.returnSort());
        if (!homogeneous) {
            throw new ParseException(
                    "Operator contract member '" + opTok.text() + "' must be homogeneous over "
                            + "the self type — [Dispatch(this.type, this.type):this.type]. "
                            + "Mixed-operand or promotion contracts are not supported yet.",
                    opTok.origin());
        }
    }

    private static boolean isSelfType(IrSort sort) {
        return sort instanceof IrSort.Named n && n.name().equals(IrSort.SELF_TYPE);
    }

    private IrSort parseBracketSort() throws ParseException {
        AltToken open = expect(AltToken.Kind.LBRACKET);
        AltToken first = peek();

        // Tuple sort: `[(S0, S1, ...)]` — an anonymous positional aggregate.
        // A leading `(` here is unambiguous (the contextual `[pred]` form below
        // never starts with `(`). Lowers onto the record substrate as a
        // structural sort named "_tuple" with positional keys _0.._n.
        if (first.kind() == AltToken.Kind.LPAREN) {
            IrSort tuple = parseTupleSortBody(open);
            expect(AltToken.Kind.RBRACKET);
            return tuple;
        }

        // Contextual form: `[pred]` — no base, take from contextualBaseStack
        // (pushed by parseMatch when the scrutinee has an inferable sort).
        if (first.kind() != AltToken.Kind.IDENT
                && first.kind() != AltToken.Kind.LBRACKET) {
            String inferredBase = contextualBaseStack.isEmpty()
                    ? null
                    : contextualBaseStack.peek();
            if (inferredBase == null || inferredBase.isEmpty()) {
                throw new ParseException(
                        "Bracket-sort starts with " + first.kind() + " '" + first.text()
                                + "' but no contextual base is available — write [Base:pred] explicitly",
                        first.origin());
            }
            IrExpr pred = parseExpr();
            AltToken close = expect(AltToken.Kind.RBRACKET);
            IrExpr cooked = applyPredicateSugar(pred);
            return new IrSort.Refined(inferredBase, cooked, open.spanTo(close));
        }

        // Parse the first branch. Could be a nested `[...]` or a bare
        // ident with optional refinement / struct / function tail.
        IrSort firstBranch = parseBracketBranch();

        // Sort-level `|` or `&` between branches?
        if (peek().kind() == AltToken.Kind.OP
                && (peek().text().equals("|") || peek().text().equals("&"))) {
            String op = consume().text();
            List<IrSort> branches = new ArrayList<>();
            branches.add(firstBranch);
            branches.add(parseBracketBranch());
            while (peek().kind() == AltToken.Kind.OP && peek().text().equals(op)) {
                consume();
                branches.add(parseBracketBranch());
            }
            AltToken close = expect(AltToken.Kind.RBRACKET);
            return normalizeMultiBranch(branches, op, open.spanTo(close));
        }

        AltToken close = expect(AltToken.Kind.RBRACKET);
        return firstBranch;
    }

    /**
     * Parses a single sort element that can appear as a branch inside a
     * bracket-sort's {@code |}/{@code &}-joined list. Either a nested
     * {@code [...]} form (recurse) or a bare ident with optional
     * refinement, struct-fields, or function-sort tail.
     */
    private IrSort parseBracketBranch() throws ParseException {
        if (peek().kind() == AltToken.Kind.LBRACKET) {
            return parseBracketSort();
        }
        AltToken baseTok = expect(AltToken.Kind.IDENT);

        // Optional parametric application on the base — `Literal[Int]` inside a
        // bracket sort (docs/type-parameters.md §2.3): an is-a base
        // `[Literal[Int]]` (bare) or `[Literal[Int]:@.value==value]` (with the
        // demotion morphism). The args are sorts.
        List<IrSort> typeArgs = new ArrayList<>();
        if (peek().kind() == AltToken.Kind.LBRACKET) {
            expect(AltToken.Kind.LBRACKET);
            boolean firstArg = true;
            while (peek().kind() != AltToken.Kind.RBRACKET) {
                if (!firstArg) expect(AltToken.Kind.COMMA);
                typeArgs.add(parseSort());
                firstArg = false;
            }
            expect(AltToken.Kind.RBRACKET);
        }

        if (peek().kind() == AltToken.Kind.COLON) {
            consume();
            IrExpr pred = parseExpr();
            IrExpr cooked = applyPredicateSugar(pred);
            return new IrSort.Refined(baseTok.text(), typeArgs, cooked, baseTok.origin());
        }

        if (typeArgs.isEmpty() && peek().kind() == AltToken.Kind.LPAREN) {
            if (baseTok.text().equals("Method")) {
                return parseFunctionSortBody(baseTok);
            }
            if (baseTok.text().equals("Dispatch")) {
                // [Dispatch(Int):Int] — the metareference sort. Same body
                // grammar as Method; a different sort entirely (a dispatch is
                // a name-keyed candidate set, not a single body).
                IrSort.Method shape = parseFunctionSortBody(baseTok);
                return new IrSort.Dispatch(shape.paramSorts(), shape.returnSort(), baseTok.origin());
            }
            consume();  // LPAREN
            java.util.Set<String> literalFields = new java.util.LinkedHashSet<>();
            Map<String, String> renames = new LinkedHashMap<>();
            Map<String, IrSort> members =
                    parseStructFields(baseTok.text(), baseTok.origin(), literalFields, renames);
            expect(AltToken.Kind.RPAREN);
            IrSort.Structural structural = new IrSort.Structural(baseTok.text(), members, baseTok.origin());
            if (!literalFields.isEmpty()) {
                literalConstrainedFields.put(structural, literalFields);
            }
            if (!renames.isEmpty()) {
                destructureRenames.put(structural, renames);
            }
            // Imported struct: shape unknown at parse time — mark deferred so the
            // binding desugar is left to the post-link DestructureResolver.
            if (patternShapeFor(baseTok.text()) == null) {
                deferredStructPatterns.add(structural);
            }
            return structural;
        }

        // Bare name — `Int`, `Bool`, etc. — or a parametric application
        // `Literal[Int]` (typeArgs non-empty).
        return new IrSort.Named(baseTok.text(), typeArgs, baseTok.origin());
    }

    /**
     * Parses a tuple sort/pattern body {@code (E0, E1, ...)} — the enclosing
     * {@code [} is already consumed (passed as {@code open}); the {@code (} is
     * the current token. In a <b>type</b> position each element is a sort; in a
     * destructure/match <b>pattern</b> (signalled by {@link #parsingTuplePattern})
     * each element is a positional binder ident or {@code _} discard. Either
     * way the result is a structural sort named {@code "_tuple"} with positional
     * keys {@code _0 .. _n}. For patterns, binder names go into
     * {@link #destructureRenames} and {@code _} discards into
     * {@link #literalConstrainedFields} (the "occupies the slot, binds nothing"
     * set, verdict C), and member sorts are left as the {@code "_"} placeholder
     * to be resolved from the scrutinee. Arity must be >= 2.
     */
    /**
     * Parses a match/destructure pattern — a sort, but with
     * {@link #parsingTuplePattern} set so a tuple {@code (a, b)} reads its
     * elements as positional binders / {@code _} discards rather than as
     * component sorts. A constructor component ({@code (Traction(n,z), …)})
     * nests and binds via the recursive destructure desugar; a nested bare
     * tuple component still occupies-but-binds-nothing. Restores the flag after.
     */
    private IrSort parsePattern() throws ParseException {
        boolean prev = parsingTuplePattern;
        parsingTuplePattern = true;
        try {
            return parseSort();
        } finally {
            parsingTuplePattern = prev;
        }
    }

    /**
     * Verdict B for tuples: a tuple destructure pattern must match the
     * scrutinee's arity exactly — a pattern with fewer slots would silently
     * drop components (lying by omission), which the structural matcher's
     * width-subtyping would otherwise allow. Checked only when the scrutinee's
     * tuple arity is statically known; an unknown scrutinee sort can't be
     * checked here and falls to the runtime matcher.
     */
    private void checkTupleArity(IrExpr scrutinee, IrSort pattern) throws ParseException {
        if (!(pattern instanceof IrSort.Structural sp) || !TUPLE_SENTINEL.equals(sp.name())) {
            return;
        }
        if (inferMaximalSort(scrutinee) instanceof IrSort.Structural ss
                && TUPLE_SENTINEL.equals(ss.name())
                && ss.members().size() != sp.members().size()) {
            throw new ParseException(
                    "Tuple pattern has " + sp.members().size() + " slot(s) but the value is a "
                            + ss.members().size() + "-tuple — a positional pattern must match every "
                            + "slot; use '_' to discard the unwanted ones (e.g. [(a, _, c)]).",
                    sp.origin());
        }
    }

    private IrSort parseTupleSortBody(AltToken open) throws ParseException {
        expect(AltToken.Kind.LPAREN);
        Map<String, IrSort> members = new LinkedHashMap<>();
        java.util.Set<String> discards = new java.util.LinkedHashSet<>();
        Map<String, String> renames = new LinkedHashMap<>();
        int index = 0;
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RPAREN) {
            if (!first) expect(AltToken.Kind.COMMA);
            String key = "_" + index;
            if (parsingTuplePattern) {
                AltToken t = peek();
                boolean literalClause = t.kind() == AltToken.Kind.INTEGER
                        || t.kind() == AltToken.Kind.DECIMAL
                        || t.kind() == AltToken.Kind.CHAR
                        || (t.kind() == AltToken.Kind.IDENT
                                && (t.text().equals("true") || t.text().equals("false")));
                if (literalClause) {
                    // A value constraint in place ([(0.0, 0.0)], [(0, y)]) — like a
                    // struct's positional literal field ([Point(0, y)]). The base
                    // comes from the literal's own kind; the slot is occupied but
                    // binds nothing (verdict C).
                    consume();
                    String base = switch (t.kind()) {
                        case INTEGER -> "Int";
                        case DECIMAL -> "Decimal";
                        case CHAR -> "Char";
                        default -> "Bool";
                    };
                    IrExpr lit = switch (t.kind()) {
                        case INTEGER -> new IrExpr.Lit(Long.parseLong(t.text()), t.origin());
                        case DECIMAL -> new IrExpr.Dec(new java.math.BigDecimal(t.text()), t.origin());
                        case CHAR -> new IrExpr.Chr(t.text().codePointAt(0), t.origin());
                        default -> new IrExpr.Bool(t.text().equals("true"), t.origin());
                    };
                    members.put(key, new IrSort.Refined(base,
                            new IrExpr.BinOp(IrExpr.Op.EQ, new IrExpr.SelfRef(t.origin()), lit, t.origin()),
                            t.origin()));
                    discards.add(key);
                } else if (t.kind() == AltToken.Kind.IDENT
                        && peek(1).kind() == AltToken.Kind.LPAREN) {
                    // A constructor/struct destructure as a tuple element
                    // ([(Traction(n, z), Traction(0.0, 0.0))]) — reuse the
                    // bracket-branch struct-pattern machinery. The slot is
                    // occupied by the nested Structural sort; its inner fields
                    // bind via the recursive destructure desugar (the renames /
                    // literal constraints are recorded against the inner sort).
                    // NOT a tuple-level discard — the nested pattern binds.
                    AltToken baseTok = consume();
                    expect(AltToken.Kind.LPAREN);
                    java.util.Set<String> innerLiterals = new java.util.LinkedHashSet<>();
                    Map<String, String> innerRenames = new LinkedHashMap<>();
                    Map<String, IrSort> innerMembers = parseStructFields(
                            baseTok.text(), baseTok.origin(), innerLiterals, innerRenames);
                    expect(AltToken.Kind.RPAREN);
                    IrSort.Structural inner = new IrSort.Structural(
                            baseTok.text(), innerMembers, baseTok.origin());
                    if (!innerLiterals.isEmpty()) literalConstrainedFields.put(inner, innerLiterals);
                    if (!innerRenames.isEmpty()) destructureRenames.put(inner, innerRenames);
                    // An imported struct inside a tuple ([(Vec(x,y), c)]) defers
                    // like any other — its slots are positional until link time.
                    if (patternShapeFor(baseTok.text()) == null) deferredStructPatterns.add(inner);
                    members.put(key, inner);
                } else if (t.kind() == AltToken.Kind.LBRACKET) {
                    // An explicit refinement-sort constraint ([Decimal:0.0],
                    // [Int:@>0]) — occupies the slot, binds nothing.
                    members.put(key, parseSort());
                    discards.add(key);
                } else if (t.kind() == AltToken.Kind.LPAREN) {
                    // A nested tuple ([((a, b), c)]) — binds its components via
                    // the recursive destructure desugar; NOT a discard.
                    members.put(key, parseTupleSortBody(t));
                } else {
                    AltToken binder = expect(AltToken.Kind.IDENT);
                    members.put(key, IrSort.named("_"));  // sort resolved from scrutinee
                    if (binder.text().equals("_")) {
                        discards.add(key);                // verdict C: explicit discard
                    } else {
                        renames.put(key, binder.text());
                    }
                }
            } else if (peek().kind() == AltToken.Kind.LPAREN) {
                // A nested tuple TYPE ([((Int, Int), Int)]) — parseSort can't
                // start at '(', so recurse here (parity with the pattern path).
                members.put(key, parseTupleSortBody(peek()));
            } else {
                members.put(key, parseSort());        // type position: a real sort
            }
            index++;
            first = false;
        }
        expect(AltToken.Kind.RPAREN);
        if (peek().kind() == AltToken.Kind.COLON) {
            // A tuple takes no whole-aggregate predicate — by design, not by
            // omission. An independent per-component constraint belongs in
            // place ([([Int:@>0], Bool)]). A constraint that *relates* two
            // components is a relationship, and a relationship is a named
            // concept: that's a struct, with fields referred to by name
            // ([Interval:@.lo <= @.hi]). Tuples are for unrelated data.
            throw new ParseException(
                    "A tuple sort takes no whole-aggregate predicate. Constrain a component "
                            + "in place — [([Int:@>0], Bool)]. If the components are related by an "
                            + "invariant, that relationship is a named concept — use a struct and "
                            + "refer to fields by name, e.g. [Interval:@.lo <= @.hi].",
                    peek().origin());
        }
        if (members.size() < 2) {
            throw new ParseException(
                    "A tuple needs at least two components; got " + members.size()
                            + " — use the value directly rather than a 1-tuple",
                    open.origin());
        }
        IrSort.Structural tuple = new IrSort.Structural(TUPLE_SENTINEL, members, open.origin());
        if (!discards.isEmpty()) literalConstrainedFields.put(tuple, discards);
        if (!renames.isEmpty()) destructureRenames.put(tuple, renames);
        return tuple;
    }

    /**
     * Builds the canonical IR shape for a multi-branch bracket-sort.
     * Same-base branches (all bare or refined over the same base name)
     * collapse to a single {@link IrSort.Refined} with an {@code OR}-joined
     * or {@code AND}-joined predicate. Cross-base branches stay as a
     * {@link IrSort.Union} or {@link IrSort.Intersection}.
     */
    private static IrSort normalizeMultiBranch(List<IrSort> branches, String op, Origin origin) {
        String commonBase = sameBaseName(branches);
        if (commonBase == null) {
            return op.equals("|")
                    ? new IrSort.Union(branches, origin)
                    : new IrSort.Intersection(branches, origin);
        }
        IrExpr.Op irOp = op.equals("|") ? IrExpr.Op.OR : IrExpr.Op.AND;
        IrExpr combined = null;
        for (IrSort b : branches) {
            IrExpr branchPred = b instanceof IrSort.Refined r
                    ? r.predicate()
                    : new IrExpr.Bool(true, b.origin());
            combined = combined == null
                    ? branchPred
                    : new IrExpr.BinOp(irOp, combined, branchPred, origin);
        }
        return new IrSort.Refined(commonBase, combined, origin);
    }

    /**
     * Returns the shared base-name across {@code branches} if all are
     * either bare {@link IrSort.Named} or {@link IrSort.Refined} over the
     * same name; null otherwise (signals cross-base — caller emits Union
     * or Intersection instead of normalizing).
     */
    private static String sameBaseName(List<IrSort> branches) {
        String base = null;
        for (IrSort b : branches) {
            String n = switch (b) {
                case IrSort.Named bn -> bn.name();
                case IrSort.Refined r -> r.name();
                default -> null;
            };
            if (n == null) return null;
            if (base == null) base = n;
            else if (!base.equals(n)) return null;
        }
        return base;
    }

    /**
     * Parses {@code (P1, P2, ...):R} after the {@code Method} keyword. Each parameter
     * is either positional ({@code Int}) or named ({@code i:Int}); a sort is all-named
     * or all-positional (mixing is an error). Named parameters are the binders a
     * dependent return/param sort references (WAR(dependent-sorts), slice 1: names are
     * parsed and carried on {@link IrSort.Method}; slice 2 resolves references to them).
     *
     * <p>The returned {@link IrSort.Method} uses the {@code Method} token's origin; the
     * caller may rebuild with a wider span if it has the closing {@code ]} on hand.
     */
    private IrSort.Method parseFunctionSortBody(AltToken funcTok) throws ParseException {
        expect(AltToken.Kind.LPAREN);
        List<IrSort> paramSorts = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();
        int named = 0;
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RPAREN) {
            if (!first) expect(AltToken.Kind.COMMA);
            if (peek().kind() == AltToken.Kind.IDENT
                    && peek(1).kind() == AltToken.Kind.COLON) {
                paramNames.add(expect(AltToken.Kind.IDENT).text());
                expect(AltToken.Kind.COLON);
                named++;
            } else {
                paramNames.add("");  // positional placeholder (dropped unless all named)
            }
            paramSorts.add(parseSort());
            first = false;
        }
        expect(AltToken.Kind.RPAREN);
        expect(AltToken.Kind.COLON);
        IrSort returnSort = parseSort();
        if (named != 0 && named != paramSorts.size()) {
            throw new ParseException(
                    "Method sort mixes named and positional parameters — name all of "
                            + "them or none.", funcTok.origin());
        }
        List<String> names = named == 0 ? List.of() : paramNames;
        return new IrSort.Method(paramSorts, names, returnSort, funcTok.origin());
    }

    /**
     * Parses the comma-separated clause list inside {@code [Name(...)]}:
     *   - {@code field:Sort} — explicit per-field sort
     *   - {@code field}      — bare ident; sort looked up from {@link #declaredStructs}
     *   - a <b>literal</b> ({@code 0}, {@code 1.5}, {@code true}) — positional:
     *     the i-th clause maps to the i-th declared field, constraining it to
     *     {@code [@==literal]} without binding it ({@code [Ternion(z, 0, w)]}).
     *     Primitive-sorted fields only; names added to {@code literalFieldsOut}.
     * Mixed forms are allowed.
     */
    /**
     * The shape a {@code [Name(...)]} pattern destructures against: the
     * declared struct, or a native constructor's registered anatomy
     * ({@code [Decimal(u, s)]} — irrefutable, every Decimal has a canonical
     * (unscaled, scale)).
     */
    private IrSort.Structural patternShapeFor(String typeName) {
        IrSort.Structural decl = declaredStructs.get(typeName);
        if (decl != null) return decl;
        return sibarum.pontif.ir.NativeConstructors.has(typeName)
                ? sibarum.pontif.ir.NativeConstructors.get(typeName).shape()
                : null;
    }

    /**
     * Resolves the declared field name at positional {@code clauseIndex} of a
     * struct pattern, with the standard guards (struct declared, index in range,
     * slot not already given by name). Shared by the positional clauses that
     * occupy a slot without a leading field name — nested patterns and
     * refinement constraints.
     */
    private String positionalField(
            String typeName, int clauseIndex, Map<String, IrSort> members, Origin o)
            throws ParseException {
        IrSort.Structural decl = patternShapeFor(typeName);
        if (decl == null) {
            throw new ParseException(
                    "A positional pattern inside [" + typeName + "(...)] requires '"
                            + typeName + "' to be declared before this point", o);
        }
        List<String> order = new ArrayList<>(decl.members().keySet());
        if (clauseIndex >= order.size()) {
            throw new ParseException(
                    "Too many fields for struct '" + typeName + "' ("
                            + order.size() + " declared)", o);
        }
        String posField = order.get(clauseIndex);
        if (members.containsKey(posField)) {
            throw new ParseException(
                    "Field '" + posField + "' is given both by name and by position in ["
                            + typeName + "(...)]", o);
        }
        return posField;
    }

    private Map<String, IrSort> parseStructFields(String typeName, Origin typeOrigin,
            java.util.Set<String> literalFieldsOut, Map<String, String> renamesOut)
            throws ParseException {
        Map<String, IrSort> members = new LinkedHashMap<>();
        boolean first = true;
        int clauseIndex = -1;
        // Cluster (2) deferral: a struct imported from another module is not in
        // declaredStructs at parse time (requires-linking runs later), so its
        // field ORDER and SORTS are unknown here. Rather than throw (the old
        // per-form seam), capture the positional pattern SYMBOLICALLY — slot
        // keys `_0.._n`, the binder/discard/literal role encoded in each slot's
        // sort — and let the post-link DestructureResolver map slots to declared
        // field names and run the arity-total check. This mirrors how the `.{}`
        // by-name form already defers (IrSort.named("_") placeholders), adding
        // the slot ORDER positional forms need. The local (struct-known) path is
        // unchanged below.
        boolean deferred = patternShapeFor(typeName) == null;
        while (peek().kind() != AltToken.Kind.RPAREN) {
            if (!first) expect(AltToken.Kind.COMMA);
            clauseIndex++;
            AltToken t = peek();
            boolean literalClause = t.kind() == AltToken.Kind.INTEGER
                    || t.kind() == AltToken.Kind.DECIMAL
                    || t.kind() == AltToken.Kind.CHAR
                    || (t.kind() == AltToken.Kind.IDENT
                            && (t.text().equals("true") || t.text().equals("false")));
            if (deferred) {
                parseDeferredStructFieldClause(
                        typeName, clauseIndex, members, literalFieldsOut, renamesOut, literalClause);
                first = false;
                continue;
            }
            if (literalClause) {
                consume();
                IrSort.Structural decl = patternShapeFor(typeName);
                if (decl == null) {
                    throw new ParseException(
                            "A literal field pattern inside [" + typeName + "(...)] requires '"
                                    + typeName + "' to be declared before this point",
                            t.origin());
                }
                List<String> order = new ArrayList<>(decl.members().keySet());
                if (clauseIndex >= order.size()) {
                    throw new ParseException(
                            "Too many fields for struct '" + typeName + "' ("
                                    + order.size() + " declared)", t.origin());
                }
                String posField = order.get(clauseIndex);
                if (members.containsKey(posField)) {
                    throw new ParseException(
                            "Field '" + posField + "' is given both by name and by position in ["
                                    + typeName + "(...)]", t.origin());
                }
                IrSort declSort = decl.members().get(posField);
                String base = baseSortName(declSort);
                if (!"Int".equals(base) && !"Bool".equals(base) && !"Decimal".equals(base)
                        && !"Char".equals(base)) {
                    throw new ParseException(
                            "A literal field pattern needs a primitive-sorted field; '"
                                    + posField + "' of '" + typeName + "' has sort " + declSort,
                            t.origin());
                }
                IrExpr lit = switch (t.kind()) {
                    case INTEGER -> new IrExpr.Lit(Long.parseLong(t.text()), t.origin());
                    case DECIMAL -> new IrExpr.Dec(new java.math.BigDecimal(t.text()), t.origin());
                    case CHAR -> new IrExpr.Chr(t.text().codePointAt(0), t.origin());
                    default -> new IrExpr.Bool(t.text().equals("true"), t.origin());
                };
                members.put(posField, new IrSort.Refined(base,
                        new IrExpr.BinOp(IrExpr.Op.EQ, new IrExpr.SelfRef(t.origin()), lit, t.origin()),
                        t.origin()));
                literalFieldsOut.add(posField);
                first = false;
                continue;
            }
            // A nested constructor pattern as a field ([Outer(Inner(x, y), c)]) —
            // reuse parseStructFields; the slot maps positionally and its inner
            // fields bind via the recursive destructure desugar (renames /
            // literal constraints recorded against the inner sort). NOT a
            // struct-level literal/discard. Parity with tuple components.
            if (t.kind() == AltToken.Kind.IDENT && peek(1).kind() == AltToken.Kind.LPAREN) {
                String posField = positionalField(typeName, clauseIndex, members, t.origin());
                AltToken baseTok = consume();
                expect(AltToken.Kind.LPAREN);
                java.util.Set<String> innerLiterals = new java.util.LinkedHashSet<>();
                Map<String, String> innerRenames = new LinkedHashMap<>();
                Map<String, IrSort> innerMembers = parseStructFields(
                        baseTok.text(), baseTok.origin(), innerLiterals, innerRenames);
                expect(AltToken.Kind.RPAREN);
                IrSort.Structural inner = new IrSort.Structural(
                        baseTok.text(), innerMembers, baseTok.origin());
                if (!innerLiterals.isEmpty()) literalConstrainedFields.put(inner, innerLiterals);
                if (!innerRenames.isEmpty()) destructureRenames.put(inner, innerRenames);
                members.put(posField, inner);
                first = false;
                continue;
            }
            // A nested tuple pattern as a field ([Pair((a, b), c)]) — binds its
            // components via the recursive desugar. NOT a struct-level discard.
            if (t.kind() == AltToken.Kind.LPAREN) {
                String posField = positionalField(typeName, clauseIndex, members, t.origin());
                members.put(posField, parseTupleSortBody(t));
                first = false;
                continue;
            }
            // A positional refinement constraint as a field ([P([Int:@>0], y)]) —
            // constrains the slot, binds nothing (like a literal); parity with a
            // tuple's [Int:@>0] component. (A by-name `field:[…]` narrowing is the
            // separate COLON path below, ruled honest narrowing not a pattern.)
            if (t.kind() == AltToken.Kind.LBRACKET) {
                String posField = positionalField(typeName, clauseIndex, members, t.origin());
                members.put(posField, parseSort());
                literalFieldsOut.add(posField);
                first = false;
                continue;
            }
            AltToken fieldName = expect(AltToken.Kind.IDENT);
            if (fieldName.text().equals("_")) {
                // Verdict C: positional discard — occupies the slot (so the
                // pattern stays arity-total) but binds nothing. Recorded in
                // literalFieldsOut, the "constrain/occupy but don't bind" set
                // the destructure desugar skips.
                IrSort.Structural decl = patternShapeFor(typeName);
                if (decl == null) {
                    throw new ParseException(
                            "A '_' discard inside [" + typeName + "(...)] requires '"
                                    + typeName + "' to be declared before this point",
                            fieldName.origin());
                }
                List<String> order = new ArrayList<>(decl.members().keySet());
                if (clauseIndex >= order.size()) {
                    throw new ParseException(
                            "Too many fields for struct '" + typeName + "' ("
                                    + order.size() + " declared)", fieldName.origin());
                }
                String posField = order.get(clauseIndex);
                if (members.containsKey(posField)) {
                    throw new ParseException(
                            "Field '" + posField + "' is given both by name and by position in ["
                                    + typeName + "(...)]", fieldName.origin());
                }
                members.put(posField, decl.members().get(posField));
                literalFieldsOut.add(posField);
                first = false;
                continue;
            }
            IrSort fieldSort;
            if (peek().kind() == AltToken.Kind.COLON) {
                consume();  // COLON
                fieldSort = parseSort();
                // By-name narrowing `field:Sort` must name a REAL field of the
                // declared struct — otherwise it's silently a bogus member that
                // binds nothing (and the body's references go unbound). A positional
                // binder is the bare-ident form `[Type(name)]` (no `:Sort`).
                IrSort.Structural decl = patternShapeFor(typeName);
                if (decl != null && !decl.members().containsKey(fieldName.text())) {
                    throw new ParseException(
                            "Pattern [" + typeName + "(...)] narrows field '" + fieldName.text()
                                    + "' by name, but '" + typeName + "' has no such field "
                                    + "(declared: " + decl.members().keySet() + "). For a "
                                    + "positional binder, drop the sort: [" + typeName + "("
                                    + fieldName.text() + ", …)].",
                            fieldName.origin());
                }
            } else {
                // Bare ident — look up declared field sort.
                IrSort.Structural decl = patternShapeFor(typeName);
                if (decl == null) {
                    throw new ParseException(
                            "Bare field name '" + fieldName.text() + "' inside [" + typeName
                                    + "(...)] requires '" + typeName + "' to be declared before this point "
                                    + "(struct decl not found)",
                            fieldName.origin());
                }
                IrSort declSort = decl.members().get(fieldName.text());
                if (declSort == null) {
                    // Not a field name → a positional RENAME binder: bind the
                    // field at this clause position under the given name
                    // ([Ternion(first, second, third)]).
                    List<String> order = new ArrayList<>(decl.members().keySet());
                    if (clauseIndex >= order.size()) {
                        throw new ParseException(
                                "Too many fields for struct '" + typeName + "' ("
                                        + order.size() + " declared)", fieldName.origin());
                    }
                    String posField = order.get(clauseIndex);
                    if (members.containsKey(posField)) {
                        throw new ParseException(
                                "Field '" + posField + "' is given both by name and by position in ["
                                        + typeName + "(...)]", fieldName.origin());
                    }
                    members.put(posField, decl.members().get(posField));
                    renamesOut.put(posField, fieldName.text());
                    first = false;
                    continue;
                }
                fieldSort = declSort;
            }
            members.put(fieldName.text(), fieldSort);
            first = false;
        }
        // Verdict B: a positional `(...)` pattern wears the constructor's
        // clothes, so it must account for every field — a subset like
        // [Ternion(a)] is lying by omission. Enforced in pattern context only;
        // a partial field-sort *type* (e.g. [Point(x:[Int:@>0])]) is honest
        // narrowing, not a pattern, so it's left alone.
        // Verdict B arity-total check, in pattern context. For the deferred
        // (cross-module) path the struct shape is unknown here, so the SAME rule
        // runs post-link in DestructureResolver — one rule, two call sites, never
        // implemented inconsistently per form. The local path checks it now.
        IrSort.Structural decl = patternShapeFor(typeName);
        if (parsingTuplePattern && decl != null) {
            requireArityTotal(typeName, members.size(), decl.members().size(), typeOrigin);
        }
        return members;
    }

    /**
     * The arity-total rule for a positional struct pattern (verdict B): a
     * {@code [Type(...)]} pattern wears the constructor's clothes, so it must
     * account for EVERY field — a subset like {@code [Ternion(a)]} lies by
     * omission. The rule itself lives ONCE in
     * {@code DestructureResolver.arityTotalError}, shared by this parse-time
     * local path and the post-link cross-module path, so it fires identically
     * for both too-few and too-many.
     */
    private static void requireArityTotal(String typeName, int provided, int declared, Origin o)
            throws ParseException {
        String msg = sibarum.pontif.ir.DestructureResolver.arityTotalError(typeName, provided, declared);
        if (msg != null) throw new ParseException(msg, o);
    }

    /**
     * Parses one clause of a DEFERRED (cross-module) positional struct pattern.
     * The struct's declared fields are unknown at parse time, so the slot is
     * keyed positionally ({@code _<clauseIndex>}) and its role is encoded in the
     * member sort, to be resolved against the linked struct by
     * {@code DestructureResolver}:
     * <ul>
     *   <li>literal ({@code 0}, {@code true}) → {@link IrSort.Refined} constraint,
     *       slot recorded in {@code literalFieldsOut} (constrains, binds nothing);
     *   <li>nested constructor ({@code Inner(x, y)}) → a nested deferred/known
     *       {@link IrSort.Structural} (binds its own slots, recurses);
     *   <li>nested tuple ({@code (a, b)}) → a tuple {@link IrSort.Structural};
     *   <li>refinement constraint ({@code [Int:@>0]}) → the parsed sort, recorded
     *       in {@code literalFieldsOut} (constrains, binds nothing);
     *   <li>{@code _} discard → {@link DEFERRED_SKIP} placeholder, recorded in
     *       {@code literalFieldsOut};
     *   <li>bare binder ({@code x}) → {@link DEFERRED_BIND} placeholder; the
     *       binder name is recorded as a rename off the positional key so the
     *       body can be parsed with the binder in scope, AND it is encoded in the
     *       placeholder sort so the resolver can recover it post-link.
     * </ul>
     * A by-name narrowing ({@code field:Sort}) is rejected: it requires knowing
     * the declared fields, which a cross-module pattern doesn't have at parse
     * time. (Cross-module by-name narrowing is the `.{}` form's job.)
     */
    private void parseDeferredStructFieldClause(
            String typeName, int clauseIndex, Map<String, IrSort> members,
            java.util.Set<String> literalFieldsOut, Map<String, String> renamesOut,
            boolean literalClause) throws ParseException {
        String slot = "_" + clauseIndex;
        AltToken t = peek();
        if (literalClause) {
            consume();
            String base = switch (t.kind()) {
                case INTEGER -> "Int";
                case DECIMAL -> "Decimal";
                case CHAR -> "Char";
                default -> "Bool";
            };
            IrExpr lit = switch (t.kind()) {
                case INTEGER -> new IrExpr.Lit(Long.parseLong(t.text()), t.origin());
                case DECIMAL -> new IrExpr.Dec(new java.math.BigDecimal(t.text()), t.origin());
                case CHAR -> new IrExpr.Chr(t.text().codePointAt(0), t.origin());
                default -> new IrExpr.Bool(t.text().equals("true"), t.origin());
            };
            members.put(slot, new IrSort.Refined(base,
                    new IrExpr.BinOp(IrExpr.Op.EQ, new IrExpr.SelfRef(t.origin()), lit, t.origin()),
                    t.origin()));
            literalFieldsOut.add(slot);
            return;
        }
        if (t.kind() == AltToken.Kind.IDENT && peek(1).kind() == AltToken.Kind.LPAREN) {
            // Nested constructor pattern as a slot ([Outer(Inner(x, y), c)]).
            AltToken baseTok = consume();
            expect(AltToken.Kind.LPAREN);
            java.util.Set<String> innerLiterals = new java.util.LinkedHashSet<>();
            Map<String, String> innerRenames = new LinkedHashMap<>();
            Map<String, IrSort> innerMembers = parseStructFields(
                    baseTok.text(), baseTok.origin(), innerLiterals, innerRenames);
            expect(AltToken.Kind.RPAREN);
            IrSort.Structural inner = new IrSort.Structural(
                    baseTok.text(), innerMembers, baseTok.origin());
            if (!innerLiterals.isEmpty()) literalConstrainedFields.put(inner, innerLiterals);
            if (!innerRenames.isEmpty()) destructureRenames.put(inner, innerRenames);
            if (patternShapeFor(baseTok.text()) == null) deferredStructPatterns.add(inner);
            members.put(slot, inner);
            return;
        }
        if (t.kind() == AltToken.Kind.LPAREN) {
            // Nested tuple pattern as a slot ([Pair((a, b), c)]).
            members.put(slot, parseTupleSortBody(t));
            return;
        }
        if (t.kind() == AltToken.Kind.LBRACKET) {
            // Positional refinement constraint as a slot ([P([Int:@>0], y)]).
            members.put(slot, parseSort());
            literalFieldsOut.add(slot);
            return;
        }
        AltToken binder = expect(AltToken.Kind.IDENT);
        if (peek().kind() == AltToken.Kind.COLON) {
            throw new ParseException(
                    "Pattern [" + typeName + "(...)] narrows field '" + binder.text()
                            + "' by name, but '" + typeName + "' is imported and its fields "
                            + "are not known at this point. Use a positional binder "
                            + "([" + typeName + "(" + binder.text() + ", …)]) or the by-name "
                            + "form [" + typeName + ".{" + binder.text() + "}].",
                    binder.origin());
        }
        if (binder.text().equals("_")) {
            members.put(slot, IrSort.named(DEFERRED_SKIP));
            literalFieldsOut.add(slot);
        } else {
            members.put(slot, IrSort.named(DEFERRED_BIND + binder.text()));
            renamesOut.put(slot, binder.text());
        }
    }

    /**
     * Implements the implicit {@code @==EXPR} sugar — see principle 5 in
     * docs/alternative-syntax.ptf. Applied per-disjunct/conjunct so
     * {@code [Int:0|1]} reads as {@code @==0 | @==1}, not {@code @==(0|1)}.
     */
    private static IrExpr applyPredicateSugar(IrExpr pred) {
        if (pred instanceof IrExpr.BinOp bop) {
            IrExpr.Op op = bop.op();
            if (op == IrExpr.Op.OR || op == IrExpr.Op.AND) {
                return new IrExpr.BinOp(op,
                        applyPredicateSugar(bop.left()),
                        applyPredicateSugar(bop.right()),
                        bop.origin());
            }
            if (isComparison(op)) {
                return pred;
            }
        }
        // Wrap: @ == pred
        return new IrExpr.BinOp(IrExpr.Op.EQ,
                new IrExpr.SelfRef(pred.origin()),
                pred,
                pred.origin());
    }

    private static boolean isComparison(IrExpr.Op op) {
        return switch (op) {
            case LT, LE, GT, GE, EQ, NE -> true;
            default -> false;
        };
    }

    // --- Expressions (Pratt) ---

    public IrExpr parseExpr() throws ParseException {
        return parseExpr(0);
    }

    private IrExpr parseExpr(int minPrec) throws ParseException {
        IrExpr left = parsePrimaryWithPostfix();
        while (true) {
            AltToken t = peek();
            if (t.kind() != AltToken.Kind.OP) break;
            int prec = precedence(t.text());
            if (prec < minPrec) break;
            consume();
            IrExpr right = parseExpr(prec + 1);  // left-associative
            // Operators are symmetric mechanism-1 multi-dispatch: `a <op> b`
            // always parses to a BinOp. The post-link MethodOperatorResolver
            // routes user-declared operators to their overload by matching BOTH
            // operand base sorts, and leaves built-in Int/Bool ops as BinOp.
            // No parse-time guess from the left operand's sort (which could not
            // see cross-module types and forced the receiver-rooted asymmetry).
            left = new IrExpr.BinOp(opKind(t.text()), left, right, t.origin());
        }
        return left;
    }

    /**
     * Operators that can be overloaded — via a bare generic
     * {@code function <op>(l, r)}. Arithmetic and comparison. Logical {@code &}
     * and {@code |} are excluded — they always go through BinOp regardless of
     * any declaration named {@code &}. The receiver-rooted {@code method Type.op}
     * form is rejected at parse (see {@link #parseMethod}); operators are free
     * functions.
     */
    private static final Set<String> OVERLOADABLE_OPS = Set.of(
            "+", "-", "*", "/", "%", "^",
            "<", "<=", ">", ">=", "==", "!=");

    /**
     * A postfix {@code (} or <code>{</code> must open on the same line as the
     * token it extends — a newline ends the call/struct-literal postfix chain.
     * Without this, a declaration body silently swallowed the next construct:
     * {@code -> Ratio(a, b)} followed by a {@code (expr).x} main parsed as a
     * CALL of the body, the module lost its main, and the placeholder main 0
     * "ran". Dot-chains may still continue across lines (fluent style).
     */
    private boolean postfixOpensOnSameLine(AltToken t) {
        return pos > 0 && tokens.get(pos - 1).line() == t.line();
    }

    /**
     * Anonymous by-name aggregate (dictionary) literal: {@code {a = 1, b = 2}}
     * — the by-name sibling of the tuple literal. Lowers to an anonymous
     * {@link IrExpr.Record} (null typeName), the same shape the S-expr
     * {@code (record …)} form produces; rides the record substrate with no new
     * node. Free-form: no completeness obligation (there is no named type to be
     * complete <em>of</em>). Duplicate keys are rejected.
     */
    private IrExpr parseDictLiteral() throws ParseException {
        AltToken open = expect(AltToken.Kind.LBRACE);
        Map<String, IrExpr> members = new LinkedHashMap<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RBRACE) {
            if (!first) expect(AltToken.Kind.COMMA);
            AltToken key = expect(AltToken.Kind.IDENT);
            if (members.containsKey(key.text())) {
                throw new ParseException(
                        "Duplicate key '" + key.text() + "' in dictionary literal",
                        key.origin());
            }
            expect(AltToken.Kind.EQUALS);
            members.put(key.text(), parseExpr());
            first = false;
        }
        AltToken close = expect(AltToken.Kind.RBRACE);
        return new IrExpr.Record(null, members, open.spanTo(close));
    }

    /** True for a tuple positional key — {@code _0}, {@code _1}, … (underscore + digits). */
    private static boolean isPositionalKey(String name) {
        if (name.length() < 2 || name.charAt(0) != '_') return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return false;
        }
        return true;
    }

    private IrExpr parsePrimaryWithPostfix() throws ParseException {
        IrExpr expr = parsePrimary();
        // Postfix: .IDENT (field access), (args) (positional call or struct
        // literal), {x=val,...} (by-name struct literal) — left-to-right.
        while (true) {
            AltToken t = peek();
            if (t.kind() == AltToken.Kind.DOT && peek(1).kind() == AltToken.Kind.IDENT) {
                consume();  // DOT
                AltToken name = consume();
                // Destructure-only: a positional key (_0, _1, …) can't be read
                // off a value expression. The only positional projection allowed
                // is `@._N` inside a sort's refinement predicate (base is the
                // SelfRef `@`); a tuple component is otherwise reached only by
                // destructuring. This keeps tuples honest — no silent component
                // extraction that bypasses the arity-total pattern.
                if (isPositionalKey(name.text()) && !(expr instanceof IrExpr.SelfRef)) {
                    throw new ParseException(
                            "Tuple components are destructure-only — '." + name.text()
                                    + "' can't be read off a value; bind it with "
                                    + "`let [(...)] = …` or a match pattern.",
                            t.origin());
                }
                expr = new IrExpr.FieldAccess(expr, name.text(), t.origin());
            } else if (t.kind() == AltToken.Kind.LPAREN && postfixOpensOnSameLine(t)) {
                AltToken open = consume();
                // Struct-literal shortcut: a bare ident matching a declared
                // struct constructs a record (positional), not a Call. Native
                // constructors (Decimal(unscaled, scale)) route the same way —
                // their registered shape plays the struct declaration's part.
                if (expr instanceof IrExpr.Var v && declaredStructs.containsKey(v.name())) {
                    expr = parsePositionalStructLiteral(
                            declaredStructs.get(v.name()), v.name(), open);
                    continue;
                }
                if (expr instanceof IrExpr.Var v
                        && sibarum.pontif.ir.NativeConstructors.has(v.name())) {
                    expr = parsePositionalStructLiteral(
                            sibarum.pontif.ir.NativeConstructors.get(v.name()).shape(),
                            v.name(), open);
                    continue;
                }
                List<IrExpr> args = parseArgList();
                AltToken close = expect(AltToken.Kind.RPAREN);
                Origin callOrigin = open.spanTo(close);

                // Instance-method call: `receiver.method(args)` on a VALUE
                // receiver becomes an unresolved MethodCall. MethodResolver (an
                // IR pass) keys it to `Type.method` by the receiver's inferred
                // type — after every declaration is registered, so resolution is
                // order-independent (forward references, self- and mutual
                // recursion all work). The receiver gets the top-level-let
                // rewrite first (a let-bound value is invoked as a 0-arg call
                // before becoming the receiver). A module- or type-qualified
                // dotted name (`std.stream.concat`, `Point.zero`) is NOT a value
                // receiver and stays a dotted Call (resolved by NameResolver).
                if (expr instanceof IrExpr.FieldAccess fa) {
                    IrExpr receiver = rewriteTopLevelLetAccess(fa.base());
                    if (isValueReceiver(receiver)) {
                        String method = fa.fieldName();
                        expr = lowerSpreadCall(args,
                                a -> new IrExpr.MethodCall(receiver, method, a, callOrigin),
                                callOrigin);
                        continue;
                    }
                }

                // Otherwise it's a Call on a dotted name, or an Apply on an
                // arbitrary expression. A `&`-spread argument turns either into a
                // per-element stream map (docs/stream-war.md §3).
                String dotted = extractDottedName(expr);
                if (dotted != null) {
                    expr = lowerSpreadCall(args, a -> new IrExpr.Call(dotted, a, callOrigin),
                            callOrigin);
                } else {
                    IrExpr fn = expr;
                    expr = lowerSpreadCall(args, a -> new IrExpr.Apply(fn, a, callOrigin),
                            callOrigin);
                }
            } else if (t.kind() == AltToken.Kind.LBRACE
                    && postfixOpensOnSameLine(t)
                    && expr instanceof IrExpr.Var v
                    && (declaredStructs.containsKey(v.name())
                            || sibarum.pontif.ir.NativeConstructors.has(v.name()))) {
                // By-name struct literal `Foo{x=a, y=b}`. The brace form is
                // reserved for declared-struct construction in this slice;
                // anonymous and dotted-name forms are deferred. Native
                // constructors take the brace form too.
                AltToken open = consume();
                IrSort.Structural shape = declaredStructs.containsKey(v.name())
                        ? declaredStructs.get(v.name())
                        : sibarum.pontif.ir.NativeConstructors.get(v.name()).shape();
                expr = parseByNameStructLiteral(shape, v.name(), open);
            } else {
                break;
            }
        }
        return rewriteTopLevelLetAccess(expr);
    }

    /**
     * If {@code receiver}'s inferred sort has a base name {@code Type} and a
     * function/method {@code Type.field} is declared, returns the
     * fully-qualified method name. Used by the instance-method call routing
     * in {@link #parsePrimaryWithPostfix}.
     */
    /**
     * Whether {@code receiver} (already run through
     * {@link #rewriteTopLevelLetAccess}) is a <em>value</em> expression — the
     * left of an instance-method call — versus a module/type-qualified name.
     * Anything that isn't a bare/dotted name is a value (a struct literal, a
     * call result, a method-call result, a 0-arg let/function call). A
     * dotted-name receiver is a value only when its head is a bound local or a
     * top-level let; otherwise it names a module or a type ({@code std.stream},
     * {@code Point}) and the call stays a dotted {@link IrExpr.Call}. This
     * decision is independent of method declaration order.
     */
    private boolean isValueReceiver(IrExpr receiver) {
        String dotted = extractDottedName(receiver);
        if (dotted == null) {
            return true;
        }
        int dot = dotted.indexOf('.');
        String head = dot < 0 ? dotted : dotted.substring(0, dot);
        return currentScope.containsKey(head) || declaredTopLevelLets.containsKey(head);
    }

    /**
     * Rewrites top-level let and 0-arg-function accesses to 0-arg dispatch
     * calls so the dispatch table resolves them. Lets and 0-arg functions
     * are treated equivalently — both are 0-arg dispatch entries from the
     * call-site's perspective, both auto-Call on bare reference. Handles
     * two shapes:
     * <ul>
     *   <li>Bare {@code Var(name)} — if {@code name} is a declared let or a
     *       declared 0-arg function and not shadowed by {@link #currentScope},
     *       becomes {@code Call(name, [])}.
     *   <li>{@code FieldAccess} chain rooted at a Var — the longest dotted
     *       prefix matching a let or 0-arg function becomes a 0-arg Call,
     *       with the remaining suffix kept as a chain of FieldAccesses.
     * </ul>
     * Functions with at least one declared param are never auto-Called
     * (they require explicit args).
     */
    private IrExpr rewriteTopLevelLetAccess(IrExpr expr) {
        if (expr instanceof IrExpr.Var v) {
            if (!currentScope.containsKey(v.name())
                    && isZeroArgDispatchEntry(v.name())) {
                return new IrExpr.Call(v.name(), List.of(), v.origin());
            }
            return expr;
        }
        if (expr instanceof IrExpr.FieldAccess) {
            return rewriteDottedLetAccess(expr);
        }
        return expr;
    }

    /**
     * True if {@code name} is a 0-arg dispatch entry — either a declared
     * top-level let, or a function declared with no params. Both auto-Call
     * on bare reference.
     */
    private boolean isZeroArgDispatchEntry(String name) {
        return declaredTopLevelLets.containsKey(name)
                || declaredZeroArgFunctions.contains(name);
    }

    /**
     * Walks a FieldAccess chain to its leftmost Var, looks for the longest
     * dotted prefix that matches a 0-arg dispatch entry (let or 0-arg
     * function), and rewrites that prefix to a 0-arg Call (keeping any
     * trailing field accesses as a chain on top). Returns the expression
     * unchanged if no such prefix matches or if the chain's root is shadowed
     * by {@link #currentScope}.
     */
    private IrExpr rewriteDottedLetAccess(IrExpr expr) {
        List<String> segments = new ArrayList<>();
        IrExpr cur = expr;
        while (cur instanceof IrExpr.FieldAccess fa) {
            segments.add(0, fa.fieldName());
            cur = fa.base();
        }
        if (!(cur instanceof IrExpr.Var rootVar)) return expr;
        if (currentScope.containsKey(rootVar.name())) return expr;
        segments.add(0, rootVar.name());

        int matchedLength = -1;
        for (int i = segments.size(); i >= 1; i--) {
            String prefix = String.join(".", segments.subList(0, i));
            if (isZeroArgDispatchEntry(prefix)) {
                matchedLength = i;
                break;
            }
        }
        if (matchedLength == -1) return expr;

        String prefix = String.join(".", segments.subList(0, matchedLength));
        IrExpr result = new IrExpr.Call(prefix, List.of(), rootVar.origin());
        for (int i = matchedLength; i < segments.size(); i++) {
            result = new IrExpr.FieldAccess(result, segments.get(i), expr.origin());
        }
        return result;
    }

    /**
     * Parses {@code (a, b, ...)} after a struct-name root — positional struct
     * literal. The opening paren is already consumed.
     *
     * <p>Validates arity equals the struct's field count and maps positional
     * args to declared field names in declaration order. The resulting
     * {@link IrExpr.Record} iterates fields in declared order regardless of
     * how the call was written.
     */
    private IrExpr.Record parsePositionalStructLiteral(
            IrSort.Structural decl, String typeName, AltToken openParen)
            throws ParseException {
        List<IrExpr> args = parseArgList();
        AltToken close = expect(AltToken.Kind.RPAREN);
        if (anySpread(args)) {
            throw new ParseException(
                    "`&` spread is only valid in a function/fragment call, not the struct "
                            + "literal '" + typeName + "'", openParen.origin());
        }
        List<String> declaredFields = new ArrayList<>(decl.members().keySet());
        if (args.size() != declaredFields.size()) {
            throw new ParseException(
                    "Struct literal for '" + typeName + "' expects "
                            + declaredFields.size() + " positional arg(s) but got "
                            + args.size() + "; declared fields: " + declaredFields,
                    openParen.origin());
        }
        Map<String, IrExpr> ordered = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            ordered.put(declaredFields.get(i), args.get(i));
        }
        return new IrExpr.Record(typeName, ordered, openParen.spanTo(close));
    }

    /**
     * Parses {@code {x=a, y=b, ...}} after a struct-name root — by-name struct
     * literal. The opening brace is already consumed.
     *
     * <p>Validates: every field name belongs to the struct, every declared
     * field is present exactly once (no missing, no duplicates). Reorders
     * the resulting {@link IrExpr.Record} into declared field iteration order
     * so the IR is canonical regardless of source order.
     */
    private IrExpr.Record parseByNameStructLiteral(
            IrSort.Structural decl, String typeName, AltToken openBrace)
            throws ParseException {
        Map<String, IrExpr> provided = new LinkedHashMap<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RBRACE) {
            if (!first) expect(AltToken.Kind.COMMA);
            AltToken fieldTok = expect(AltToken.Kind.IDENT);
            String fieldName = fieldTok.text();
            if (!decl.members().containsKey(fieldName)) {
                throw new ParseException(
                        "Struct '" + typeName + "' has no field '" + fieldName
                                + "'; declared fields: " + decl.members().keySet(),
                        fieldTok.origin());
            }
            if (provided.containsKey(fieldName)) {
                throw new ParseException(
                        "Field '" + fieldName + "' appears more than once in struct "
                                + "literal for '" + typeName + "'",
                        fieldTok.origin());
            }
            expect(AltToken.Kind.EQUALS);
            IrExpr value = parseExpr();
            provided.put(fieldName, value);
            first = false;
        }
        AltToken close = expect(AltToken.Kind.RBRACE);
        for (String declaredField : decl.members().keySet()) {
            if (!provided.containsKey(declaredField)) {
                throw new ParseException(
                        "Struct literal for '" + typeName + "' is missing field '"
                                + declaredField + "'; required fields: "
                                + decl.members().keySet(),
                        openBrace.origin());
            }
        }
        Map<String, IrExpr> ordered = new LinkedHashMap<>();
        for (String declaredField : decl.members().keySet()) {
            ordered.put(declaredField, provided.get(declaredField));
        }
        return new IrExpr.Record(typeName, ordered, openBrace.spanTo(close));
    }

    /**
     * In-expression let: {@code let NAME (:Sort)? = VALUE BODY}.
     *
     * <p>Surface form mirrors top-level {@link #parseLet} for the common case
     * but lives inside an expression scope. No explicit separator between
     * VALUE and BODY — the value is parsed greedily via {@link #parseExpr},
     * stopping naturally at non-operator tokens; BODY is the next expression.
     * Single-ident names only (dotted lets remain top-level).
     *
     * <p>Lowering: {@link IrExpr.LetIn}. The bound name is pushed into
     * {@link #currentScope} for body parsing only; on exit, the previous
     * binding (if any — e.g., a shadowed function param) is restored.
     */
    /**
     * Expression-level destructuring let: {@code let [Pattern] = VALUE BODY} is
     * sugar for {@code match VALUE { [Pattern] -> BODY }}. The match-totality
     * checker enforces the let rule for free: the pattern must be proven total
     * over the value's sort — trivially (a bare destructure) or via the kernel
     * (a provable narrowing) — otherwise it's a refutable pattern and the
     * compile fails, directing the user to a real match.
     */
    private IrExpr parseDestructuringLetExpr(AltToken start) throws ParseException {
        IrSort pattern = parsePattern();
        expect(AltToken.Kind.EQUALS);
        IrExpr value = parseExpr();
        checkTupleArity(value, pattern);
        IrExpr body = parseExpr();
        return desugarStructuralDestructure(
                value,
                List.of(new IrExpr.MatchBranch(pattern, body)),
                start.origin());
    }

    /**
     * Top-level destructuring let: {@code let [Ternion(a, b, c)] = VALUE}
     * lowers to a synthetic 0-arg value binding plus one 0-arg accessor per
     * bound field, each accessor being the single-arm match
     * {@code match __d { [Pattern] -> field }} — the proof obligation and the
     * binding semantics are identical to the expression level; only the
     * lowering target differs (declarations instead of a scoped expression,
     * exactly like plain lets).
     */
    private IrStmt parseDestructuringLetTop(AltToken start) throws ParseException {
        IrSort pattern = parsePattern();
        if (!(pattern instanceof IrSort.Structural sp)) {
            throw new ParseException(
                    "A top-level destructuring let needs a struct pattern — "
                            + "e.g. let [Ternion(a, b, c)] = …",
                    start.origin());
        }
        expect(AltToken.Kind.EQUALS);
        IrExpr value = parseExpr();
        checkTupleArity(value, sp);
        String synthetic = "__destructure$" + (syntheticCounter++);
        IrSort valueSort = inferMaximalSort(value);
        declaredTopLevelLets.put(synthetic, valueSort);
        // Collect every leaf binder (recursively through nested patterns), then
        // emit one accessor per binder. The accessor's body re-runs the full
        // pattern match and returns that binder — and desugarStructuralDestructure
        // (via wrapDestructureBindings) binds nested binders too, so a leaf like
        // `x` in `[Outer(Inner(x, y), c)]` reads as `synthetic._<inner>._<x>`.
        List<Map.Entry<String, IrSort>> binders = new ArrayList<>();
        collectPatternBinders(sp, binders);
        for (Map.Entry<String, IrSort> b : binders) {
            String binder = b.getKey();
            IrExpr accessor = desugarStructuralDestructure(
                    new IrExpr.Call(synthetic, List.of(), start.origin()),
                    List.of(new IrExpr.MatchBranch(sp, new IrExpr.Var(binder, start.origin()))),
                    start.origin());
            declaredTopLevelLets.put(binder, b.getValue());
            pendingTopLevelDecls.add(new IrStmt.FunctionDecl(
                    binder, List.of(), b.getValue(), accessor, start.origin(), true));
        }
        return new IrStmt.FunctionDecl(synthetic, List.of(), valueSort, value, start.origin(), true);
    }

    /**
     * Collects every leaf binder a structural pattern introduces, recursing
     * through nested struct/tuple patterns — the binder name (after renames) and
     * its sort. Mirrors {@link #wrapDestructureBindings}' walk: a nested
     * Structural member recurses; a literal-/{@code _}-constrained field binds
     * nothing; everything else is a leaf binder.
     */
    /**
     * Seeds a structural pattern's leaf binders into {@link #currentScope} for
     * the duration of an arm-body parse — so they're recognized as value
     * receivers (the only effect that matters at parse time). The binder's sort
     * is best-effort ({@code "_"} when the pattern leaves it as the placeholder);
     * the real field sort flows post-parse through the destructure desugar's
     * {@code let binder = scrutinee.field} and {@code MethodResolver}. A non-
     * structural pattern (a refinement, {@code [_]}) binds nothing.
     */
    private void seedPatternBinders(IrSort pattern) {
        if (!(pattern instanceof IrSort.Structural sp)) {
            return;
        }
        List<Map.Entry<String, IrSort>> binders = new ArrayList<>();
        collectPatternBinders(sp, binders);
        for (Map.Entry<String, IrSort> b : binders) {
            currentScope.put(b.getKey(), b.getValue() == null ? IrSort.named("_") : b.getValue());
        }
    }

    private void collectPatternBinders(
            IrSort.Structural sp, List<Map.Entry<String, IrSort>> out) {
        java.util.Set<String> constrainedOnly =
                literalConstrainedFields.getOrDefault(sp, java.util.Set.of());
        Map<String, String> renames = destructureRenames.getOrDefault(sp, Map.of());
        for (Map.Entry<String, IrSort> e : sp.members().entrySet()) {
            if (e.getValue() instanceof IrSort.Structural nested) {
                collectPatternBinders(nested, out);
            } else if (constrainedOnly.contains(e.getKey())) {
                // constrains the match, binds nothing
            } else {
                // A deferred slot's sort is the placeholder encoding (_$bind$x) —
                // the binder's real sort isn't known until link time, so seed it
                // as the unknown sort `_` for body parsing.
                IrSort sort = isDeferredEncodedSort(e.getValue()) ? IrSort.named("_") : e.getValue();
                out.add(Map.entry(renames.getOrDefault(e.getKey(), e.getKey()), sort));
            }
        }
    }

    private IrExpr parseLetExpr() throws ParseException {
        AltToken start = expectKeyword("let");
        if (peek().kind() == AltToken.Kind.LBRACKET) {
            return parseDestructuringLetExpr(start);
        }
        AltToken nameTok = expect(AltToken.Kind.IDENT);
        String name = nameTok.text();
        if (KEYWORDS.contains(name)) {
            throw new ParseException(
                    "Cannot bind keyword '" + name + "' as a let-name",
                    nameTok.origin());
        }
        if (peek().kind() == AltToken.Kind.DOT && peek(1).kind() == AltToken.Kind.LBRACE) {
            return parseDictDecompositionLetExpr(start, nameTok);
        }
        IrSort declaredSort = null;
        if (peek().kind() == AltToken.Kind.COLON) {
            consume();
            declaredSort = parseSort();
        }
        expect(AltToken.Kind.EQUALS);
        IrExpr value = parseExpr();
        IrSort inferred = inferMaximalSort(value);
        boolean intToDecimal = false;
        boolean streamAutobox = false;
        if (declaredSort != null) {
            String declaredBase = baseSortName(declaredSort);
            String inferredBase = baseSortName(inferred);
            // The lossless Int→Decimal embedding is not a mismatch (see
            // parseLet) — promoted and gate-judged at IR time.
            intToDecimal = "Decimal".equals(declaredBase) && "Int".equals(inferredBase);
            // "_record" against a declared name is the promotion sugar (see
            // parseLet) — AggregatePromotion stamps and validates at IR time.
            if (declaredBase != null && inferredBase != null
                    && !inferredBase.equals("_record")
                    && !inferredBase.equals("_")  // unknown floor — parser can't prove a mismatch, so abstain
                    && !intToDecimal
                    && !declaredBase.equals(inferredBase)) {
                if ("Stream".equals(declaredBase) && TUPLE_SENTINEL.equals(inferredBase)) {
                    // tuple → Stream[T] autobox (docs/iteration.md §8.6), same as parseLet.
                    requireStreamElements(declaredSort, inferred, start.origin());
                    streamAutobox = true;
                } else {
                    throw new ParseException(
                            "let '" + name + "' is declared " + describeSort(declaredSort)
                                    + " but its value is " + describeSort(inferred)
                                    + " — these are different types.",
                            start.origin());
                }
            }
        }
        IrSort binding = declaredSort != null
                && ("_record".equals(baseSortName(inferred)) || intToDecimal || streamAutobox)
                ? declaredSort
                : inferred;
        IrSort prevBinding = currentScope.get(name);
        boolean hadPrev = currentScope.containsKey(name);
        currentScope.put(name, binding);
        IrExpr body;
        try {
            body = parseExpr();
        } finally {
            if (hadPrev) currentScope.put(name, prevBinding);
            else currentScope.remove(name);
        }
        // The declared sort travels as the binding's claim — judged by the
        // construction gate three-way, like a constructor argument. The stream
        // autobox is gated at parse time (§8.6), so it carries no runtime claim.
        return new IrExpr.LetIn(name, binding, value, body, start.origin(),
                streamAutobox ? null : declaredSort);
    }

    /**
     * Match expression: {@code match SCRUTINEE [{] [pred] -> result ... [}]}.
     * Braces are optional — without them, arms continue until the next token
     * is not {@code [}.
     *
     * <p>Inside an arm, {@code @} is the scrutinee value-under-refinement (no
     * variable substitution magic — see {@code substituteVarWithSelf} in the
     * old parser, which is gone). When the scrutinee is a {@link IrExpr.Var}
     * naming an in-scope param/let, the arm's contextual {@code [pred]} form
     * inherits the param's base sort for the pattern's {@link IrSort.Refined}.
     */
    private IrExpr parseMatch() throws ParseException {
        AltToken start = expectKeyword("match");
        IrExpr scrutinee = parseExpr();

        // Determine contextual base from scrutinee, if possible.
        String inferredBase = inferBaseSortName(scrutinee);
        contextualBaseStack.push(inferredBase == null ? "" : inferredBase);

        try {
            boolean braced = peek().kind() == AltToken.Kind.LBRACE;
            if (braced) consume();

            List<IrExpr.MatchBranch> branches = new ArrayList<>();
            int defaultArmIndex = -1;
            Origin defaultArmOrigin = null;
            IrExpr defaultArmResult = null;

            while (isMatchArmStart()) {
                if (defaultArmIndex >= 0) {
                    throw new ParseException(
                            "Match arms after '_' default are not allowed; '_' must be the last arm",
                            peek().origin());
                }
                if (isUnderscoreArm()) {
                    AltToken underscore = consume();
                    expect(AltToken.Kind.ARROW);
                    IrExpr result = parseExpr();
                    defaultArmIndex = branches.size();
                    defaultArmOrigin = underscore.origin();
                    defaultArmResult = result;
                    // Placeholder pattern — replaced after the loop with the
                    // computed complement of the other arms' predicates.
                    branches.add(new IrExpr.MatchBranch(
                            new IrSort.Named("__default_placeholder", underscore.origin()),
                            result));
                } else {
                    IrSort pattern = parsePattern();
                    expect(AltToken.Kind.ARROW);
                    // The pattern's leaf binders are in scope for the arm body —
                    // seed them so a binder used as a method receiver reads as a
                    // value (`n.toString()` is a call on `n`, not the qualified
                    // name `n.toString`; see isValueReceiver). Saved/restored per
                    // arm so binders don't leak across arms or past the match.
                    Map<String, IrSort> savedScope = new LinkedHashMap<>(currentScope);
                    seedPatternBinders(pattern);
                    IrExpr result;
                    try {
                        result = parseExpr();
                    } finally {
                        currentScope.clear();
                        currentScope.putAll(savedScope);
                    }
                    // `[_]` parses as the universal Named("_") — the bracketed
                    // spelling of the default arm. Treat it exactly like bare `_`
                    // so its region becomes the computed complement of the other
                    // arms, not an unconstrained wildcard (otherwise a `[_]` arm
                    // is strictly weaker than the equivalent explicit arm, and
                    // proofs that should discharge over its region don't).
                    if (pattern instanceof IrSort.Named u && u.name().equals("_")) {
                        defaultArmIndex = branches.size();
                        defaultArmOrigin = pattern.origin();
                        defaultArmResult = result;
                        branches.add(new IrExpr.MatchBranch(
                                new IrSort.Named("__default_placeholder", pattern.origin()), result));
                    } else {
                        checkTupleArity(scrutinee, pattern);
                        branches.add(new IrExpr.MatchBranch(pattern, result));
                    }
                }
            }
            if (braced) expect(AltToken.Kind.RBRACE);

            if (branches.isEmpty()) {
                throw new ParseException(
                        "Match expression must have at least one branch",
                        start.origin());
            }

            // `_` desugar: replace the placeholder pattern with the complement
            // of the other arms' union over the scrutinee's sort. After this,
            // the IR sees only explicit refinements; totality holds by
            // construction (the complement covers exactly the leftover values).
            if (defaultArmIndex >= 0) {
                IrSort defaultPattern = computeDefaultArmPattern(
                        scrutinee, branches, defaultArmIndex, defaultArmOrigin);
                branches.set(defaultArmIndex,
                        new IrExpr.MatchBranch(defaultPattern, defaultArmResult));
            }

            // Destructure desugar: for each structural-pattern branch, wrap
            // the result with let-bindings so the pattern's field names refer
            // to fields of the scrutinee. If the scrutinee isn't already a Var,
            // wrap the entire match in an outer let so it's evaluated once.
            return desugarStructuralDestructure(scrutinee, branches, start.origin());
        } finally {
            contextualBaseStack.pop();
        }
    }

    /**
     * The iteration construct (docs/iteration.md §8), slice 1 — map + filter only.
     * {@code iter(src).{value, accept, reject} { match value <arms> }} lowers to
     * {@link IrExpr.Iterate}: destructured members pick outputs (accept/reject →
     * streams; a bare-value arm → the default stream), and each arm's
     * <em>disposition</em> ({@code accept(e)} / {@code reject(e)} / a bool /
     * a bare value) becomes a write. REVISIT (docs/iteration.md §10): index,
     * fold (current/next), group-by (put), the completed-iterator result, and the
     * conservation checks are not in this slice.
     */
    /**
     * A <em>fragment literal</em>: {@code [ (el:Int) -> body ]} — the synthesis
     * fragment as a first-class value (docs/stream-war.md §3, slice 2c). Parses to
     * an {@link IrExpr.Lambda} (a {@code Closure} at runtime), the lambda
     * replacement: a value you can bind, pass, and apply (notably by {@code &}
     * spread). The opening {@code [} is at {@code peek()}. Params scope the body,
     * mirroring {@link #parseFunction}.
     */
    private IrExpr.Lambda parseFragmentLiteral() throws ParseException {
        AltToken open = expect(AltToken.Kind.LBRACKET);
        expect(AltToken.Kind.LPAREN);
        List<IrParam> params = parseParamList(AltToken.Kind.RPAREN);
        expect(AltToken.Kind.RPAREN);
        List<ParamDestructure> destrs = drainParamDestructures();
        expect(AltToken.Kind.ARROW);
        Map<String, IrSort> savedScope = new LinkedHashMap<>(currentScope);
        currentScope.clear();
        for (IrParam p : params) currentScope.put(p.name(), p.sort());
        bindParamDestructures(destrs);
        IrExpr body;
        try {
            body = wrapParamDestructures(parseExpr(), destrs);
        } finally {
            currentScope.clear();
            currentScope.putAll(savedScope);
        }
        AltToken close = expect(AltToken.Kind.RBRACKET);
        // Return sort is inferred from the body; the explicit `:[Shape]` on the
        // application (and the construction gate) pins it where it matters.
        IrSort returnSort = inferMaximalSort(body);
        return new IrExpr.Lambda(params, returnSort, body, open.spanTo(close));
    }

    /** Whether {@code let NAME:} is followed by a fragment literal {@code [ (name: …) -> … ]}. */
    private boolean looksLikeFragmentLiteral() {
        return peek().kind() == AltToken.Kind.LBRACKET
                && peek(1).kind() == AltToken.Kind.LPAREN
                && peek(2).kind() == AltToken.Kind.IDENT
                && peek(3).kind() == AltToken.Kind.COLON;
    }

    private IrExpr parseIter() throws ParseException {
        AltToken start = consume();  // `iter`
        expect(AltToken.Kind.LPAREN);
        IrExpr source = parseExpr();
        expect(AltToken.Kind.RPAREN);
        // `.{ members }` — the capability set (slice 1: value / accept / reject).
        expect(AltToken.Kind.DOT);
        expect(AltToken.Kind.LBRACE);
        java.util.LinkedHashSet<String> members = new java.util.LinkedHashSet<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RBRACE) {
            if (!first) expect(AltToken.Kind.COMMA);
            AltToken m = expect(AltToken.Kind.IDENT);
            if (!java.util.Set.of("value", "accept", "reject").contains(m.text())) {
                throw new ParseException(
                        "Iterator member '" + m.text() + "' is not supported yet — slice 1 "
                                + "is map+filter (value, accept, reject); docs/iteration.md §10",
                        m.origin());
            }
            members.add(m.text());
            first = false;
        }
        expect(AltToken.Kind.RBRACE);
        if (!members.contains("value")) {
            throw new ParseException(
                    "Iterator block must destructure `value` (the current element)", start.origin());
        }
        // Element-sort inference (so refinement-pattern shorthands like `[0]` /
        // `[@>1]` get a base): a stream is a positional record (a tuple literal
        // `(1,2,3)`), so the element sort is its first member's. REVISIT
        // (docs/iteration.md §10): real element-type inference from the Source
        // contract (heterogeneous streams, non-literal sources).
        IrSort valueSort = null;
        if (inferMaximalSort(source) instanceof IrSort.Structural st && !st.members().isEmpty()) {
            // The element's BASE sort, not the first member's value-singleton:
            // `value` ranges over every element, so it must not inherit the first
            // literal's refinement (e.g. [Int:@==1]) — that would over-narrow the
            // scrutinee and, now that `[_]` is the complement within the
            // scrutinee's sort, make a `[_]` arm match only the first element.
            String base = baseSortName(st.members().values().iterator().next());
            if (base != null) valueSort = IrSort.named(base);
        }
        // `{ match value <arms> }` — `value` scoped so the match infers its base.
        Map<String, IrSort> savedScope = new LinkedHashMap<>(currentScope);
        if (valueSort != null) currentScope.put("value", valueSort);
        IrExpr body;
        AltToken end;
        try {
            expect(AltToken.Kind.LBRACE);
            body = parseMatch();
            end = expect(AltToken.Kind.RBRACE);
        } finally {
            currentScope.clear();
            currentScope.putAll(savedScope);
        }
        if (!(body instanceof IrExpr.Match matchExpr)) {
            throw new ParseException("Iterator block body must be a `match`", start.origin());
        }
        String element = matchExpr.scrutinee() instanceof IrExpr.Var v ? v.name() : "value";

        boolean usesAccept = false, usesReject = false, usesDefault = false;
        List<IrExpr.Arm> arms = new ArrayList<>(matchExpr.branches().size());
        for (IrExpr.MatchBranch b : matchExpr.branches()) {
            IrExpr.Write w = lowerDisposition(b.result(), element, members, start.origin());
            switch (w.output()) {
                case "accept" -> usesAccept = true;
                case "reject" -> usesReject = true;
                default -> usesDefault = true;
            }
            arms.add(new IrExpr.Arm(b.pattern(), List.of(w)));
        }
        List<IrExpr.OutputSpec> outputs = new ArrayList<>();
        if (usesDefault) outputs.add(new IrExpr.OutputSpec("default", IrExpr.OutputKind.STREAM, null));
        if (usesAccept) outputs.add(new IrExpr.OutputSpec("accept", IrExpr.OutputKind.STREAM, null));
        if (usesReject) outputs.add(new IrExpr.OutputSpec("reject", IrExpr.OutputKind.STREAM, null));
        return new IrExpr.Iterate(source, element, outputs, arms, start.spanTo(end));
    }

    /**
     * Lowers one arm's <em>disposition</em> expression to a write. {@code accept(e)}
     * / {@code reject(e)} (or no-arg, routing the current element) → a write to
     * that stream; a bool routes the current element (skip — legal because filter
     * is in scope); anything else is a bare value → the default (map) stream.
     */
    private IrExpr.Write lowerDisposition(
            IrExpr r, String element, java.util.Set<String> members, Origin o) throws ParseException {
        String verb = null;
        IrExpr arg = null;
        if (r instanceof IrExpr.Apply ap && ap.fn() instanceof IrExpr.Var fv
                && (fv.name().equals("accept") || fv.name().equals("reject"))) {
            verb = fv.name();
            arg = ap.args().isEmpty() ? new IrExpr.Var(element, o) : ap.args().get(0);
        } else if (r instanceof IrExpr.Call c
                && (c.functionName().equals("accept") || c.functionName().equals("reject"))) {
            verb = c.functionName();
            arg = c.args().isEmpty() ? new IrExpr.Var(element, o) : c.args().get(0);
        } else if (r instanceof IrExpr.Bool bl) {
            verb = bl.value() ? "accept" : "reject";  // bool = skip disposition
            arg = new IrExpr.Var(element, o);
        }
        if (verb != null) {
            if (!members.contains(verb)) {
                throw new ParseException(
                        "Arm uses `" + verb + "` but it is not destructured in `.{…}`", o);
            }
            return new IrExpr.Write(verb, null, arg);
        }
        return new IrExpr.Write("default", null, r);  // bare value → default (map) stream
    }

    /** True if the next token starts a match arm — either `[` or the `_` default. */
    private boolean isMatchArmStart() {
        return peek().kind() == AltToken.Kind.LBRACKET || isUnderscoreArm();
    }

    /** True if the next token is the `_` default-arm marker (an IDENT with text "_"). */
    private boolean isUnderscoreArm() {
        AltToken t = peek();
        return t.kind() == AltToken.Kind.IDENT && t.text().equals("_");
    }

    /**
     * Computes the {@link IrSort.Refined} pattern for a {@code _} default arm.
     * The predicate is the complement of the union of explicit arms'
     * predicates, taken over the scrutinee's sort (via
     * {@link PredicateArithmetic#complement}).
     *
     * <p>The result is in IR form so the IR sees only explicit predicates —
     * the {@code _} is fully desugared by the time it leaves the parser.
     */
    private IrSort computeDefaultArmPattern(
            IrExpr scrutinee,
            List<IrExpr.MatchBranch> branches,
            int defaultArmIndex,
            Origin defaultArmOrigin) {
        // Where the precise complement isn't computable, `_` falls back to the
        // universal pattern [_] — ordered match makes that total by
        // construction (the arm catches exactly what earlier arms didn't).
        // The precise complement is kept where the kernel can compute it,
        // because it gives the arm body an exact narrowing rather than `_`.
        IrSort universal = new IrSort.Named("_", defaultArmOrigin);

        IrSort scrutineeIrSort = inferScrutineeSort(scrutinee);
        if (scrutineeIrSort == null) {
            return universal;
        }

        // Union the explicit arms' predicates as SymExpr.
        SymExpr unionPredicate = null;
        for (int i = 0; i < branches.size(); i++) {
            if (i == defaultArmIndex) continue;
            IrSort armPattern = branches.get(i).pattern();
            if (!(armPattern instanceof IrSort.Refined refined)) {
                return universal;  // destructure / bare arms — no predicate to complement
            }
            SymExpr armPred;
            try {
                armPred = IrCompiler.compileSymExpr(refined.predicate());
            } catch (CompileException ce) {
                return universal;
            }
            unionPredicate = (unionPredicate == null) ? armPred : SymExpr.or(unionPredicate, armPred);
        }
        // No explicit arms — complement of false = entire domain.
        if (unionPredicate == null) unionPredicate = SymExpr.bool(false);

        Sort scrutineeSort;
        try {
            scrutineeSort = IrCompiler.compileSort(scrutineeIrSort);
        } catch (CompileException ce) {
            return universal;
        }

        ComplementResult complement = PredicateArithmetic.complement(unionPredicate, scrutineeSort);
        if (complement instanceof ComplementResult.Unknown) {
            return universal;  // outside the decidable fragment — order does the work
        }
        SymExpr complementSym = ((ComplementResult.Computed) complement).predicate();
        IrExpr complementIr = symExprToIrExpr(complementSym, defaultArmOrigin);

        return new IrSort.Refined(baseSortName(scrutineeIrSort), complementIr, defaultArmOrigin);
    }

    /** Returns the scrutinee's IrSort if it's a known in-scope Var; null otherwise. */
    private IrSort inferScrutineeSort(IrExpr expr) {
        if (expr instanceof IrExpr.Var v) {
            return currentScope.get(v.name());
        }
        return null;
    }

    /**
     * Converts a {@link SymExpr} back to an {@link IrExpr}, for the subset of
     * shapes produced by {@link PredicateArithmetic#complement} (Bool, Lit,
     * Self, Cmp of those, And, Or). Anything outside that subset is a
     * framework bug — the complement result should always stay within the
     * Int-comparison fragment.
     */
    private static IrExpr symExprToIrExpr(SymExpr expr, Origin origin) {
        return switch (expr) {
            case SymExpr.Bool b -> new IrExpr.Bool(b.value(), origin);
            case SymExpr.Lit l -> new IrExpr.Lit(l.value(), origin);
            case SymExpr.Dec d -> new IrExpr.Dec(d.value(), origin);
            case SymExpr.Self s -> new IrExpr.SelfRef(origin);
            case SymExpr.Cmp(SymExpr left, SymExpr.CmpOp op, SymExpr right) ->
                    new IrExpr.BinOp(cmpOpToIrOp(op),
                            symExprToIrExpr(left, origin),
                            symExprToIrExpr(right, origin),
                            origin);
            case SymExpr.And(SymExpr l, SymExpr r) ->
                    new IrExpr.BinOp(IrExpr.Op.AND,
                            symExprToIrExpr(l, origin),
                            symExprToIrExpr(r, origin),
                            origin);
            case SymExpr.Or(SymExpr l, SymExpr r) ->
                    new IrExpr.BinOp(IrExpr.Op.OR,
                            symExprToIrExpr(l, origin),
                            symExprToIrExpr(r, origin),
                            origin);
            default -> throw new IllegalStateException(
                    "Unexpected SymExpr in complement result (outside Int-comparison fragment): " + expr);
        };
    }

    private static IrExpr.Op cmpOpToIrOp(SymExpr.CmpOp op) {
        return switch (op) {
            case LT -> IrExpr.Op.LT;
            case LE -> IrExpr.Op.LE;
            case GT -> IrExpr.Op.GT;
            case GE -> IrExpr.Op.GE;
            case EQ -> IrExpr.Op.EQ;
            case NE -> IrExpr.Op.NE;
        };
    }

    /**
     * If any branch's pattern is structural, wrap the branch result with
     * {@code let field = scrutinee.field in ...} for each field, so the
     * pattern's field names are bound in the branch's result.
     */
    /**
     * Whether {@code sp} is a deferred (cross-module) positional struct pattern —
     * its struct was not in {@link #declaredStructs} at parse time, so its slots
     * are positional and {@code DestructureResolver} owns the post-link desugar.
     */
    private boolean isDeferredPattern(IrSort.Structural sp) {
        return deferredStructPatterns.contains(sp);
    }

    /** True if {@code s} is a deferred-slot placeholder ({@code _$bind$x}/{@code _$skip$}). */
    private static boolean isDeferredEncodedSort(IrSort s) {
        return s instanceof IrSort.Named n
                && (n.name().startsWith(DEFERRED_BIND) || n.name().startsWith(DEFERRED_SKIP));
    }

    private IrExpr desugarStructuralDestructure(
            IrExpr scrutinee,
            List<IrExpr.MatchBranch> branches,
            Origin matchOrigin) {
        boolean anyStructural = branches.stream().anyMatch(
                b -> b.pattern() instanceof IrSort.Structural);
        if (!anyStructural) {
            return new IrExpr.Match(scrutinee, branches, matchOrigin);
        }
        boolean needsOuterLet = !(scrutinee instanceof IrExpr.Var);
        String outerLetName = null;
        IrExpr scrutineeRef;
        if (needsOuterLet) {
            outerLetName = "__scrutinee$" + (syntheticCounter++);
            scrutineeRef = new IrExpr.Var(outerLetName, Origin.NONE);
        } else {
            scrutineeRef = scrutinee;
        }
        List<IrExpr.MatchBranch> wrapped = new ArrayList<>(branches.size());
        for (IrExpr.MatchBranch b : branches) {
            IrExpr result = b.result();
            // A DEFERRED (cross-module) struct pattern is left unwrapped here: its
            // slots are positional and the struct's field names are unknown until
            // link time, so DestructureResolver does the let-binding wrap post-link
            // (reading match.scrutinee(), which is always a Var after this method).
            if (b.pattern() instanceof IrSort.Structural sp && !isDeferredPattern(sp)) {
                result = wrapDestructureBindings(
                        sp, scrutineeRef, inferMaximalSort(scrutinee), result);
            }
            IrSort pattern = b.pattern();
            // A native-anatomy pattern ([Decimal(u, s)]) matches the CARRIER,
            // not a record — the arm's sort becomes the bare nominal name (the
            // destructure is irrefutable: every carrier has a canonical
            // anatomy), or a refinement over the anatomy when fields are
            // literal-constrained ([Decimal(25, s)] → [Decimal:@.unscaled==25]).
            if (b.pattern() instanceof IrSort.Structural sp
                    && sibarum.pontif.ir.NativeConstructors.has(sp.name())) {
                IrExpr conjunct = null;
                for (Map.Entry<String, IrSort> e : sp.members().entrySet()) {
                    if (!(e.getValue() instanceof IrSort.Refined rf)) continue;
                    IrExpr p = selfToFieldAccess(rf.predicate(), e.getKey());
                    conjunct = conjunct == null ? p
                            : new IrExpr.BinOp(IrExpr.Op.AND, conjunct, p, Origin.NONE);
                }
                pattern = conjunct == null
                        ? IrSort.named(sp.name())
                        : new IrSort.Refined(sp.name(), conjunct, Origin.NONE);
            }
            wrapped.add(new IrExpr.MatchBranch(pattern, result));
        }
        IrExpr match = new IrExpr.Match(scrutineeRef, wrapped, matchOrigin);
        if (!needsOuterLet) return match;
        // Thread the scrutinee's inferred sort through the outer let so the
        // placeholder "_" sentinel doesn't leak into the compiled module's
        // sort table. Falls back to "_" only when nothing tighter is known
        // (record-literal scrutinees give Structural, calls give the
        // callee's return, etc.).
        IrSort scrutineeSort = inferMaximalSort(scrutinee);
        return new IrExpr.LetIn(outerLetName, scrutineeSort, scrutinee, match, matchOrigin);
    }

    /**
     * Wraps {@code result} with the let-bindings a structural pattern
     * introduces, threading the {@code accessPath} so NESTED struct patterns
     * bind too: in {@code [(Traction(n1,z1), Traction(n2,z2))]} the element
     * {@code Traction(n1,z1)} binds {@code n1 = scrutinee._0.n},
     * {@code z1 = scrutinee._0.zexp}. Reverse order so the first field is the
     * outermost let. A literal-/{@code _}-constrained field constrains the match
     * but binds nothing (skipped, so the name isn't silently shadowed); a
     * member that is itself a Structural pattern recurses regardless.
     */
    private IrExpr wrapDestructureBindings(
            IrSort.Structural sp, IrExpr accessPath, IrSort accessSort, IrExpr result) {
        java.util.Set<String> constrainedOnly =
                literalConstrainedFields.getOrDefault(sp, java.util.Set.of());
        Map<String, String> renames = destructureRenames.getOrDefault(sp, Map.of());
        // A tuple pattern's member sorts are the placeholder `_` (resolved from
        // the scrutinee); recover the real element sort from the scrutinee's
        // inferred sort so a tuple binder used as a method receiver (`i.bump()`
        // in [(i, k)] over (Inner, Int)) is typed, not stuck at `_`.
        Map<String, IrSort> accessMembers = accessSort instanceof IrSort.Structural as
                ? as.members() : Map.of();
        List<Map.Entry<String, IrSort>> entries = new ArrayList<>(sp.members().entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map.Entry<String, IrSort> e = entries.get(i);
            IrExpr fieldAccess = new IrExpr.FieldAccess(accessPath, e.getKey(), Origin.NONE);
            IrSort elemSort = accessMembers.get(e.getKey());
            if (e.getValue() instanceof IrSort.Structural nested) {
                // A DEFERRED nested struct (cross-module, e.g. the Vec of
                // [(Vec(x,y), c)]) has positional slots whose field names aren't
                // known yet — leave its bindings to DestructureResolver, which
                // descends from this same access path post-link.
                if (!isDeferredPattern(nested)) {
                    result = wrapDestructureBindings(nested, fieldAccess, elemSort, result);
                }
            } else if (constrainedOnly.contains(e.getKey())) {
                // literal / `_` constraint: matches, binds nothing.
                continue;
            } else {
                IrSort binderSort = isUnknownSort(e.getValue()) && elemSort != null
                        ? elemSort : e.getValue();
                result = new IrExpr.LetIn(
                        renames.getOrDefault(e.getKey(), e.getKey()),
                        binderSort,
                        fieldAccess,
                        result,
                        Origin.NONE);
            }
        }
        return result;
    }

    /** True if {@code s} is the unknown placeholder sort {@code _}. */
    private static boolean isUnknownSort(IrSort s) {
        return s instanceof IrSort.Named n && n.name().equals("_");
    }

    /**
     * Rewrites a per-field pattern predicate ({@code @==25}, Self meaning the
     * FIELD) into a whole-value predicate ({@code @.unscaled==25}, Self
     * meaning the carrier) — the form native-anatomy patterns refine with.
     */
    private static IrExpr selfToFieldAccess(IrExpr pred, String field) {
        return switch (pred) {
            case IrExpr.SelfRef s -> new IrExpr.FieldAccess(s, field, s.origin());
            case IrExpr.BinOp op -> new IrExpr.BinOp(op.op(),
                    selfToFieldAccess(op.left(), field),
                    selfToFieldAccess(op.right(), field),
                    op.origin());
            default -> pred;  // literals and anything Self-free pass through
        };
    }

    /**
     * Best-effort base-sort name for a scrutinee expression. Returns {@code null}
     * if no base can be inferred (the scrutinee isn't a Var in scope, or is a
     * non-named sort like Function). The contextual {@code [pred]} arm form
     * then becomes an error inside this match.
     */
    private String inferBaseSortName(IrExpr expr) {
        if (expr instanceof IrExpr.Var v) {
            IrSort s = currentScope.get(v.name());
            if (s != null) return baseSortName(s);
        }
        return null;
    }

    /**
     * The figurative tuple→{@code Stream[T]} element gate (docs/iteration.md §8.6):
     * every member of the tuple must be convertible to {@code T}. Base-level for
     * now (exact base, plus the lossless Int→Decimal embedding); the multi-dispatch
     * promotion path will subsume it.
     */
    private static void requireStreamElements(IrSort declaredStream, IrSort tupleSort, Origin o)
            throws ParseException {
        IrSort elemType = declaredStream instanceof IrSort.Named sn && !sn.typeArgs().isEmpty()
                ? sn.typeArgs().get(0) : null;
        String tBase = elemType == null ? null : baseSortName(elemType);
        if (tBase == null || !(tupleSort instanceof IrSort.Structural st)) return;
        int idx = 0;
        for (IrSort m : st.members().values()) {
            String mBase = baseSortName(m);
            boolean ok = tBase.equals(mBase) || ("Decimal".equals(tBase) && "Int".equals(mBase));
            if (!ok) {
                throw new ParseException(
                        "Cannot box this tuple as Stream[" + describeSort(elemType) + "]: element "
                                + idx + " is " + describeSort(m) + ", not " + describeSort(elemType),
                        o);
            }
            idx++;
        }
    }

    /** A compact, human-readable rendering of a sort for error messages. */
    private static String describeSort(IrSort s) {
        return switch (s) {
            case IrSort.Named n -> {
                if (n.typeArgs().isEmpty()) yield n.name();
                StringBuilder sb = new StringBuilder(n.name()).append("[");
                for (int i = 0; i < n.typeArgs().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(describeSort(n.typeArgs().get(i)));
                }
                yield sb.append("]").toString();
            }
            case IrSort.Refined r -> r.name();  // base only; the predicate is elided for readability
            case IrSort.Structural st -> {
                if (!TUPLE_SENTINEL.equals(st.name())) yield st.name();
                StringBuilder sb = new StringBuilder("(");
                boolean first = true;
                for (IrSort m : st.members().values()) {
                    if (!first) sb.append(", ");
                    sb.append(describeSort(m));
                    first = false;
                }
                yield sb.append(")").toString();
            }
            case IrSort.Method m -> "Method(…)";
            case IrSort.Dispatch d -> "Dispatch(…)";
            case IrSort.Union u -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < u.branches().size(); i++) {
                    if (i > 0) sb.append(" | ");
                    sb.append(describeSort(u.branches().get(i)));
                }
                yield sb.toString();
            }
            case IrSort.Intersection i -> {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < i.branches().size(); j++) {
                    if (j > 0) sb.append(" & ");
                    sb.append(describeSort(i.branches().get(j)));
                }
                yield sb.toString();
            }
            case IrSort.Trait t -> t.name();
        };
    }

    private static String baseSortName(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> r.name();
            case IrSort.Structural s -> s.name();
            case IrSort.Method f -> null;
            case IrSort.Dispatch d -> "Dispatch";
            case IrSort.Trait t -> t.name();
            // Cross-base unions/intersections have no single base name.
            case IrSort.Union u -> null;
            case IrSort.Intersection i -> null;
        };
    }

    /**
     * If {@code expr} is a chain of {@link IrExpr.FieldAccess} rooted at an
     * {@link IrExpr.Var}, returns the dotted name (e.g., {@code "Point.manhattan"}).
     * Otherwise returns {@code null}. Used by {@code parsePrimaryWithPostfix}
     * to decide whether {@code Name.fn(args)} is a qualified Call or an Apply.
     */
    private static String extractDottedName(IrExpr expr) {
        if (expr instanceof IrExpr.Var v) return v.name();
        if (expr instanceof IrExpr.FieldAccess fa) {
            String base = extractDottedName(fa.base());
            if (base == null) return null;
            return base + "." + fa.fieldName();
        }
        return null;
    }

    private List<IrExpr> parseArgList() throws ParseException {
        List<IrExpr> args = new ArrayList<>();
        if (peek().kind() == AltToken.Kind.RPAREN) return args;
        args.add(parseArg());
        while (peek().kind() == AltToken.Kind.COMMA) {
            consume();
            args.add(parseArg());
        }
        return args;
    }

    /**
     * Reserved callee name for the transient <em>spread</em> marker: a {@code &expr}
     * argument is wrapped as {@code Call(SPREAD_SENTINEL, [source])} so it rides the
     * existing {@code List<IrExpr>} arg shape without a new IR variant. It never
     * survives parsing — {@link #lowerSpreadCall} consumes every spread when the
     * enclosing call is built. The {@code &} can't appear in a user identifier, so
     * the name can't collide.
     */
    private static final String SPREAD_SENTINEL = "&spread";

    /**
     * One call argument, recognizing the stream-<em>spread</em> prefix {@code &expr}
     * (docs/stream-war.md §3). A leading {@code &} at the start of an argument is
     * unambiguous — binary {@code &} (refinement conjunction) only occurs inside a
     * refinement bracket, never as an expression head — so it marks the argument as
     * a per-element spread over a stream. Anything else is an ordinary argument.
     */
    private IrExpr parseArg() throws ParseException {
        if (peek().kind() == AltToken.Kind.OP && "&".equals(peek().text())) {
            AltToken amp = consume();
            IrExpr source = parseExpr();
            return new IrExpr.Call(SPREAD_SENTINEL, List.of(source), amp.origin());
        }
        return parseExpr();
    }

    private static boolean isSpread(IrExpr arg) {
        return arg instanceof IrExpr.Call c && SPREAD_SENTINEL.equals(c.functionName());
    }

    private static boolean anySpread(List<IrExpr> args) {
        return args.stream().anyMatch(AltParser::isSpread);
    }

    /**
     * Lowers a call carrying a {@code &}-spread argument to an {@link IrExpr.Iterate}
     * — the synthesis-fragment primitive, slice 2a (docs/stream-war.md §3, the
     * single-stream <em>map</em> shape). {@code rebuild} reconstructs the underlying
     * call form (a {@code Call}, {@code Apply}, or {@code MethodCall}) given the
     * per-element argument list; the one spread position is replaced by the bound
     * element, and the whole call becomes the body of a one-output stream iteration:
     * each element of the source is mapped through the function and emitted.
     *
     * <p>Exactly one spread is the map shape; multiple spreads (zip) and tuple-return
     * fan-out are later sub-slices, rejected here with a pointer.
     */
    private IrExpr lowerSpreadCall(
            List<IrExpr> args, java.util.function.Function<List<IrExpr>, IrExpr> rebuild, Origin o)
            throws ParseException {
        int spreadAt = -1;
        for (int i = 0; i < args.size(); i++) {
            if (isSpread(args.get(i))) {
                if (spreadAt != -1) {
                    throw new ParseException(
                            "Multiple `&` spreads in one call (zip / fan-in) is not implemented "
                                    + "yet — slice 2c; docs/stream-war.md §3", o);
                }
                spreadAt = i;
            }
        }
        if (spreadAt == -1) return rebuild.apply(args);

        IrExpr source = ((IrExpr.Call) args.get(spreadAt)).args().get(0);
        String element = "$e" + (syntheticCounter++);
        List<IrExpr> perElement = new ArrayList<>(args);
        perElement.set(spreadAt, new IrExpr.Var(element, o));
        IrExpr body = rebuild.apply(perElement);

        IrExpr.OutputSpec out = new IrExpr.OutputSpec("default", IrExpr.OutputKind.STREAM, null);
        IrExpr.Arm arm = new IrExpr.Arm(
                IrSort.named("_"), List.of(new IrExpr.Write("default", null, body)));
        return new IrExpr.Iterate(source, element, List.of(out), List.of(arm), o);
    }

    /**
     * Builds a tuple literal: a {@link IrExpr.Record} carrying the reserved
     * {@code "_tuple"} sentinel name and positional keys {@code _0 .. _n}.
     * Tuples are anonymous positional aggregates — the positional sibling of
     * the {@code "_record"} sentinel — so they ride the existing record
     * substrate with no dedicated IR node.
     */
    private IrExpr buildTupleLiteral(List<IrExpr> elems, Origin origin) {
        Map<String, IrExpr> members = new LinkedHashMap<>();
        for (int i = 0; i < elems.size(); i++) {
            members.put("_" + i, elems.get(i));
        }
        return new IrExpr.Record(TUPLE_SENTINEL, members, origin);
    }

    private IrExpr parsePrimary() throws ParseException {
        AltToken t = peek();
        return switch (t.kind()) {
            case INTEGER -> {
                consume();
                yield new IrExpr.Lit(Long.parseLong(t.text()), t.origin());
            }
            case DECIMAL -> {
                consume();
                yield new IrExpr.Dec(new java.math.BigDecimal(t.text()), t.origin());
            }
            case CHAR -> {
                consume();
                yield new IrExpr.Chr(t.text().codePointAt(0), t.origin());
            }
            case STRING -> {
                consume();
                yield new IrExpr.Str(t.text(), t.origin());
            }
            case IDENT -> {
                if (t.text().equals("true")) {
                    consume();
                    yield new IrExpr.Bool(true, t.origin());
                }
                if (t.text().equals("false")) {
                    consume();
                    yield new IrExpr.Bool(false, t.origin());
                }
                if (t.text().equals("match")) {
                    yield parseMatch();
                }
                if (t.text().equals("let")) {
                    yield parseLetExpr();
                }
                if (t.text().equals("iter")) {
                    yield parseIter();
                }
                if (KEYWORDS.contains(t.text())) {
                    throw new ParseException(
                            "Unexpected keyword '" + t.text() + "' in expression position",
                            t.origin());
                }
                // Just the first ident — parsePrimaryWithPostfix builds the
                // dotted chain via FieldAccess. Whether the whole chain ends
                // as a Call (qualified name) or stays as FieldAccess (field
                // read) is decided when we see (or don't see) a trailing `(`.
                // Top-level-let access (bare or dotted) is rewritten to a
                // 0-arg Call at the end of parsePrimaryWithPostfix.
                AltToken nameTok = consume();
                yield new IrExpr.Var(nameTok.text(), nameTok.origin());
            }
            case LPAREN -> {
                AltToken lp = consume();
                // Value-space cast `(Type:value)` — explicit coercion, the
                // sibling of the type-space refinement `[Base:pred]`
                // (docs/dispatch-unification.md → "Coercion"). A complete sort
                // immediately followed by `:` is unambiguously a cast: in
                // expression position there is no binder `(name:Type)` (that
                // lives in declaration slots) and `:` is not an expression
                // operator, so nothing else can produce a top-level colon here.
                // The capitalization law fixes the reading before scope matters
                // — the target is a Type (`[…]` refinement or a Capitalized
                // name), never a lowercase binder name.
                IrSort castTarget = tryParseCastTarget();
                if (castTarget != null) {
                    IrExpr value = parseExpr();
                    AltToken close = expect(AltToken.Kind.RPAREN);
                    yield new IrExpr.Cast(castTarget, value, lp.spanTo(close));
                }
                IrExpr inner = parseExpr();
                if (peek().kind() == AltToken.Kind.COMMA) {
                    // Tuple literal: (e0, e1, ...) — an anonymous positional
                    // aggregate. Lowers onto the record substrate as a Record
                    // with the reserved "_tuple" sentinel name and positional
                    // keys _0.._n (the sibling of the "_record" sentinel). No
                    // new IR node: everything downstream consumes it as a
                    // structural record. Arity >= 2; `(x)` stays grouping.
                    List<IrExpr> elems = new ArrayList<>();
                    elems.add(inner);
                    while (peek().kind() == AltToken.Kind.COMMA) {
                        consume();
                        elems.add(parseExpr());
                    }
                    expect(AltToken.Kind.RPAREN);
                    yield buildTupleLiteral(elems, lp.origin());
                }
                expect(AltToken.Kind.RPAREN);
                yield inner;
            }
            case LBRACE -> {
                // Dictionary literal `{a = 1, b = 2}` vs block `{ EXPR }`: an
                // `IDENT =` head can't start a block expression (`=` isn't an
                // expression operator), so one token of lookahead disambiguates.
                if (peek(1).kind() == AltToken.Kind.IDENT
                        && peek(2).kind() == AltToken.Kind.EQUALS) {
                    yield parseDictLiteral();
                }
                // Block expression: `{ EXPR }`. A pure delimiter — the block
                // evaluates to its inner expression with no new semantics.
                // Useful for giving multi-let chains an explicit closing
                // boundary so greedy Pratt parsing of `let X = value BODY`
                // terminates at the `}` instead of wandering into whatever
                // follows in the enclosing context.
                consume();
                IrExpr inner = parseExpr();
                expect(AltToken.Kind.RBRACE);
                yield inner;
            }
            case AT -> {
                // @ is the principal subject — value-under-refinement, or the
                // scrutinee inside a match arm. At runtime it has no value (it's
                // a typing-context placeholder), but it lives in the IR as SelfRef.
                consume();
                yield new IrExpr.SelfRef(t.origin());
            }
            case DOLLAR -> {
                // Name literal: `$` marks a NAME (the namespace level — quoted,
                // not evaluated), the third element of the notation law (`[]`
                // types, `()` values, `$` names). The general production is the
                // $fqn literal; its one implemented case is the dispatch
                // reference `$name[Sorts]`, which reifies the dispatch keyed at
                // those sorts (sort `[Dispatch(...):...]`).
                AltToken dollar = consume();
                AltToken firstName = expect(AltToken.Kind.IDENT);
                StringBuilder name = new StringBuilder(firstName.text());
                AltToken lastName = firstName;
                while (peek().kind() == AltToken.Kind.DOT
                        && peek(1).kind() == AltToken.Kind.IDENT) {
                    consume();                       // DOT
                    AltToken seg = consume();        // IDENT
                    name.append('.').append(seg.text());
                    lastName = seg;
                }
                String dottedName = name.toString();
                AltToken next = peek();
                boolean adjacentBracket = next.kind() == AltToken.Kind.LBRACKET
                        && next.line() == lastName.line()
                        && next.column() == lastName.column() + lastName.text().length();
                if (adjacentBracket) {
                    consume();                       // LBRACKET
                    List<IrSort> keys = new ArrayList<>();
                    boolean firstKey = true;
                    while (peek().kind() != AltToken.Kind.RBRACKET) {
                        if (!firstKey) expect(AltToken.Kind.COMMA);
                        keys.add(parseSort());
                        firstKey = false;
                    }
                    AltToken close = expect(AltToken.Kind.RBRACKET);
                    yield new IrExpr.DispatchRef(dottedName, keys, dollar.spanTo(close));
                }
                throw new ParseException(
                        "A bare name literal ($" + dottedName + ") is not yet a "
                                + "value — dispatch references take key sorts "
                                + "($name[Sorts]); bare type references are a "
                                + "later slice.",
                        dollar.origin());
            }
            case OP -> {
                // Unary minus: `-x` ⇒ `0 - x` (cheap desugar).
                if (t.text().equals("-")) {
                    consume();
                    IrExpr operand = parsePrimaryWithPostfix();
                    yield new IrExpr.BinOp(IrExpr.Op.SUB,
                            new IrExpr.Lit(0, t.origin()), operand, t.origin());
                }
                // Unary not: parsed but the IR has no Not op yet (TODO).
                if (t.text().equals("!")) {
                    throw new ParseException(
                            "Unary '!' is recognized but the IR has no Not op yet — see TODO",
                            t.origin());
                }
                throw new ParseException(
                        "Unexpected operator '" + t.text() + "' in expression position",
                        t.origin());
            }
            default -> throw new ParseException(
                    "Unexpected token " + t.kind() + " '" + t.text() + "' in expression position",
                    t.origin());
        };
    }
}

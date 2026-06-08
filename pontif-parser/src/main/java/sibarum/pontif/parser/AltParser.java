package sibarum.pontif.parser;

import sibarum.pontif.core.Origin;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.types.Sort;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrExpr;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;
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
            "function", "method", "struct", "let",
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
                "function", "method", "struct", "let", "assign", "proof").contains(t.text());
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
            case "let"      -> parseLet();
            case "assign"   -> parseAssignTrait();
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
        expect(AltToken.Kind.LPAREN);
        List<IrParam> params = parseParamList(AltToken.Kind.RPAREN);
        expect(AltToken.Kind.RPAREN);
        List<ParamDestructure> destrs = drainParamDestructures();
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
                return new IrStmt.FunctionDecl(name, params, returnSort, body, start.origin());
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
                return new IrStmt.FunctionDecl(name, params, effReturn, body, start.origin());
            }
            throw specOnlyWithoutSynthesis("function", name, returnSort, start.origin());
        }
        throw new ParseException(
                "function '" + name + "' needs a body ('-> expr') or a synthesis "
                        + "directive (';')",
                peek().origin());
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
    private static String describeSort(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> n.name();
            case IrSort.Refined r -> "[" + r.name() + ":...]";
            default -> sort.getClass().getSimpleName();
        };
    }

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
            }
            params.add(new IrParam(name.text(), sort));
            first = false;
        }
        return params;
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
     * For a return sort of shape {@code Refined(_, @ == EXPR)} where EXPR
     * doesn't itself reference {@code @}, pulls out EXPR as the synthesized
     * body. Returns {@code null} if the sort isn't synthesizable.
     */
    private static IrExpr tryDeriveBodyFromReturnSort(IrSort returnSort) {
        if (!(returnSort instanceof IrSort.Refined r)) return null;
        if (!(r.predicate() instanceof IrExpr.BinOp bop)) return null;
        if (bop.op() != IrExpr.Op.EQ) return null;
        // Accept either side: users naturally write `@==EXPR` (which the implicit
        // sugar also produces), but hand-built IR could be flipped.
        IrExpr candidate;
        if (bop.left() instanceof IrExpr.SelfRef && !(bop.right() instanceof IrExpr.SelfRef)) {
            candidate = bop.right();
        } else if (bop.right() instanceof IrExpr.SelfRef && !(bop.left() instanceof IrExpr.SelfRef)) {
            candidate = bop.left();
        } else {
            return null;
        }
        if (containsSelfRef(candidate)) return null;
        return candidate;
    }

    /**
     * The declared return for a synthesized body. A construction-pin synthesis
     * (the body is a struct construction, e.g. {@code Point3D(x, y, z)}) is
     * DEFINITIONAL — the body IS the construction — so the declared return is
     * the bare struct, not the self-referential {@code @ == Point3D(…)}
     * obligation (which the return gate can't discharge through the param-
     * destructure `let` indirection). Value pins ({@code @ == n*2}) keep their
     * refinement — the gate proves those reflexively.
     */
    private static IrSort effectiveSynthesizedReturn(IrExpr derived, IrSort declaredReturn) {
        if (derived instanceof IrExpr.Record rec && rec.typeName() != null) {
            return new IrSort.Named(rec.typeName(), declaredReturn.origin());
        }
        // In-type pipeline (S8): a let-chain body's pin (@ == let … in witness)
        // is definitional and carries opaque calls the gate can't reflexively
        // prove — declare the bare final base.
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
        if (peek().kind() == AltToken.Kind.DOT && peek(1).kind() == AltToken.Kind.LBRACE) {
            return parseDictDecompositionLetTop(start, name);
        }
        IrSort declaredSort = null;
        if (peek().kind() == AltToken.Kind.COLON) {
            consume();
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
        // Trait declaration: `let X:Type{...}` (no value). Lower to TypeAlias
        // with the binding's name patched into the trait sort. Each contract
        // method is also registered in `declaredFunctionReturns` under
        // `TraitName.methodName` so method-call routing on trait-typed
        // receivers (e.g., `d.quack()` where `d:Duck`) finds them.
        if (declaredSort instanceof IrSort.Trait t) {
            if (value != null) {
                throw new ParseException(
                        "let '" + name + "' with trait sort can't have a value — "
                                + "trait declarations are type-level only",
                        start.origin());
            }
            IrSort.Trait named = new IrSort.Trait(name, t.methods(), t.origin());
            for (Map.Entry<String, IrSort.Method> e : named.methods().entrySet()) {
                declaredFunctionReturns.put(
                        name + "." + e.getKey(), e.getValue().returnSort());
            }
            return new IrStmt.TypeAlias(name, named, start.origin());
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
                    && !intToDecimal
                    && !declaredBase.equals(inferredBase)) {
                // A declared DEMOTION: the value's struct carries a base sort
                // that demotes to the claimed base (`struct Point3D:[Point:…]`),
                // so `let b:Point = a` is a valid projection, not a mismatch —
                // ConstructionGate runs the morphism at IR time. The binding is
                // recorded at the demoted (base) sort.
                if (demotesTo(inferredBase, declaredBase)) {
                    demotion = true;
                } else {
                    throw new ParseException(
                            "let '" + name + "' declared as " + declaredSort
                                    + " but value's inferred sort is " + inferredSort
                                    + " (base sort mismatch)",
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
        IrSort binding = demotion
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
        if (declaredSort != null && !"_record".equals(baseSortName(inferredSort))) {
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

    private IrSort inferMaximalSort(IrExpr expr) {
        return switch (expr) {
            case IrExpr.Lit lit -> new IrSort.Refined(
                    "Int",
                    new IrExpr.BinOp(IrExpr.Op.EQ,
                            new IrExpr.SelfRef(lit.origin()),
                            lit,
                            lit.origin()),
                    lit.origin());
            case IrExpr.Bool b -> new IrSort.Refined(
                    "Bool",
                    new IrExpr.BinOp(IrExpr.Op.EQ,
                            new IrExpr.SelfRef(b.origin()),
                            b,
                            b.origin()),
                    b.origin());
            // Decimal literals carry no value-level narrowing (the discharge
            // engine is integer-only); the bare Decimal sort is the maximal
            // shape we infer.
            case IrExpr.Dec d -> new IrSort.Named("Decimal", d.origin());
            // A metareference's maximal shape is its Dispatch sort; the
            // return stays "_" at parse level (candidates aren't consulted).
            case IrExpr.DispatchRef d -> new IrSort.Dispatch(
                    d.keySorts(), new IrSort.Named("_", d.origin()), d.origin());
            // Same stance for Char in the value slice: bare Char.
            case IrExpr.Chr c -> new IrSort.Named("Char", c.origin());
            case IrExpr.Var v -> {
                IrSort scoped = currentScope.get(v.name());
                if (scoped != null) yield scoped;
                IrSort topLevel = declaredTopLevelLets.get(v.name());
                if (topLevel != null) yield topLevel;
                yield IrSort.named("_");
            }
            case IrExpr.Record r -> {
                Map<String, IrSort> memberSorts = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> e : r.members().entrySet()) {
                    memberSorts.put(e.getKey(), inferMaximalSort(e.getValue()));
                }
                // The record's own claim is the inferred name: a named struct
                // stays itself, a tuple stays "_tuple", and an anonymous record
                // stays "_record" — NEVER christened after a same-shaped struct
                // (that was findStructByFieldSet, retired by the claim rule:
                // names come from declared assertions via AggregatePromotion,
                // not from shape guessing).
                String name = r.typeName() != null ? r.typeName() : "_record";
                yield new IrSort.Structural(name, memberSorts, r.origin());
            }
            case IrExpr.FieldAccess fa -> {
                IrSort baseSort = inferMaximalSort(fa.base());
                if (baseSort instanceof IrSort.Structural sp) {
                    IrSort fieldSort = sp.members().get(fa.fieldName());
                    if (fieldSort != null) yield fieldSort;
                }
                yield IrSort.named("_");
            }
            case IrExpr.BinOp op -> {
                // Decimal arithmetic yields a bare Decimal (no value refinement —
                // see the Dec literal case). Int arithmetic and all comparisons
                // keep the value-pinned refinement.
                boolean decimalArith = switch (op.op()) {
                    case ADD, SUB, MUL, DIV, MOD, POW -> isDecimalOperand(op.left()) || isDecimalOperand(op.right());
                    default -> false;
                };
                if (decimalArith) {
                    yield IrSort.named("Decimal");
                }
                String baseName = switch (op.op()) {
                    case ADD, SUB, MUL, DIV, MOD, POW -> "Int";
                    case LT, LE, GT, GE, EQ, NE, APPROX, AND, OR -> "Bool";
                };
                yield new IrSort.Refined(
                        baseName,
                        new IrExpr.BinOp(IrExpr.Op.EQ,
                                new IrExpr.SelfRef(op.origin()),
                                op,
                                op.origin()),
                        op.origin());
            }
            case IrExpr.Call call -> {
                IrSort declaredReturn = declaredFunctionReturns.get(call.functionName());
                if (declaredReturn != null) yield declaredReturn;
                // Top-level let names lower to 0-arg Calls — check there too
                // so method-call routing can see a let-receiver's actual sort.
                IrSort letSort = declaredTopLevelLets.get(call.functionName());
                if (letSort != null) yield letSort;
                yield IrSort.named("_");
            }
            case IrExpr.Apply ap -> IrSort.named("_");
            case IrExpr.Lambda lam -> new IrSort.Method(
                    lam.params().stream().map(IrParam::sort).toList(),
                    lam.returnSort(),
                    lam.origin());
            case IrExpr.Match m -> IrSort.named("_");
            case IrExpr.LetIn l -> inferMaximalSort(l.body());
            case IrExpr.SelfRef s -> IrSort.named("_");
        };
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

    private IrStmt parseAssignTrait() throws ParseException {
        AltToken start = expectKeyword("assign");
        expectKeyword("trait");
        AltToken typeNameTok = expect(AltToken.Kind.IDENT);
        expect(AltToken.Kind.COLON);
        AltToken traitNameTok = expect(AltToken.Kind.IDENT);
        expect(AltToken.Kind.LBRACE);

        String typeName = typeNameTok.text();
        IrSort selfSort = new IrSort.Named(typeName, typeNameTok.origin());

        List<IrStmt.FunctionDecl> methods = new ArrayList<>();
        while (peek().kind() != AltToken.Kind.RBRACE) {
            methods.add(parseTraitImplMethod(typeName, selfSort));
        }
        AltToken close = expect(AltToken.Kind.RBRACE);
        return new IrStmt.TraitImpl(
                typeName, traitNameTok.text(), methods, start.spanTo(close));
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
        IrSort.Structural structSort = new IrSort.Structural(nameTok.text(), members, baseSort, origin);
        declaredStructs.put(nameTok.text(), structSort);
        return new IrStmt.TypeAlias(nameTok.text(), structSort, origin);
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

    public IrSort parseSort() throws ParseException {
        AltToken t = peek();
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
            // `Type{...}` — trait literal at sort level. The trait's name is
            // empty here (it's anonymous); parseLet patches it with the
            // let-binding's name before producing the TypeAlias.
            if (t.text().equals("Type") && peek(1).kind() == AltToken.Kind.LBRACE) {
                return parseTraitTypeLiteral();
            }
            // `Name{e1, e2, …}` — a construction-pin return sort over a declared
            // struct (S5): desugars to `[Name:@ == Name(e1, …)]`, so spec-only
            // synthesis derives the body `Name(e1, …)` via the @==EXPR path.
            if (peek(1).kind() == AltToken.Kind.LBRACE && declaredStructs.containsKey(t.text())) {
                return parseConstructionPinSort();
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
        return new IrSort.Refined(base,
                new IrExpr.BinOp(IrExpr.Op.EQ, new IrExpr.SelfRef(open.origin()), chain, open.origin()),
                open.spanTo(close));
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
     * Parses {@code Type{methodName:MethodSort, ...}} — the trait literal.
     * Each entry must be {@code methodName:[Method(...):Ret]} (method
     * sort). The returned {@link IrSort.Trait} has an empty placeholder
     * name; {@link #parseLet} patches it with the binding name from the
     * enclosing {@code let X:Type{...}} declaration.
     */
    private IrSort.Trait parseTraitTypeLiteral() throws ParseException {
        AltToken typeTok = expect(AltToken.Kind.IDENT);  // "Type"
        AltToken open = expect(AltToken.Kind.LBRACE);
        Map<String, IrSort.Method> methods = new LinkedHashMap<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RBRACE) {
            if (!first) expect(AltToken.Kind.COMMA);
            AltToken methodName = expect(AltToken.Kind.IDENT);
            expect(AltToken.Kind.COLON);
            IrSort methodSort = parseSort();
            if (!(methodSort instanceof IrSort.Method fn)) {
                throw new ParseException(
                        "Trait method '" + methodName.text() + "' must have a "
                                + "method sort like [Method(args):Ret]; got " + methodSort,
                        methodName.origin());
            }
            if (methods.containsKey(methodName.text())) {
                throw new ParseException(
                        "Duplicate method '" + methodName.text() + "' in trait body",
                        methodName.origin());
            }
            methods.put(methodName.text(), fn);
            first = false;
        }
        AltToken close = expect(AltToken.Kind.RBRACE);
        // Placeholder name; parseLet patches it with the binding's name.
        return new IrSort.Trait("_pending", methods, typeTok.spanTo(close));
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

        if (peek().kind() == AltToken.Kind.COLON) {
            consume();
            IrExpr pred = parseExpr();
            IrExpr cooked = applyPredicateSugar(pred);
            return new IrSort.Refined(baseTok.text(), cooked, baseTok.origin());
        }

        if (peek().kind() == AltToken.Kind.LPAREN) {
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
            return structural;
        }

        // Bare name — `Int`, `Bool`, etc.
        return new IrSort.Named(baseTok.text(), baseTok.origin());
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
     * component sorts. Restores the flag afterwards (patterns don't nest a
     * tuple-pattern inside a component in Slice 1).
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
                AltToken binder = expect(AltToken.Kind.IDENT);
                members.put(key, IrSort.named("_"));  // sort resolved from scrutinee
                if (binder.text().equals("_")) {
                    discards.add(key);                // verdict C: explicit discard
                } else {
                    renames.put(key, binder.text());
                }
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
     * Parses {@code (P1, P2, ...):R} after the {@code Method} keyword.
     * Only positional/anonymous param sorts are accepted in slice 7. Named
     * params ({@code (x:Int)}) require {@link IrSort.Method} to carry param
     * names — tracked as deferred work.
     *
     * <p>The returned {@link IrSort.Method} uses the {@code Method} token's
     * origin; the caller may rebuild with a wider span if it has the closing
     * {@code ]} on hand.
     */
    private IrSort.Method parseFunctionSortBody(AltToken funcTok) throws ParseException {
        expect(AltToken.Kind.LPAREN);
        List<IrSort> paramSorts = new ArrayList<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RPAREN) {
            if (!first) expect(AltToken.Kind.COMMA);
            if (peek().kind() == AltToken.Kind.IDENT
                    && peek(1).kind() == AltToken.Kind.COLON) {
                throw new ParseException(
                        "Named-parameter method sorts (e.g., [Method(x:Int):Ret]) "
                                + "are not yet supported — IrSort.Method needs param-name "
                                + "support. Use positional form for now.",
                        peek().origin());
            }
            paramSorts.add(parseSort());
            first = false;
        }
        expect(AltToken.Kind.RPAREN);
        expect(AltToken.Kind.COLON);
        IrSort returnSort = parseSort();
        return new IrSort.Method(paramSorts, returnSort, funcTok.origin());
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

    private Map<String, IrSort> parseStructFields(String typeName, Origin typeOrigin,
            java.util.Set<String> literalFieldsOut, Map<String, String> renamesOut)
            throws ParseException {
        Map<String, IrSort> members = new LinkedHashMap<>();
        boolean first = true;
        int clauseIndex = -1;
        while (peek().kind() != AltToken.Kind.RPAREN) {
            if (!first) expect(AltToken.Kind.COMMA);
            clauseIndex++;
            AltToken t = peek();
            boolean literalClause = t.kind() == AltToken.Kind.INTEGER
                    || t.kind() == AltToken.Kind.DECIMAL
                    || t.kind() == AltToken.Kind.CHAR
                    || (t.kind() == AltToken.Kind.IDENT
                            && (t.text().equals("true") || t.text().equals("false")));
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
        IrSort.Structural decl = patternShapeFor(typeName);
        if (parsingTuplePattern && decl != null && members.size() < decl.members().size()) {
            throw new ParseException(
                    "Pattern [" + typeName + "(...)] lists " + members.size() + " of "
                            + decl.members().size() + " fields — a positional pattern must account "
                            + "for every field. Use '_' to discard the unwanted ones "
                            + "(e.g. [" + typeName + "(a, _, _)]) or focus by name with a refinement "
                            + "[" + typeName + ":@.field …].",
                    typeOrigin);
        }
        return members;
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
            // Operator-overload routing: if the left operand's inferred sort
            // has a Type.{op} method declared, emit a Call to it instead of a
            // primitive BinOp. The primitive path stays unchanged for Int/Bool
            // operands; refinement predicates are unaffected because SelfRef
            // and field-accesses-through-SelfRef yield the "_" placeholder
            // sort which the routing skips.
            String overload = tryOperatorOverloadRoute(left, t.text());
            if (overload != null) {
                left = new IrExpr.Call(
                        overload, List.of(left, right), t.origin());
            } else {
                left = new IrExpr.BinOp(opKind(t.text()), left, right, t.origin());
            }
        }
        return left;
    }

    /**
     * Operators that can be overloaded — via a bare generic
     * {@code function <op>(l, r)} or the legacy {@code method Type.<op>(...)}.
     * Arithmetic and comparison. Logical {@code &} and {@code |} are excluded
     * — they always go through BinOp regardless of any declaration named
     * {@code &}.
     */
    private static final Set<String> OVERLOADABLE_OPS = Set.of(
            "+", "-", "*", "/", "%", "^",
            "<", "<=", ">", ">=", "==", "!=");

    /**
     * Returns the dispatch name to route {@code left <op> right} to, or
     * {@code null} to keep the primitive {@code BinOp} path. Resolution:
     * <ol>
     *   <li>the bare operator generic {@code "<op>"} (a {@code function +(l,r)}
     *       declaration) — preferred;</li>
     *   <li>the legacy {@code "<TypeName>.<op>"} method form — fallback.</li>
     * </ol>
     * Skipped (BinOp stays) when the op isn't overloadable, or {@code left}'s
     * base sort is a primitive ({@code Int}, {@code Bool}, {@code Function})
     * or a sentinel ({@code _}, {@code _record}), or neither name is
     * registered. (Routing on a primitive <em>left</em> with a non-primitive
     * right — {@code Int * Point} — is a separate, later slice.)
     */
    private String tryOperatorOverloadRoute(IrExpr left, String opText) {
        if (!OVERLOADABLE_OPS.contains(opText)) return null;
        IrSort leftSort = inferMaximalSort(left);
        String typeName = baseSortName(leftSort);
        if (typeName == null
                || typeName.equals("_")
                || typeName.equals("_record")
                || typeName.equals(TUPLE_SENTINEL)
                || typeName.equals("Int")
                || typeName.equals("Bool")
                || typeName.equals("Decimal")
                || typeName.equals("Char")
                || typeName.equals("Method")
                || typeName.equals("Dispatch")) {
            return null;
        }
        // Bare-name operator generic (`function +(l, r)`) wins; the legacy
        // `Type.op` method form stays as a fallback so existing
        // `method Type.+` declarations keep routing.
        if (declaredFunctionReturns.containsKey(opText)) return opText;
        String methodName = typeName + "." + opText;
        return declaredFunctionReturns.containsKey(methodName) ? methodName : null;
    }

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

                // Instance-method call routing: `receiver.method(args)`
                // where receiver's inferred sort has a base name matching a
                // declared method `Type.method`. Rewrites to
                // `Call("Type.method", [receiver, ...args])`. The receiver
                // itself gets the top-level-let rewrite first, so a let-bound
                // value is invoked as a 0-arg call before being passed as
                // `self`.
                if (expr instanceof IrExpr.FieldAccess fa) {
                    IrExpr receiver = rewriteTopLevelLetAccess(fa.base());
                    String methodName = methodNameForReceiver(receiver, fa.fieldName());
                    if (methodName != null) {
                        List<IrExpr> rewrittenArgs = new ArrayList<>(args.size() + 1);
                        rewrittenArgs.add(receiver);
                        rewrittenArgs.addAll(args);
                        expr = new IrExpr.Call(methodName, rewrittenArgs, callOrigin);
                        continue;
                    }
                }

                // Otherwise it's a Call on a dotted name, or an Apply on an
                // arbitrary expression.
                String dotted = extractDottedName(expr);
                if (dotted != null) {
                    expr = new IrExpr.Call(dotted, args, callOrigin);
                } else {
                    expr = new IrExpr.Apply(expr, args, callOrigin);
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
    private String methodNameForReceiver(IrExpr receiver, String field) {
        IrSort receiverSort = inferMaximalSort(receiver);
        String typeName = baseSortName(receiverSort);
        if (typeName == null || typeName.equals("_") || typeName.equals("_record")
                || typeName.equals(TUPLE_SENTINEL)) {
            return null;
        }
        String methodName = typeName + "." + field;
        return declaredFunctionReturns.containsKey(methodName) ? methodName : null;
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
        java.util.Set<String> constrainedOnly =
                literalConstrainedFields.getOrDefault(sp, java.util.Set.of());
        Map<String, String> renames = destructureRenames.getOrDefault(sp, Map.of());
        for (Map.Entry<String, IrSort> e : sp.members().entrySet()) {
            if (constrainedOnly.contains(e.getKey())) {
                continue;  // literal field: constrains the match, binds nothing
            }
            String binder = renames.getOrDefault(e.getKey(), e.getKey());
            IrExpr accessor = desugarStructuralDestructure(
                    new IrExpr.Call(synthetic, List.of(), start.origin()),
                    List.of(new IrExpr.MatchBranch(sp, new IrExpr.Var(binder, start.origin()))),
                    start.origin());
            declaredTopLevelLets.put(binder, e.getValue());
            pendingTopLevelDecls.add(new IrStmt.FunctionDecl(
                    binder, List.of(), e.getValue(), accessor, start.origin(), true));
        }
        return new IrStmt.FunctionDecl(synthetic, List.of(), valueSort, value, start.origin(), true);
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
                    && !intToDecimal
                    && !declaredBase.equals(inferredBase)) {
                throw new ParseException(
                        "let '" + name + "' declared as " + declaredSort
                                + " but value's inferred sort is " + inferred
                                + " (base sort mismatch)",
                        start.origin());
            }
        }
        IrSort binding = declaredSort != null
                && ("_record".equals(baseSortName(inferred)) || intToDecimal)
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
        // construction gate three-way, like a constructor argument.
        return new IrExpr.LetIn(name, binding, value, body, start.origin(), declaredSort);
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
                    checkTupleArity(scrutinee, pattern);
                    expect(AltToken.Kind.ARROW);
                    IrExpr result = parseExpr();
                    branches.add(new IrExpr.MatchBranch(pattern, result));
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
            if (b.pattern() instanceof IrSort.Structural sp) {
                // Wrap in reverse so the first field becomes the outermost let.
                // Literal-constrained fields ([Ternion(z, 0, w)]) constrain the
                // match but bind nothing — skipped here so the field name isn't
                // silently shadowed.
                java.util.Set<String> constrainedOnly =
                        literalConstrainedFields.getOrDefault(sp, java.util.Set.of());
                Map<String, String> renames =
                        destructureRenames.getOrDefault(sp, Map.of());
                List<Map.Entry<String, IrSort>> entries =
                        new ArrayList<>(sp.members().entrySet());
                for (int i = entries.size() - 1; i >= 0; i--) {
                    Map.Entry<String, IrSort> e = entries.get(i);
                    if (constrainedOnly.contains(e.getKey())) {
                        continue;
                    }
                    result = new IrExpr.LetIn(
                            renames.getOrDefault(e.getKey(), e.getKey()),
                            e.getValue(),
                            new IrExpr.FieldAccess(scrutineeRef, e.getKey(), Origin.NONE),
                            result,
                            Origin.NONE);
                }
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

    /** True when {@code expr}'s maximal-inferred sort has base name {@code Decimal}. */
    private boolean isDecimalOperand(IrExpr expr) {
        return "Decimal".equals(baseSortName(inferMaximalSort(expr)));
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
        args.add(parseExpr());
        while (peek().kind() == AltToken.Kind.COMMA) {
            consume();
            args.add(parseExpr());
        }
        return args;
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

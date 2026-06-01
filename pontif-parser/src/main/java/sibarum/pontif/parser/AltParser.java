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

    /** Keywords are recognized by text at parse time, not by token kind. */
    private static final Set<String> KEYWORDS = Set.of(
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
            case "==", "!=" -> 3;
            case "<", "<=", ">", ">=" -> 4;
            case "+", "-" -> 5;
            case "*", "/" -> 6;
            default -> -1;
        };
    }

    private static IrExpr.Op opKind(String op) {
        return switch (op) {
            case "+" -> IrExpr.Op.ADD;
            case "-" -> IrExpr.Op.SUB;
            case "*" -> IrExpr.Op.MUL;
            case "<" -> IrExpr.Op.LT;
            case "<=" -> IrExpr.Op.LE;
            case ">" -> IrExpr.Op.GT;
            case ">=" -> IrExpr.Op.GE;
            case "==" -> IrExpr.Op.EQ;
            case "!=" -> IrExpr.Op.NE;
            case "&" -> IrExpr.Op.AND;
            case "|" -> IrExpr.Op.OR;
            case "/" -> throw new IllegalArgumentException("'/' has no IrExpr.Op yet");
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
                                + "(overloadable: + - * < <= > >= == !=)",
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
        List<String> names = parseDotBraceSymbolList();
        AltToken last = tokens.get(pos - 1);
        return new IrStmt.Requires(target, names, start.spanTo(last));
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
        List<String> names = parseDotBraceSymbolList();
        AltToken last = tokens.get(pos - 1);
        return new IrStmt.Exports(names, self, start.spanTo(last));
    }

    /** Parses the {@code .{name, name, ...}} list tail into its symbol names. */
    private List<String> parseDotBraceSymbolList() throws ParseException {
        expect(AltToken.Kind.DOT);
        expect(AltToken.Kind.LBRACE);
        List<String> names = new ArrayList<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RBRACE) {
            if (!first) {
                expect(AltToken.Kind.COMMA);
            }
            names.add(expect(AltToken.Kind.IDENT).text());
            first = false;
        }
        expect(AltToken.Kind.RBRACE);
        return names;
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
            try {
                IrExpr body = parseExpr();
                declaredFunctionReturns.put(name, returnSort);
                if (params.isEmpty()) declaredZeroArgFunctions.add(name);
                return new IrStmt.FunctionDecl(name, params, returnSort, body, start.origin());
            } finally {
                currentScope.clear();
                currentScope.putAll(savedScope);
            }
        }
        // No body — try synthesis.
        IrExpr derived = tryDeriveBodyFromReturnSort(returnSort);
        if (derived != null) {
            declaredFunctionReturns.put(name, returnSort);
            if (params.isEmpty()) declaredZeroArgFunctions.add(name);
            return new IrStmt.FunctionDecl(name, params, returnSort, derived, start.origin());
        }
        throw specOnlyWithoutSynthesis("function", name, returnSort, start.origin());
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
        expect(AltToken.Kind.COLON);
        IrSort returnSort = parseSort();

        // Reject a user-declared `self` — would collide with the injected one.
        for (IrParam p : params) {
            if (p.name().equals("self")) {
                throw new ParseException(
                        "Method param cannot be named 'self' — that name is reserved for "
                                + "the implicit receiver injected by method desugar",
                        start.origin());
            }
        }

        IrSort receiverSort = new IrSort.Named(receiverTypeName, start.origin());
        List<IrParam> desugaredParams = new ArrayList<>(params.size() + 1);
        desugaredParams.add(new IrParam("self", receiverSort));
        desugaredParams.addAll(params);

        if (peek().kind() != AltToken.Kind.ARROW) {
            // Spec-only — try synthesis (same path as functions).
            IrExpr derived = tryDeriveBodyFromReturnSort(returnSort);
            if (derived != null) {
                declaredFunctionReturns.put(name, returnSort);
                return new IrStmt.FunctionDecl(
                        name, desugaredParams, returnSort, derived, start.origin());
            }
            throw specOnlyWithoutSynthesis("method", name, returnSort, start.origin());
        }
        consume();  // ARROW

        // Push desugared params (including self) into scope for body parsing.
        Map<String, IrSort> savedScope = new LinkedHashMap<>(currentScope);
        currentScope.clear();
        for (IrParam p : desugaredParams) currentScope.put(p.name(), p.sort());
        try {
            IrExpr body = parseExpr();
            declaredFunctionReturns.put(name, returnSort);
            return new IrStmt.FunctionDecl(
                    name, desugaredParams, returnSort, body, start.origin());
        } finally {
            currentScope.clear();
            currentScope.putAll(savedScope);
        }
    }

    private List<IrParam> parseParamList(AltToken.Kind terminator) throws ParseException {
        List<IrParam> params = new ArrayList<>();
        boolean first = true;
        while (peek().kind() != terminator) {
            if (!first) expect(AltToken.Kind.COMMA);
            AltToken name = expect(AltToken.Kind.IDENT);
            expect(AltToken.Kind.COLON);
            IrSort sort = parseSort();
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

    /** True if {@code expr} contains an {@link IrExpr.SelfRef} anywhere. */
    private static boolean containsSelfRef(IrExpr expr) {
        return switch (expr) {
            case IrExpr.SelfRef s -> true;
            case IrExpr.Lit l -> false;
            case IrExpr.Bool b -> false;
            case IrExpr.Var v -> false;
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
        String name = parseDottedName();
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
            for (Map.Entry<String, IrSort.Function> e : named.methods().entrySet()) {
                declaredFunctionReturns.put(
                        name + "." + e.getKey(), e.getValue().returnSort());
            }
            return new IrStmt.TypeAlias(name, named, start.origin());
        }
        if (value == null) {
            // Spec-only — synthesis from maximally-specific sort is a separate
            // TODO item (see docs/TODO.md). Stay NoOp so other decls process.
            return new IrStmt.NoOp("let " + name + ":" + declaredSort, start.origin());
        }
        IrSort inferredSort = inferMaximalSort(value);
        if (declaredSort != null) {
            String declaredBase = baseSortName(declaredSort);
            String inferredBase = baseSortName(inferredSort);
            if (declaredBase != null && inferredBase != null
                    && !declaredBase.equals(inferredBase)) {
                throw new ParseException(
                        "let '" + name + "' declared as " + declaredSort
                                + " but value's inferred sort is " + inferredSort
                                + " (base sort mismatch)",
                        start.origin());
            }
        }
        declaredTopLevelLets.put(name, inferredSort);
        return new IrStmt.FunctionDecl(
                name, List.of(), inferredSort, value, start.origin());
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
                String matchedName = findStructByFieldSet(r.members().keySet());
                yield new IrSort.Structural(
                        matchedName != null ? matchedName : "_record",
                        memberSorts,
                        r.origin());
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
                String baseName = switch (op.op()) {
                    case ADD, SUB, MUL -> "Int";
                    case LT, LE, GT, GE, EQ, NE, AND, OR -> "Bool";
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
            case IrExpr.Lambda lam -> new IrSort.Function(
                    lam.params().stream().map(IrParam::sort).toList(),
                    lam.returnSort(),
                    lam.origin());
            case IrExpr.Match m -> IrSort.named("_");
            case IrExpr.LetIn l -> inferMaximalSort(l.body());
            case IrExpr.SelfRef s -> IrSort.named("_");
        };
    }

    /**
     * Looks up a declared struct by exact field-set match. Returns the struct
     * name if exactly one declared struct has that field set, else null
     * (zero or multiple matches — in the multi-match case the inferred sort
     * stays anonymous and the user can disambiguate with an explicit
     * {@code let X:Foo = ...}).
     */
    private String findStructByFieldSet(Set<String> fieldSet) {
        String found = null;
        for (Map.Entry<String, IrSort.Structural> e : declaredStructs.entrySet()) {
            if (e.getValue().members().keySet().equals(fieldSet)) {
                if (found != null) return null;  // ambiguous
                found = e.getKey();
            }
        }
        return found;
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
        expect(AltToken.Kind.COLON);
        IrSort returnSort = parseSort();
        expect(AltToken.Kind.ARROW);

        List<IrParam> allParams = new ArrayList<>(userParams.size() + 1);
        allParams.add(new IrParam("self", selfSort));
        allParams.addAll(userParams);

        Map<String, IrSort> savedScope = new LinkedHashMap<>(currentScope);
        currentScope.clear();
        for (IrParam p : allParams) currentScope.put(p.name(), p.sort());
        try {
            IrExpr body = parseExpr();
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
        IrSort.Structural structSort = new IrSort.Structural(nameTok.text(), members, origin);
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
            return parseBracketSort();
        }
        if (t.kind() == AltToken.Kind.IDENT) {
            // `Type{...}` — trait literal at sort level. The trait's name is
            // empty here (it's anonymous); parseLet patches it with the
            // let-binding's name before producing the TypeAlias.
            if (t.text().equals("Type") && peek(1).kind() == AltToken.Kind.LBRACE) {
                return parseTraitTypeLiteral();
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
     * Parses {@code Type{methodName:FunctionSort, ...}} — the trait literal.
     * Each entry must be {@code methodName:[Function(...):Ret]} (function
     * sort). The returned {@link IrSort.Trait} has an empty placeholder
     * name; {@link #parseLet} patches it with the binding name from the
     * enclosing {@code let X:Type{...}} declaration.
     */
    private IrSort.Trait parseTraitTypeLiteral() throws ParseException {
        AltToken typeTok = expect(AltToken.Kind.IDENT);  // "Type"
        AltToken open = expect(AltToken.Kind.LBRACE);
        Map<String, IrSort.Function> methods = new LinkedHashMap<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RBRACE) {
            if (!first) expect(AltToken.Kind.COMMA);
            AltToken methodName = expect(AltToken.Kind.IDENT);
            expect(AltToken.Kind.COLON);
            IrSort methodSort = parseSort();
            if (!(methodSort instanceof IrSort.Function fn)) {
                throw new ParseException(
                        "Trait method '" + methodName.text() + "' must have a "
                                + "function sort like [Function(args):Ret]; got " + methodSort,
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
            if (baseTok.text().equals("Function")) {
                return parseFunctionSortBody(baseTok);
            }
            consume();  // LPAREN
            Map<String, IrSort> members = parseStructFields(baseTok.text(), baseTok.origin());
            expect(AltToken.Kind.RPAREN);
            return new IrSort.Structural(baseTok.text(), members, baseTok.origin());
        }

        // Bare name — `Int`, `Bool`, etc.
        return new IrSort.Named(baseTok.text(), baseTok.origin());
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
     * Parses {@code (P1, P2, ...):R} after the {@code Function} keyword.
     * Only positional/anonymous param sorts are accepted in slice 7. Named
     * params ({@code (x:Int)}) require {@link IrSort.Function} to carry param
     * names — tracked as deferred work.
     *
     * <p>The returned {@link IrSort.Function} uses the {@code Function} token's
     * origin; the caller may rebuild with a wider span if it has the closing
     * {@code ]} on hand.
     */
    private IrSort.Function parseFunctionSortBody(AltToken funcTok) throws ParseException {
        expect(AltToken.Kind.LPAREN);
        List<IrSort> paramSorts = new ArrayList<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RPAREN) {
            if (!first) expect(AltToken.Kind.COMMA);
            if (peek().kind() == AltToken.Kind.IDENT
                    && peek(1).kind() == AltToken.Kind.COLON) {
                throw new ParseException(
                        "Named-parameter function sorts (e.g., [Function(x:Int):Ret]) "
                                + "are not yet supported — IrSort.Function needs param-name "
                                + "support. Use positional form for now.",
                        peek().origin());
            }
            paramSorts.add(parseSort());
            first = false;
        }
        expect(AltToken.Kind.RPAREN);
        expect(AltToken.Kind.COLON);
        IrSort returnSort = parseSort();
        return new IrSort.Function(paramSorts, returnSort, funcTok.origin());
    }

    /**
     * Parses the comma-separated clause list inside {@code [Name(...)]}:
     *   - {@code field:Sort} — explicit per-field sort
     *   - {@code field}      — bare ident; sort looked up from {@link #declaredStructs}
     * Mixed forms are allowed.
     */
    private Map<String, IrSort> parseStructFields(String typeName, Origin typeOrigin)
            throws ParseException {
        Map<String, IrSort> members = new LinkedHashMap<>();
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RPAREN) {
            if (!first) expect(AltToken.Kind.COMMA);
            AltToken fieldName = expect(AltToken.Kind.IDENT);
            IrSort fieldSort;
            if (peek().kind() == AltToken.Kind.COLON) {
                consume();  // COLON
                fieldSort = parseSort();
            } else {
                // Bare ident — look up declared field sort.
                IrSort.Structural decl = declaredStructs.get(typeName);
                if (decl == null) {
                    throw new ParseException(
                            "Bare field name '" + fieldName.text() + "' inside [" + typeName
                                    + "(...)] requires '" + typeName + "' to be declared before this point "
                                    + "(struct decl not found)",
                            fieldName.origin());
                }
                IrSort declSort = decl.members().get(fieldName.text());
                if (declSort == null) {
                    throw new ParseException(
                            "Field '" + fieldName.text() + "' is not a member of struct '"
                                    + typeName + "'",
                            fieldName.origin());
                }
                fieldSort = declSort;
            }
            members.put(fieldName.text(), fieldSort);
            first = false;
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
            "+", "-", "*",
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
                || typeName.equals("Int")
                || typeName.equals("Bool")
                || typeName.equals("Function")) {
            return null;
        }
        // Bare-name operator generic (`function +(l, r)`) wins; the legacy
        // `Type.op` method form stays as a fallback so existing
        // `method Type.+` declarations keep routing.
        if (declaredFunctionReturns.containsKey(opText)) return opText;
        String methodName = typeName + "." + opText;
        return declaredFunctionReturns.containsKey(methodName) ? methodName : null;
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
                expr = new IrExpr.FieldAccess(expr, name.text(), t.origin());
            } else if (t.kind() == AltToken.Kind.LPAREN) {
                AltToken open = consume();
                // Struct-literal shortcut: a bare ident matching a declared
                // struct constructs a record (positional), not a Call.
                if (expr instanceof IrExpr.Var v && declaredStructs.containsKey(v.name())) {
                    expr = parsePositionalStructLiteral(
                            declaredStructs.get(v.name()), v.name(), open);
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
                    && expr instanceof IrExpr.Var v
                    && declaredStructs.containsKey(v.name())) {
                // By-name struct literal `Foo{x=a, y=b}`. The brace form is
                // reserved for declared-struct construction in this slice;
                // anonymous and dotted-name forms are deferred.
                AltToken open = consume();
                expr = parseByNameStructLiteral(
                        declaredStructs.get(v.name()), v.name(), open);
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
        if (typeName == null || typeName.equals("_") || typeName.equals("_record")) {
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
    private IrExpr parseLetExpr() throws ParseException {
        AltToken start = expectKeyword("let");
        AltToken nameTok = expect(AltToken.Kind.IDENT);
        String name = nameTok.text();
        if (KEYWORDS.contains(name)) {
            throw new ParseException(
                    "Cannot bind keyword '" + name + "' as a let-name",
                    nameTok.origin());
        }
        IrSort declaredSort = null;
        if (peek().kind() == AltToken.Kind.COLON) {
            consume();
            declaredSort = parseSort();
        }
        expect(AltToken.Kind.EQUALS);
        IrExpr value = parseExpr();
        IrSort inferred = inferMaximalSort(value);
        if (declaredSort != null) {
            String declaredBase = baseSortName(declaredSort);
            String inferredBase = baseSortName(inferred);
            if (declaredBase != null && inferredBase != null
                    && !declaredBase.equals(inferredBase)) {
                throw new ParseException(
                        "let '" + name + "' declared as " + declaredSort
                                + " but value's inferred sort is " + inferred
                                + " (base sort mismatch)",
                        start.origin());
            }
        }
        IrSort prevBinding = currentScope.get(name);
        boolean hadPrev = currentScope.containsKey(name);
        currentScope.put(name, inferred);
        IrExpr body;
        try {
            body = parseExpr();
        } finally {
            if (hadPrev) currentScope.put(name, prevBinding);
            else currentScope.remove(name);
        }
        return new IrExpr.LetIn(name, inferred, value, body, start.origin());
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
                    IrSort pattern = parseSort();
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
            Origin defaultArmOrigin) throws ParseException {
        IrSort scrutineeIrSort = inferScrutineeSort(scrutinee);
        if (scrutineeIrSort == null) {
            throw new ParseException(
                    "Cannot infer scrutinee's sort for '_' default arm desugar; "
                            + "give the scrutinee a known sort or write the explicit complement predicate",
                    defaultArmOrigin);
        }

        // Union the explicit arms' predicates as SymExpr.
        SymExpr unionPredicate = null;
        for (int i = 0; i < branches.size(); i++) {
            if (i == defaultArmIndex) continue;
            IrSort armPattern = branches.get(i).pattern();
            if (!(armPattern instanceof IrSort.Refined refined)) {
                throw new ParseException(
                        "'_' default arm currently requires all other arms to use refined sorts "
                                + "(e.g., [@<0]); got non-refined arm pattern: " + armPattern,
                        defaultArmOrigin);
            }
            SymExpr armPred;
            try {
                armPred = IrCompiler.compileSymExpr(refined.predicate());
            } catch (CompileException ce) {
                throw new ParseException(
                        "Cannot compile arm predicate for '_' desugar: " + ce.getMessage(),
                        defaultArmOrigin);
            }
            unionPredicate = (unionPredicate == null) ? armPred : SymExpr.or(unionPredicate, armPred);
        }
        // No explicit arms — complement of false = entire domain.
        if (unionPredicate == null) unionPredicate = SymExpr.bool(false);

        Sort scrutineeSort;
        try {
            scrutineeSort = IrCompiler.compileSort(scrutineeIrSort);
        } catch (CompileException ce) {
            throw new ParseException(
                    "Cannot compile scrutinee's sort for '_' desugar: " + ce.getMessage(),
                    defaultArmOrigin);
        }

        ComplementResult complement = PredicateArithmetic.complement(unionPredicate, scrutineeSort);
        if (complement instanceof ComplementResult.Unknown unknown) {
            throw new ParseException(
                    "Cannot infer '_' default arm's predicate (" + unknown.reason()
                            + "); write the predicate explicitly",
                    defaultArmOrigin);
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
                List<Map.Entry<String, IrSort>> entries =
                        new ArrayList<>(sp.members().entrySet());
                for (int i = entries.size() - 1; i >= 0; i--) {
                    Map.Entry<String, IrSort> e = entries.get(i);
                    result = new IrExpr.LetIn(
                            e.getKey(),
                            e.getValue(),
                            new IrExpr.FieldAccess(scrutineeRef, e.getKey(), Origin.NONE),
                            result,
                            Origin.NONE);
                }
            }
            wrapped.add(new IrExpr.MatchBranch(b.pattern(), result));
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
            case IrSort.Function f -> null;
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
        args.add(parseExpr());
        while (peek().kind() == AltToken.Kind.COMMA) {
            consume();
            args.add(parseExpr());
        }
        return args;
    }

    private IrExpr parsePrimary() throws ParseException {
        AltToken t = peek();
        return switch (t.kind()) {
            case INTEGER -> {
                consume();
                yield new IrExpr.Lit(Long.parseLong(t.text()), t.origin());
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
                consume();
                IrExpr inner = parseExpr();
                expect(AltToken.Kind.RPAREN);
                yield inner;
            }
            case LBRACE -> {
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

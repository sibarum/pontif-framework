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
            "match",
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
    /** Counter for synthesizing fresh names (currently unused; reserved for slice 5+). */
    @SuppressWarnings("unused")
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
                "function", "method", "struct", "let").contains(t.text());
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
            default -> throw new ParseException(
                    "Unknown top-level keyword '" + head.text() + "'",
                    head.origin());
        };
    }

    // --- Declarations: requires / exports ---
    // Form: `requires X.{name, name, ...}` and `exports @.{name, name, ...}`.
    // No semantics yet — both parse as NoOp until the module system lands.

    private IrStmt parseRequires() throws ParseException {
        AltToken start = expectKeyword("requires");
        StringBuilder label = new StringBuilder("requires ");
        label.append(parseDottedName());
        label.append(parseDotBraceSymbolList());
        AltToken last = tokens.get(pos - 1);
        return new IrStmt.NoOp(label.toString(), start.spanTo(last));
    }

    private IrStmt parseExports() throws ParseException {
        AltToken start = expectKeyword("exports");
        StringBuilder label = new StringBuilder("exports ");
        if (peek().kind() == AltToken.Kind.AT) {
            consume();
            label.append("@");
        } else {
            label.append(parseDottedName());
        }
        label.append(parseDotBraceSymbolList());
        AltToken last = tokens.get(pos - 1);
        return new IrStmt.NoOp(label.toString(), start.spanTo(last));
    }

    /**
     * Parses the {@code .{name, name, ...}} dictionary-decomposition tail.
     * Returns a stringified rendering for use in the parent's NoOp label.
     */
    private String parseDotBraceSymbolList() throws ParseException {
        expect(AltToken.Kind.DOT);
        expect(AltToken.Kind.LBRACE);
        StringBuilder sb = new StringBuilder(".{");
        boolean first = true;
        while (peek().kind() != AltToken.Kind.RBRACE) {
            if (!first) {
                expect(AltToken.Kind.COMMA);
                sb.append(", ");
            }
            AltToken sym = expect(AltToken.Kind.IDENT);
            sb.append(sym.text());
            first = false;
        }
        expect(AltToken.Kind.RBRACE);
        sb.append("}");
        return sb.toString();
    }

    // --- Function declarations ---
    // Form: `function NAME(PARAMS):RETURN_SORT [-> BODY]`
    //   - NAME may be dotted (e.g., `Point.manhattan`) — the whole dotted form
    //     becomes the dispatch identifier.
    //   - PARAMS is a comma-separated list of `name:Sort`.
    //   - If `-> BODY` is present, BODY is used directly.
    //   - If absent, body synthesis is attempted from the return sort: a return
    //     of shape `[Sort:@==EXPR]` (which is what the implicit @==EXPR sugar
    //     produces) yields BODY = EXPR. Sorts where the projected RHS contains
    //     SelfRef would be self-referential (recursive equation, not a
    //     definition) and are rejected — those stay NoOp.

    private IrStmt parseFunction() throws ParseException {
        AltToken start = expectKeyword("function");
        String name = parseDottedName();
        expect(AltToken.Kind.LPAREN);
        List<IrParam> params = parseParamList(AltToken.Kind.RPAREN);
        expect(AltToken.Kind.RPAREN);
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
            return new IrStmt.FunctionDecl(name, params, returnSort, derived, start.origin());
        }
        return new IrStmt.NoOp(
                "spec-only function " + name + "(" + paramSig(params) + "):" + returnSort,
                start.origin());
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
        String name = parseDottedName();
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
            return new IrStmt.NoOp(
                    "spec-only method " + name + "(" + paramSig(params) + "):" + returnSort,
                    start.origin());
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
                yield declaredReturn != null ? declaredReturn : IrSort.named("_");
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
            // Bare-ident sugar: `Int` ≡ `[Int]`.
            AltToken nameTok = consume();
            return new IrSort.Named(nameTok.text(), nameTok.origin());
        }
        throw new ParseException(
                "Expected a sort (bare ident or '[...]'); got " + t.kind() + " '" + t.text() + "'",
                t.origin());
    }

    private IrSort parseBracketSort() throws ParseException {
        AltToken open = expect(AltToken.Kind.LBRACKET);
        AltToken first = peek();

        // Contextual form: `[pred]` — no base, take from contextualBaseStack
        // (pushed by parseMatch when the scrutinee has an inferable sort).
        // Slice 4 scope: works in match arms; other contexts (struct refinement,
        // function sorts, union/intersection) come in later slices.
        if (first.kind() != AltToken.Kind.IDENT) {
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
        AltToken baseTok = consume();

        if (peek().kind() == AltToken.Kind.RBRACKET) {
            AltToken close = expect(AltToken.Kind.RBRACKET);
            return new IrSort.Named(baseTok.text(), open.spanTo(close));
        }

        if (peek().kind() == AltToken.Kind.COLON) {
            consume();  // COLON
            IrExpr pred = parseExpr();
            AltToken close = expect(AltToken.Kind.RBRACKET);
            IrExpr cooked = applyPredicateSugar(pred);
            return new IrSort.Refined(baseTok.text(), cooked, open.spanTo(close));
        }

        if (peek().kind() == AltToken.Kind.LPAREN) {
            // [Function(P1, P2, ...):R] — function sort. `Function` is a reserved
            // builtin sort name; users cannot declare structs named Function.
            if (baseTok.text().equals("Function")) {
                IrSort.Function fnSort = parseFunctionSortBody(baseTok);
                AltToken close = expect(AltToken.Kind.RBRACKET);
                return new IrSort.Function(
                        fnSort.paramSorts(), fnSort.returnSort(), open.spanTo(close));
            }
            // [Name(field:Sort, ...)] structural form. Each clause is either:
            //   - `field:Sort` — explicit field sort
            //   - `field`      — bare name, sort looked up from declaredStructs
            consume();  // LPAREN
            Map<String, IrSort> members = parseStructFields(baseTok.text(), baseTok.origin());
            expect(AltToken.Kind.RPAREN);
            AltToken close = expect(AltToken.Kind.RBRACKET);
            return new IrSort.Structural(baseTok.text(), members, open.spanTo(close));
        }

        throw new ParseException(
                "Expected ':' (refinement predicate), '(' (struct fields), or ']' (bare sort) "
                        + "after '" + baseTok.text() + "'; got " + peek().kind()
                        + " '" + peek().text() + "'",
                peek().origin());
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
            left = new IrExpr.BinOp(opKind(t.text()), left, right, t.origin());
        }
        return left;
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
                // Otherwise it's a Call on a dotted name, or an Apply on an
                // arbitrary expression.
                List<IrExpr> args = parseArgList();
                AltToken close = expect(AltToken.Kind.RPAREN);
                Origin callOrigin = open.spanTo(close);
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
        return expr;
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
        return new IrExpr.Record(ordered, openParen.spanTo(close));
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
        return new IrExpr.Record(ordered, openBrace.spanTo(close));
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
        return new IrExpr.LetIn(outerLetName, IrSort.named("_"), scrutinee, match, matchOrigin);
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
                if (KEYWORDS.contains(t.text())) {
                    throw new ParseException(
                            "Unexpected keyword '" + t.text() + "' in expression position",
                            t.origin());
                }
                // Just the first ident — parsePrimaryWithPostfix builds the
                // dotted chain via FieldAccess. Whether the whole chain ends
                // as a Call (qualified name) or stays as FieldAccess (field
                // read) is decided when we see (or don't see) a trailing `(`.
                AltToken nameTok = consume();
                String name = nameTok.text();
                // Top-level let names lower to 0-arg Calls so dispatch can
                // find the binding (and so `origin.x` reads as expected,
                // becoming Call("origin", []).x). Skip when shadowed by a
                // param / in-scope binding, or when the next token is `(`
                // (the user is explicitly calling — let the postfix path
                // build the Call directly with their args).
                if (peek().kind() != AltToken.Kind.LPAREN
                        && !currentScope.containsKey(name)
                        && declaredTopLevelLets.containsKey(name)) {
                    yield new IrExpr.Call(name, List.of(), nameTok.origin());
                }
                yield new IrExpr.Var(name, nameTok.origin());
            }
            case LPAREN -> {
                consume();
                IrExpr inner = parseExpr();
                expect(AltToken.Kind.RPAREN);
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

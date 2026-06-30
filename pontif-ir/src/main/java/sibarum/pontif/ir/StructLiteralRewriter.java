package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-link pass that turns a constructor-shaped {@code Call} into a struct
 * {@link IrExpr.Record} when the called name is a declared struct type.
 *
 * <p><b>Why this exists (parser-blindness):</b> the alt parser recognizes a
 * positional struct literal {@code Point(x, y)} only for structs declared in the
 * <em>same file</em> (its local {@code declaredStructs} map). A struct
 * <em>imported</em> from another module is invisible at parse time, so
 * {@code Point(x, y)} lowers to {@code Call("Point", [x, y])}. After the linker
 * FQN-rewrites names, that becomes {@code Call("lib/Point", …)} — a call to a
 * function that doesn't exist, which would fail as "unknown function".
 *
 * <p>This pass runs on the <em>combined</em> module (so it sees every module's
 * FQN'd struct definitions via {@link TypeRegistry#collect}) and rewrites any
 * {@code Call(name, args)} whose {@code name} is a registered struct into the
 * equivalent positional {@link IrExpr.Record} — mirroring what
 * {@code AltParser.parsePositionalStructLiteral} does for local structs. Arity is
 * checked against the struct's declared field count.
 *
 * <p><b>By-name imported literals.</b> The by-name form {@code Point{x=…}} for an
 * imported struct is the parser-blind twin: {@code AltParser} emits an
 * <em>uncanonicalized</em> {@link IrExpr.Record} (source field order, no field-set
 * check — it can't see the declaration). This pass validates the field set and
 * reorders members to declared order in {@link #canonicalizeByName}, applied to
 * <em>every</em> Record whose typeName resolves to a registered struct. It is
 * idempotent for records the parser already canonicalized (local literals,
 * positional literals) and a no-op for anonymous dicts (null typeName) and
 * non-struct typeNames (e.g. native {@code Decimal}).
 *
 * <p>Invoked <b>only by the module linker</b>. A single-file compile never runs
 * here — and never needs to, since its struct literals are already {@code Record}s
 * (the parser sees the local declaration). The pass is therefore additive and
 * leaves the single-file path byte-for-byte unchanged, exactly like
 * {@link NameResolver}.
 *
 * <p>Scope: positional and by-name construction. Struct literals embedded inside
 * refinement <em>predicates</em> are still not walked — they remain in the
 * parser-blindness bucket.
 */
public final class StructLiteralRewriter {

    private StructLiteralRewriter() {}

    public static IrModule rewrite(IrModule combined) throws CompileException {
        Map<String, IrSort.Structural> structs = TypeRegistry.collect(combined);
        if (structs.isEmpty()) return combined;  // nothing to rewrite

        List<IrStmt> out = new ArrayList<>(combined.statements().size());
        for (IrStmt stmt : combined.statements()) {
            out.add(switch (stmt) {
                case IrStmt.FunctionDecl fd -> new IrStmt.FunctionDecl(
                        fd.name(), fd.params(), fd.returnSort(),
                        rewriteExpr(fd.body(), structs), fd.origin(), fd.topLevelLet(),
                        fd.typeParams());
                case IrStmt.TraitImpl ti -> {
                    List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods().size());
                    for (IrStmt.FunctionDecl mm : ti.methods()) {
                        methods.add(new IrStmt.FunctionDecl(
                                mm.name(), mm.params(), mm.returnSort(),
                                rewriteExpr(mm.body(), structs), mm.origin()));
                    }
                    List<IrStmt.FunctionDecl> attrs = new ArrayList<>(ti.attributeProducers().size());
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                        attrs.add(new IrStmt.FunctionDecl(
                                a.name(), a.params(), a.returnSort(),
                                rewriteExpr(a.body(), structs), a.origin()));
                    }
                    yield new IrStmt.TraitImpl(ti.typeName(), ti.traitName(), methods, attrs,
                            ti.typeBindings(), ti.typeParams(), ti.traitTypeArgs(), ti.origin());
                }
                case IrStmt.Proof p -> new IrStmt.Proof(
                        p.functionName(), rewriteExpr(p.proofTree(), structs), p.origin());
                // A coercion body may construct an imported struct (`cast T:(x) -> S(x)`),
                // so rewrite it like a function body.
                case IrStmt.Coercion c -> new IrStmt.Coercion(
                        c.sourceSort(), c.targetSort(), c.paramName(),
                        rewriteExpr(c.body(), structs), c.origin());
                default -> stmt;  // TypeAlias / Requires / Exports / NoOp carry no struct-literal expr
            });
        }
        return new IrModule(combined.name(), out, rewriteExpr(combined.main(), structs));
    }

    private static IrExpr rewriteExpr(IrExpr e, Map<String, IrSort.Structural> structs)
            throws CompileException {
        return switch (e) {
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            case IrExpr.Chr c -> c;
            case IrExpr.Str s -> s;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            case IrExpr.DispatchRef d -> d;
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(), rewriteExpr(op.left(), structs), rewriteExpr(op.right(), structs), op.origin());
            case IrExpr.LetIn l -> new IrExpr.LetIn(
                    l.name(), l.declaredSort(), rewriteExpr(l.value(), structs),
                    rewriteExpr(l.body(), structs), l.origin(), l.claim());
            case IrExpr.Call c -> {
                List<IrExpr> args = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) args.add(rewriteExpr(a, structs));
                IrSort.Structural decl = structs.get(c.functionName());
                if (decl != null) {
                    yield asRecord(c.functionName(), decl, args, c.origin());
                }
                yield new IrExpr.Call(c.functionName(), args, c.origin());
            }
            case IrExpr.Lambda lam -> new IrExpr.Lambda(
                    lam.params(), lam.returnSort(), rewriteExpr(lam.body(), structs), lam.origin());
            case IrExpr.Apply app -> {
                List<IrExpr> args = new ArrayList<>(app.args().size());
                for (IrExpr a : app.args()) args.add(rewriteExpr(a, structs));
                yield new IrExpr.Apply(rewriteExpr(app.fn(), structs), args, app.origin());
            }
            case IrExpr.Match mt -> {
                List<IrExpr.MatchBranch> bs = new ArrayList<>(mt.branches().size());
                for (IrExpr.MatchBranch b : mt.branches()) {
                    bs.add(new IrExpr.MatchBranch(b.pattern(), rewriteExpr(b.result(), structs)));
                }
                yield new IrExpr.Match(rewriteExpr(mt.scrutinee(), structs), bs, mt.origin());
            }
            case IrExpr.Record r -> {
                Map<String, IrExpr> mem = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> en : r.members().entrySet()) {
                    mem.put(en.getKey(), rewriteExpr(en.getValue(), structs));
                }
                // A by-name literal for an IMPORTED struct was deferred by the
                // parser (uncanonicalized, source field order — it couldn't see
                // the declaration). Now the struct is FQN'd and visible: validate
                // the field set and reorder to declared order, the by-name twin of
                // asRecord. Idempotent for already-canonical records (locally
                // resolved literals, positional literals), and a no-op for
                // anonymous dicts / non-struct typeNames (e.g. native Decimal).
                IrSort.Structural decl = r.typeName() == null ? null : structs.get(r.typeName());
                if (decl != null) {
                    yield canonicalizeByName(r.typeName(), decl, mem, r.origin());
                }
                yield new IrExpr.Record(r.typeName(), mem, r.origin());
            }
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    rewriteExpr(fa.base(), structs), fa.fieldName(), fa.origin());
            case IrExpr.MethodCall mc -> {
                List<IrExpr> args = new ArrayList<>(mc.args().size());
                for (IrExpr a : mc.args()) args.add(rewriteExpr(a, structs));
                yield new IrExpr.MethodCall(
                        rewriteExpr(mc.receiver(), structs), mc.methodName(), args, mc.origin());
            }
            // REVISIT (docs/iteration.md §10): no struct-literal rewriting inside
            // the source / arm writes yet (slice 1 builds those explicitly).
            case IrExpr.Iterate it -> it;
            case IrExpr.Emit em -> new IrExpr.Emit(
                    rewriteExpr(em.event(), structs), rewriteExpr(em.body(), structs), em.origin());
            case IrExpr.Cast cast -> new IrExpr.Cast(cast.targetSort(),
                    rewriteExpr(cast.value(), structs), cast.origin());
        };
    }

    /** Zips positional args onto the struct's declared field order, like the parser does locally. */
    private static IrExpr.Record asRecord(
            String typeName, IrSort.Structural decl, List<IrExpr> args, sibarum.pontif.core.Origin origin)
            throws CompileException {
        List<String> fields = new ArrayList<>(decl.members().keySet());
        if (args.size() != fields.size()) {
            throw new CompileException(
                    "Struct literal for '" + typeName + "' expects " + fields.size()
                            + " positional arg(s) but got " + args.size()
                            + "; declared fields: " + fields, origin);
        }
        Map<String, IrExpr> ordered = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            ordered.put(fields.get(i), args.get(i));
        }
        return new IrExpr.Record(typeName, ordered, origin);
    }

    /**
     * Validates a by-name record's field set against the declared struct and
     * reorders its members to declared field order — mirroring
     * {@code AltParser.parseByNameStructLiteral} for a struct the parser couldn't
     * see (imported). Idempotent: a record already in declared order with the full
     * field set passes through unchanged.
     */
    private static IrExpr.Record canonicalizeByName(
            String typeName, IrSort.Structural decl, Map<String, IrExpr> provided,
            sibarum.pontif.core.Origin origin) throws CompileException {
        List<String> fields = new ArrayList<>(decl.members().keySet());
        for (String name : provided.keySet()) {
            if (!decl.members().containsKey(name)) {
                throw new CompileException(
                        "Struct '" + typeName + "' has no field '" + name
                                + "'; declared fields: " + fields, origin);
            }
        }
        Map<String, IrExpr> ordered = new LinkedHashMap<>();
        for (String field : fields) {
            IrExpr value = provided.get(field);
            if (value == null) {
                throw new CompileException(
                        "Struct literal for '" + typeName + "' is missing field '"
                                + field + "'; required fields: " + fields, origin);
            }
            ordered.put(field, value);
        }
        return new IrExpr.Record(typeName, ordered, origin);
    }
}

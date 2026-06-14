package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites a module's function/method/operator declarations and every call site
 * to fully-qualified keys ({@code module/localKey}), so that when modules are
 * merged their dispatch keys are globally collision-proof and a cross-module
 * call resolves to the right declaration.
 *
 * <p>Invoked <b>only by the module linker</b> (multi-file projects). A
 * single-file compile via {@code PontifCompiler.compile}/{@code compileAlt}
 * never runs this pass, so its dispatch keys stay bare and identical to today —
 * backward compatibility is structural, not rule-based.
 *
 * <p>Resolution of a call name {@code n} in module {@code M}:
 * <ol>
 *   <li>{@code M} declares {@code n} locally → {@code M/n};</li>
 *   <li>{@code n} is a {@code requires}-imported name from module {@code S} →
 *       {@code S/n};</li>
 *   <li>{@code n} is dotted {@code X.rest} where {@code X} is a required module →
 *       {@code X/rest};</li>
 *   <li>otherwise (primitive, local lambda binding, or genuinely unknown) →
 *       left bare for {@code SortChecker}/dispatch to handle as today.</li>
 * </ol>
 *
 * <p>Type references are FQN-rewritten too (per-module type namespacing): see
 * {@link #resolveTypeName}, which mirrors {@link #resolveCallName} over
 * {@code typeOwners} and additionally rejects an <b>ambiguous</b> bare type name
 * (declared in two or more other modules, not resolvable locally/by-import) with
 * a precise error instead of leaving it bare to fail later as "unknown sort".
 */
public final class NameResolver {

    /**
     * Type names that are never module-qualified: primitives and the parser's
     * internal sentinels. Mirrors {@code SortChecker.PRIMITIVE_SORT_NAMES}
     * (kept in sync — both must agree on "not a user type").
     */
    private static final Set<String> PRIMITIVES = Set.of("Int", "Bool", "Decimal", "_", "_record", "_tuple");

    private NameResolver() {}

    public static IrModule resolve(IrModule module, ModuleSymbolTable table) throws CompileException {
        String m = module.name();
        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt stmt : module.statements()) {
            out.add(switch (stmt) {
                case IrStmt.FunctionDecl fd -> new IrStmt.FunctionDecl(
                        ModuleSymbolTable.fqn(m, fd.name()), rewriteParams(fd.params(), m, table),
                        rewriteSort(fd.returnSort(), m, table), rewrite(fd.body(), m, table), fd.origin(),
                        fd.topLevelLet(), fd.typeParams());
                case IrStmt.TraitImpl ti -> {
                    List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods().size());
                    for (IrStmt.FunctionDecl mm : ti.methods()) {
                        methods.add(new IrStmt.FunctionDecl(
                                ModuleSymbolTable.fqn(m, mm.name()), rewriteParams(mm.params(), m, table),
                                rewriteSort(mm.returnSort(), m, table), rewrite(mm.body(), m, table), mm.origin()));
                    }
                    List<IrStmt.FunctionDecl> attrs = new ArrayList<>(ti.attributeProducers().size());
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                        attrs.add(new IrStmt.FunctionDecl(
                                ModuleSymbolTable.fqn(m, a.name()), rewriteParams(a.params(), m, table),
                                rewriteSort(a.returnSort(), m, table), rewrite(a.body(), m, table), a.origin()));
                    }
                    Map<String, IrSort> binds = new LinkedHashMap<>();
                    for (Map.Entry<String, IrSort> e : ti.typeBindings().entrySet()) {
                        binds.put(e.getKey(), rewriteSort(e.getValue(), m, table));
                    }
                    yield new IrStmt.TraitImpl(
                            resolveTypeName(ti.typeName(), m, table, ti.origin()),
                            resolveTypeName(ti.traitName(), m, table, ti.origin()),
                            methods, attrs, binds, ti.origin());
                }
                case IrStmt.TypeAlias ta -> new IrStmt.TypeAlias(
                        resolveTypeName(ta.name(), m, table, ta.origin()),
                        rewriteSort(ta.sort(), m, table), ta.origin());
                case IrStmt.Proof p -> new IrStmt.Proof(
                        resolveCallName(p.functionName(), m, table),
                        rewrite(p.proofTree(), m, table), p.origin());
                default -> stmt;  // Requires / Exports / NoOp unchanged
            });
        }
        return new IrModule(m, out, rewrite(module.main(), m, table));
    }

    private static List<IrParam> rewriteParams(List<IrParam> params, String m, ModuleSymbolTable table)
            throws CompileException {
        List<IrParam> out = new ArrayList<>(params.size());
        for (IrParam p : params) out.add(new IrParam(p.name(), rewriteSort(p.sort(), m, table)));
        return out;
    }

    /**
     * Resolve a type name to its FQN (mirrors {@link #resolveCallName} over
     * {@code typeOwners}). A bare name not resolvable locally, by import, or by
     * module-qualification but declared in <b>two or more</b> other modules is an
     * <b>ambiguous-type-reference</b> error rather than a silent leave-bare that
     * later surfaces as a generic "unknown sort". The sole-owner-unimported case
     * stays leave-bare (a separate design call — see TODO).
     */
    static String resolveTypeName(String t, String m, ModuleSymbolTable table, Origin origin)
            throws CompileException {
        if (t.indexOf('/') >= 0 || PRIMITIVES.contains(t)) return t;
        if (table.typeOwners(t).contains(m)) return ModuleSymbolTable.fqn(m, t);
        ModuleSymbolTable.ImportedName imported = table.importedName(m, t);
        // FQN via the DECLARING ORIGIN — a renamed type import
        // (`requires geo.{Point -> Pt}`) resolves local Pt to geo/Point, and a
        // RE-EXPORTED import chases through the exporting module to the true
        // declarer (std.proof re-exporting std.common's Leaf resolves to
        // std.common/Leaf — one nominal, however many doors it's served from).
        if (imported != null) {
            ModuleSymbolTable.ImportedName declarer = table.originOf(m, t);
            return declarer != null
                    ? ModuleSymbolTable.fqn(declarer.sourceModule(), declarer.remoteName())
                    : ModuleSymbolTable.fqn(imported.sourceModule(), imported.remoteName());
        }
        int dot = t.indexOf('.');
        if (dot > 0 && table.requiredModules(m).contains(t.substring(0, dot))) {
            return ModuleSymbolTable.fqn(t.substring(0, dot), t.substring(dot + 1));
        }
        Set<String> owners = table.typeOwners(t);
        if (owners.size() >= 2) {
            throw new CompileException(
                    "type '" + t + "' referenced in module '" + m + "' is ambiguous — "
                            + "declared in " + owners + "; qualify it (e.g. `"
                            + owners.iterator().next() + "." + t + "`) or import exactly one",
                    origin);
        }
        return t;  // primitive handled above / unknown / sole-owner-unimported — leave bare for SortChecker
    }

    /** Recursively FQN-rewrites every type name appearing in a sort. */
    private static IrSort rewriteSort(IrSort sort, String m, ModuleSymbolTable table)
            throws CompileException {
        return switch (sort) {
            case IrSort.Named n -> new IrSort.Named(resolveTypeName(n.name(), m, table, n.origin()), n.origin());
            case IrSort.Refined r -> new IrSort.Refined(
                    resolveTypeName(r.name(), m, table, r.origin()), r.predicate(), r.origin());
            case IrSort.Structural s -> {
                Map<String, IrSort> members = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort> e : s.members().entrySet()) {
                    members.put(e.getKey(), rewriteSort(e.getValue(), m, table));
                }
                IrSort base = s.baseSort() == null
                        ? null : rewriteSort(s.baseSort(), m, table);
                yield new IrSort.Structural(
                        resolveTypeName(s.name(), m, table, s.origin()), members, base, s.origin());
            }
            case IrSort.Method f -> {
                List<IrSort> ps = new ArrayList<>(f.paramSorts().size());
                for (IrSort p : f.paramSorts()) ps.add(rewriteSort(p, m, table));
                yield new IrSort.Method(ps, rewriteSort(f.returnSort(), m, table), f.origin());
            }
            case IrSort.Dispatch d -> {
                List<IrSort> ks = new ArrayList<>(d.keySorts().size());
                for (IrSort k : d.keySorts()) ks.add(rewriteSort(k, m, table));
                yield new IrSort.Dispatch(ks, rewriteSort(d.returnSort(), m, table), d.origin());
            }
            case IrSort.Trait t -> {
                Map<String, IrSort.Method> methods = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort.Method> e : t.methods().entrySet()) {
                    methods.put(e.getKey(), (IrSort.Method) rewriteSort(e.getValue(), m, table));
                }
                Map<String, IrSort> attrs = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort> e : t.attributes().entrySet()) {
                    attrs.put(e.getKey(), rewriteSort(e.getValue(), m, table));
                }
                Map<String, IrSort> assoc = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort> e : t.associatedTypes().entrySet()) {
                    // bound may be null (unbounded `type X`); resolve a present bound.
                    assoc.put(e.getKey(),
                            e.getValue() == null ? null : rewriteSort(e.getValue(), m, table));
                }
                yield new IrSort.Trait(
                        resolveTypeName(t.name(), m, table, t.origin()), methods, attrs, assoc, t.origin());
            }
            case IrSort.Union u -> {
                List<IrSort> bs = new ArrayList<>(u.branches().size());
                for (IrSort b : u.branches()) bs.add(rewriteSort(b, m, table));
                yield new IrSort.Union(bs, u.origin());
            }
            case IrSort.Intersection i -> {
                List<IrSort> bs = new ArrayList<>(i.branches().size());
                for (IrSort b : i.branches()) bs.add(rewriteSort(b, m, table));
                yield new IrSort.Intersection(bs, i.origin());
            }
        };
    }

    /** Resolve a call name to its FQN per the rules in the class doc. */
    static String resolveCallName(String n, String m, ModuleSymbolTable table) {
        if (n.indexOf('/') >= 0) return n;  // already an FQN
        if (table.moduleDeclaresFunction(m, n)) return ModuleSymbolTable.fqn(m, n);
        ModuleSymbolTable.ImportedName imported = table.importedName(m, n);
        // The FQN uses the DECLARING ORIGIN — the symbol as the module that
        // truly declares it knows it. The local name is just how this module
        // refers to it (rename), and the import's direct source may itself be
        // a RE-EXPORT (the chase finds the declarer).
        if (imported != null) {
            ModuleSymbolTable.ImportedName origin = table.originOf(m, n);
            return origin != null
                    ? ModuleSymbolTable.fqn(origin.sourceModule(), origin.remoteName())
                    : ModuleSymbolTable.fqn(imported.sourceModule(), imported.remoteName());
        }
        int dot = n.indexOf('.');
        if (dot > 0) {
            String prefix = n.substring(0, dot);
            String rest = n.substring(dot + 1);
            // Qualified function call: prefix is a required module → module/rest.
            if (table.requiredModules(m).contains(prefix)) {
                return ModuleSymbolTable.fqn(prefix, rest);
            }
            // Method / trait-method call: prefix is a TYPE (e.g. p.magnitude
            // routed to `Point.magnitude`, or `Show.render`). The method lives
            // in the type's owning module → ownerModule/Type.method.
            String typeOwner = table.soleTypeOwner(prefix);
            if (typeOwner != null) {
                return ModuleSymbolTable.fqn(typeOwner, n);
            }
        }
        return n;  // primitive / local lambda / unknown — leave bare
    }

    private static IrExpr rewrite(IrExpr e, String m, ModuleSymbolTable table) throws CompileException {
        return switch (e) {
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            case IrExpr.Chr c -> c;
            case IrExpr.Str s -> s;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            // A metareference names a dispatch — FQN-resolve the name like a
            // call site's, and rewrite type names inside the key sorts.
            case IrExpr.DispatchRef d -> {
                List<IrSort> keys = new ArrayList<>(d.keySorts().size());
                for (IrSort k : d.keySorts()) keys.add(rewriteSort(k, m, table));
                yield new IrExpr.DispatchRef(
                        resolveCallName(d.functionName(), m, table), keys, d.origin());
            }
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(), rewrite(op.left(), m, table), rewrite(op.right(), m, table), op.origin());
            case IrExpr.LetIn l -> new IrExpr.LetIn(
                    l.name(), rewriteSort(l.declaredSort(), m, table), rewrite(l.value(), m, table),
                    rewrite(l.body(), m, table), l.origin(),
                    l.claim() == null ? null : rewriteSort(l.claim(), m, table));
            case IrExpr.Call c -> {
                List<IrExpr> args = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) args.add(rewrite(a, m, table));
                yield new IrExpr.Call(resolveCallName(c.functionName(), m, table), args, c.origin());
            }
            case IrExpr.Lambda lam -> new IrExpr.Lambda(
                    rewriteParams(lam.params(), m, table), rewriteSort(lam.returnSort(), m, table),
                    rewrite(lam.body(), m, table), lam.origin());
            case IrExpr.Apply app -> {
                List<IrExpr> args = new ArrayList<>(app.args().size());
                for (IrExpr a : app.args()) args.add(rewrite(a, m, table));
                yield new IrExpr.Apply(rewrite(app.fn(), m, table), args, app.origin());
            }
            case IrExpr.Match mt -> {
                List<IrExpr.MatchBranch> bs = new ArrayList<>(mt.branches().size());
                for (IrExpr.MatchBranch b : mt.branches()) {
                    bs.add(new IrExpr.MatchBranch(
                            rewriteSort(b.pattern(), m, table), rewrite(b.result(), m, table)));
                }
                yield new IrExpr.Match(rewrite(mt.scrutinee(), m, table), bs, mt.origin());
            }
            case IrExpr.Record r -> {
                Map<String, IrExpr> mem = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> en : r.members().entrySet()) {
                    mem.put(en.getKey(), rewrite(en.getValue(), m, table));
                }
                String typeName = r.typeName() == null ? null : resolveTypeName(r.typeName(), m, table, r.origin());
                yield new IrExpr.Record(typeName, mem, r.origin());
            }
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    rewrite(fa.base(), m, table), fa.fieldName(), fa.origin());
            case IrExpr.MethodCall mc -> {
                List<IrExpr> args = new ArrayList<>(mc.args().size());
                for (IrExpr a : mc.args()) args.add(rewrite(a, m, table));
                // The receiver/args names get FQN-resolved here; the method name
                // stays bare and is resolved against the receiver type by
                // MethodResolver (which keys it Type.method itself).
                yield new IrExpr.MethodCall(
                        rewrite(mc.receiver(), m, table), mc.methodName(), args, mc.origin());
            }
        };
    }
}

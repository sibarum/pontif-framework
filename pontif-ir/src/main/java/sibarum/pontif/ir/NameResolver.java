package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>v1 leaves <b>type</b> references bare (type names are global across a
 * project); per-module type namespacing is a follow-up. So only function-shaped
 * names are qualified here.
 */
public final class NameResolver {

    private NameResolver() {}

    public static IrModule resolve(IrModule module, ModuleSymbolTable table) {
        String m = module.name();
        List<IrStmt> out = new ArrayList<>(module.statements().size());
        for (IrStmt stmt : module.statements()) {
            out.add(switch (stmt) {
                case IrStmt.FunctionDecl fd -> new IrStmt.FunctionDecl(
                        ModuleSymbolTable.fqn(m, fd.name()), fd.params(),
                        fd.returnSort(), rewrite(fd.body(), m, table), fd.origin());
                case IrStmt.TraitImpl ti -> {
                    List<IrStmt.FunctionDecl> methods = new ArrayList<>(ti.methods().size());
                    for (IrStmt.FunctionDecl mm : ti.methods()) {
                        methods.add(new IrStmt.FunctionDecl(
                                ModuleSymbolTable.fqn(m, mm.name()), mm.params(),
                                mm.returnSort(), rewrite(mm.body(), m, table), mm.origin()));
                    }
                    yield new IrStmt.TraitImpl(ti.typeName(), ti.traitName(), methods, ti.origin());
                }
                default -> stmt;  // TypeAlias / Requires / Exports / Proof / NoOp unchanged
            });
        }
        return new IrModule(m, out, rewrite(module.main(), m, table));
    }

    /** Resolve a call name to its FQN per the rules in the class doc. */
    static String resolveCallName(String n, String m, ModuleSymbolTable table) {
        if (n.indexOf('/') >= 0) return n;  // already an FQN
        if (table.moduleDeclaresFunction(m, n)) return ModuleSymbolTable.fqn(m, n);
        String src = table.importSource(m, n);
        if (src != null) return ModuleSymbolTable.fqn(src, n);
        int dot = n.indexOf('.');
        if (dot > 0 && table.requiredModules(m).contains(n.substring(0, dot))) {
            return ModuleSymbolTable.fqn(n.substring(0, dot), n.substring(dot + 1));
        }
        return n;  // primitive / local lambda / unknown — leave bare
    }

    private static IrExpr rewrite(IrExpr e, String m, ModuleSymbolTable table) {
        return switch (e) {
            case IrExpr.Lit l -> l;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(), rewrite(op.left(), m, table), rewrite(op.right(), m, table), op.origin());
            case IrExpr.LetIn l -> new IrExpr.LetIn(
                    l.name(), l.declaredSort(), rewrite(l.value(), m, table),
                    rewrite(l.body(), m, table), l.origin());
            case IrExpr.Call c -> {
                List<IrExpr> args = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) args.add(rewrite(a, m, table));
                yield new IrExpr.Call(resolveCallName(c.functionName(), m, table), args, c.origin());
            }
            case IrExpr.Lambda lam -> new IrExpr.Lambda(
                    lam.params(), lam.returnSort(), rewrite(lam.body(), m, table), lam.origin());
            case IrExpr.Apply app -> {
                List<IrExpr> args = new ArrayList<>(app.args().size());
                for (IrExpr a : app.args()) args.add(rewrite(a, m, table));
                yield new IrExpr.Apply(rewrite(app.fn(), m, table), args, app.origin());
            }
            case IrExpr.Match mt -> {
                List<IrExpr.MatchBranch> bs = new ArrayList<>(mt.branches().size());
                for (IrExpr.MatchBranch b : mt.branches()) {
                    bs.add(new IrExpr.MatchBranch(b.pattern(), rewrite(b.result(), m, table)));
                }
                yield new IrExpr.Match(rewrite(mt.scrutinee(), m, table), bs, mt.origin());
            }
            case IrExpr.Record r -> {
                Map<String, IrExpr> mem = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> en : r.members().entrySet()) {
                    mem.put(en.getKey(), rewrite(en.getValue(), m, table));
                }
                yield new IrExpr.Record(r.typeName(), mem, r.origin());
            }
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    rewrite(fa.base(), m, table), fa.fieldName(), fa.origin());
        };
    }
}

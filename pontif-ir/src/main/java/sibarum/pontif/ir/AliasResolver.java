package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Substitutes named type-alias references with their aliased sorts.
 *
 * <p>Given a module containing {@link IrStmt.TypeAlias} declarations,
 * builds a name → sort table, then walks every other statement
 * (function decls and the main expression) replacing
 * {@link IrSort.Named} references whose name matches an alias with the
 * aliased sort. Recursive — substituting inside structural sort members
 * and function-sort params/return.
 *
 * <p>What is NOT substituted (intentional, keeps the IR representable):
 * <ul>
 *   <li>The name field of {@link IrSort.Refined}. {@code (refined Coord …)}
 *       where {@code Coord} is an alias stays as {@code (refined Coord …)};
 *       it's not unwrapped to {@code (refined (refined Int …) …)} (a shape
 *       the IR can't represent anyway). Workaround for users: write the
 *       refinement against the primitive directly.</li>
 *   <li>The name field of {@link IrSort.Structural}. Same reasoning —
 *       it identifies the sort, not a reference to another sort.</li>
 * </ul>
 *
 * <p>Cycle detection: if alias {@code A → … → A}, throws
 * {@link CompileException} with the cycle's path.
 *
 * <p>Output {@link IrModule} has all {@link IrStmt.TypeAlias} statements
 * stripped — they're metadata once resolution is complete.
 */
public final class AliasResolver {

    private AliasResolver() {}

    public static IrModule resolve(IrModule module) throws CompileException {
        Map<String, IrSort> aliases = new HashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.TypeAlias ta) {
                if (aliases.containsKey(ta.name())) {
                    throw new CompileException(
                            "Duplicate type alias '" + ta.name() + "'",
                            ta.origin());
                }
                aliases.put(ta.name(), ta.sort());
            }
        }

        // Pre-resolve each alias definition fully, so subsequent substitutions
        // can use the fully-resolved table without recursive lookup at use sites.
        // Cycle detection happens here.
        Map<String, IrSort> resolvedAliases = new HashMap<>();
        for (Map.Entry<String, IrSort> e : aliases.entrySet()) {
            resolvedAliases.put(e.getKey(),
                    resolveSort(e.getValue(), aliases, new ArrayList<>(List.of(e.getKey()))));
        }

        // Now rewrite the module's other statements + main against the fully-
        // resolved alias table.
        List<IrStmt> newStatements = new ArrayList<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                newStatements.add(rewriteFunctionDecl(fd, resolvedAliases));
            } else if (stmt instanceof IrStmt.TraitImpl ti) {
                List<IrStmt.FunctionDecl> rewrittenMethods = new ArrayList<>(ti.methods().size());
                for (IrStmt.FunctionDecl m : ti.methods()) {
                    rewrittenMethods.add(rewriteFunctionDecl(m, resolvedAliases));
                }
                newStatements.add(new IrStmt.TraitImpl(
                        ti.typeName(), ti.traitName(), rewrittenMethods, ti.origin()));
            } else if (stmt instanceof IrStmt.TypeAlias ta && ta.sort() instanceof IrSort.Trait) {
                // Trait declarations are kept — SortChecker needs the contract
                // info to validate TraitImpl statements. Struct TypeAliases are
                // dropped (they've served their purpose: substitution).
                newStatements.add(ta);
            } else if (stmt instanceof IrStmt.NoOp np) {
                newStatements.add(np);  // pass through; nothing to resolve
            }
            // Struct TypeAlias statements are dropped from output.
        }
        IrExpr newMain = rewriteExpr(module.main(), resolvedAliases);

        return new IrModule(module.name(), newStatements, newMain);
    }

    // --- Sort resolution ---

    private static IrSort resolveSort(IrSort sort, Map<String, IrSort> aliases, List<String> path)
            throws CompileException {
        return switch (sort) {
            case IrSort.Named n -> {
                if (aliases.containsKey(n.name())) {
                    if (path.contains(n.name())) {
                        List<String> cyclePath = new ArrayList<>(path);
                        cyclePath.add(n.name());
                        throw new CompileException(
                                "Cyclic type alias chain: " + String.join(" → ", cyclePath),
                                n.origin());
                    }
                    List<String> nextPath = new ArrayList<>(path);
                    nextPath.add(n.name());
                    yield resolveSort(aliases.get(n.name()), aliases, nextPath);
                }
                yield n;  // not an alias, primitive or unknown name
            }
            case IrSort.Refined r ->
                    // Keep refined sorts as-is — name is the refinement base, not a reference to substitute.
                    r;
            case IrSort.Structural s -> {
                Map<String, IrSort> resolvedMembers = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort> e : s.members().entrySet()) {
                    resolvedMembers.put(e.getKey(), resolveSort(e.getValue(), aliases, path));
                }
                yield new IrSort.Structural(s.name(), resolvedMembers, s.origin());
            }
            case IrSort.Function f -> {
                List<IrSort> resolvedParams = new ArrayList<>(f.paramSorts().size());
                for (IrSort p : f.paramSorts()) {
                    resolvedParams.add(resolveSort(p, aliases, path));
                }
                yield new IrSort.Function(resolvedParams, resolveSort(f.returnSort(), aliases, path), f.origin());
            }
            case IrSort.Trait t -> {
                // Trait sort's method signatures are Function sorts; recurse
                // into each to substitute any aliased param/return types.
                Map<String, IrSort.Function> resolvedMethods = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort.Function> e : t.methods().entrySet()) {
                    resolvedMethods.put(
                            e.getKey(),
                            (IrSort.Function) resolveSort(e.getValue(), aliases, path));
                }
                yield new IrSort.Trait(t.name(), resolvedMethods, t.origin());
            }
        };
    }

    // --- Function-decl rewriting ---

    private static IrStmt.FunctionDecl rewriteFunctionDecl(IrStmt.FunctionDecl fd,
                                                           Map<String, IrSort> resolved)
            throws CompileException {
        List<IrParam> newParams = new ArrayList<>(fd.params().size());
        for (IrParam p : fd.params()) {
            newParams.add(new IrParam(p.name(), substituteResolved(p.sort(), resolved)));
        }
        IrSort newReturn = substituteResolved(fd.returnSort(), resolved);
        IrExpr newBody = rewriteExpr(fd.body(), resolved);
        return new IrStmt.FunctionDecl(fd.name(), newParams, newReturn, newBody, fd.origin());
    }

    // --- Expression rewriting (touches sort references inside Let, Lambda, Match patterns) ---

    private static IrExpr rewriteExpr(IrExpr expr, Map<String, IrSort> resolved) throws CompileException {
        return switch (expr) {
            case IrExpr.Lit l -> l;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            case IrExpr.BinOp op -> new IrExpr.BinOp(
                    op.op(),
                    rewriteExpr(op.left(), resolved),
                    rewriteExpr(op.right(), resolved),
                    op.origin());
            case IrExpr.LetIn l -> new IrExpr.LetIn(
                    l.name(),
                    substituteResolved(l.declaredSort(), resolved),
                    rewriteExpr(l.value(), resolved),
                    rewriteExpr(l.body(), resolved),
                    l.origin());
            case IrExpr.Call c -> {
                List<IrExpr> newArgs = new ArrayList<>(c.args().size());
                for (IrExpr a : c.args()) newArgs.add(rewriteExpr(a, resolved));
                yield new IrExpr.Call(c.functionName(), newArgs, c.origin());
            }
            case IrExpr.Lambda lam -> {
                List<IrParam> newParams = new ArrayList<>(lam.params().size());
                for (IrParam p : lam.params()) {
                    newParams.add(new IrParam(p.name(), substituteResolved(p.sort(), resolved)));
                }
                yield new IrExpr.Lambda(
                        newParams,
                        substituteResolved(lam.returnSort(), resolved),
                        rewriteExpr(lam.body(), resolved),
                        lam.origin());
            }
            case IrExpr.Apply app -> {
                List<IrExpr> newArgs = new ArrayList<>(app.args().size());
                for (IrExpr a : app.args()) newArgs.add(rewriteExpr(a, resolved));
                yield new IrExpr.Apply(rewriteExpr(app.fn(), resolved), newArgs, app.origin());
            }
            case IrExpr.Match m -> {
                List<IrExpr.MatchBranch> newBranches = new ArrayList<>(m.branches().size());
                for (IrExpr.MatchBranch b : m.branches()) {
                    newBranches.add(new IrExpr.MatchBranch(
                            substituteResolved(b.pattern(), resolved),
                            rewriteExpr(b.result(), resolved)));
                }
                yield new IrExpr.Match(rewriteExpr(m.scrutinee(), resolved), newBranches, m.origin());
            }
            case IrExpr.Record r -> {
                Map<String, IrExpr> newMembers = new LinkedHashMap<>();
                for (Map.Entry<String, IrExpr> e : r.members().entrySet()) {
                    newMembers.put(e.getKey(), rewriteExpr(e.getValue(), resolved));
                }
                yield new IrExpr.Record(r.typeName(), newMembers, r.origin());
            }
            case IrExpr.FieldAccess fa -> new IrExpr.FieldAccess(
                    rewriteExpr(fa.base(), resolved), fa.fieldName(), fa.origin());
        };
    }

    /**
     * Replace top-level Named-sort references with their resolved aliases.
     * For compound sorts (Structural, Function), recurse into children.
     * Refined is returned as-is (per the policy above).
     */
    private static IrSort substituteResolved(IrSort sort, Map<String, IrSort> resolved) {
        return switch (sort) {
            case IrSort.Named n -> resolved.getOrDefault(n.name(), n);
            case IrSort.Refined r -> r;
            case IrSort.Structural s -> {
                Map<String, IrSort> newMembers = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort> e : s.members().entrySet()) {
                    newMembers.put(e.getKey(), substituteResolved(e.getValue(), resolved));
                }
                yield new IrSort.Structural(s.name(), newMembers, s.origin());
            }
            case IrSort.Function f -> {
                List<IrSort> newParams = new ArrayList<>(f.paramSorts().size());
                for (IrSort p : f.paramSorts()) newParams.add(substituteResolved(p, resolved));
                yield new IrSort.Function(newParams, substituteResolved(f.returnSort(), resolved), f.origin());
            }
            case IrSort.Trait t -> {
                Map<String, IrSort.Function> newMethods = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort.Function> e : t.methods().entrySet()) {
                    newMethods.put(
                            e.getKey(),
                            (IrSort.Function) substituteResolved(e.getValue(), resolved));
                }
                yield new IrSort.Trait(t.name(), newMethods, t.origin());
            }
        };
    }
}

package sibarum.pontif.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        // Structs are NOMINAL: a struct reference stays IrSort.Named and is
        // resolved by name against the registry on demand, never inlined — this
        // is what lets the type graph refer to itself through a constructor
        // (recursive types). Only pure abbreviations (type Coord = Int, type
        // Pair = [A|B]) inline. Excluding structs from the alias table means
        // resolveSort never follows a reference into a struct body, so any
        // recursion that passes THROUGH a struct constructor is naturally
        // admitted, while a constructor-free abbreviation cycle (type A = [A|Int])
        // is still caught by the path-based cycle check below. That equivalence
        // — "recursion must pass through a constructor" — is the contractiveness
        // discipline, enforced for free by the exclusion.
        Map<String, IrSort> aliases = new HashMap<>();
        Set<String> declaredNames = new HashSet<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.TypeAlias ta) {
                if (!declaredNames.add(ta.name())) {
                    throw new CompileException(
                            "Duplicate type alias '" + ta.name() + "'",
                            ta.origin());
                }
                // Struct definitions are nominal — kept by-reference, never
                // entered into the inlining table — so abbreviations inline but
                // struct references (incl. self-references) stay IrSort.Named.
                // Traits ARE inlined (the common, non-recursive case relies on
                // it for coercion/dispatch), but a trait that references ITSELF
                // resolves the self-occurrence to a nominal trait shell rather
                // than expanding forever — see resolveSort's cycle handling.
                if (!(ta.sort() instanceof IrSort.Structural)) {
                    aliases.put(ta.name(), ta.sort());
                }
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
                List<IrStmt.FunctionDecl> rewrittenAttrs = new ArrayList<>(ti.attributeProducers().size());
                for (IrStmt.FunctionDecl a : ti.attributeProducers()) {
                    rewrittenAttrs.add(rewriteFunctionDecl(a, resolvedAliases));
                }
                newStatements.add(new IrStmt.TraitImpl(
                        ti.typeName(), ti.traitName(), rewrittenMethods, rewrittenAttrs, ti.origin()));
            } else if (stmt instanceof IrStmt.TypeAlias ta) {
                // Type aliases are kept downstream so SortChecker can see them:
                // trait contracts for TraitImpl validation; struct definitions
                // for nominal resolution + `[StructName:@.field…]` refinement
                // validation; other aliases are inert pass-through (IrCompiler
                // skips them). For a struct definition we still resolve
                // abbreviation references inside its member sorts (so a field
                // typed `Coord` where `type Coord = Int` becomes `Int`); struct
                // references stay nominal because structs are absent from
                // resolvedAliases — substituteResolved leaves them as Named,
                // which also makes this terminate on a recursive member.
                if (ta.sort() instanceof IrSort.Structural) {
                    newStatements.add(new IrStmt.TypeAlias(
                            ta.name(),
                            substituteResolved(ta.sort(), resolvedAliases),
                            ta.origin()));
                } else {
                    newStatements.add(ta);
                }
            } else if (stmt instanceof IrStmt.Proof p) {
                // Pass through unchanged: the proof tree's Var("x") are proof
                // variables (the function's params), not sort aliases — there is
                // nothing to resolve, and rewriting would be wrong. The gate
                // reads it and translates it after drafting.
                newStatements.add(p);
            } else if (stmt instanceof IrStmt.ReturnProof rp) {
                // Pass through unchanged (like Proof). The granted return + the
                // case-function guards are consumed by the inference layer
                // (call-site narrowing) and the return gate; the body's vars are
                // proof variables, not aliases. (Alias resolution inside a granted
                // return is a follow-up if ever needed.) Critically, this must NOT
                // be dropped — doing so blinds every fromModule-built consumer to
                // the proof's region narrowing.
                newStatements.add(rp);
            } else if (stmt instanceof IrStmt.Requires || stmt instanceof IrStmt.Exports) {
                // Module import/export decls: no sorts to resolve; the loader/
                // linker + name resolver consume them. Pass through unchanged.
                newStatements.add(stmt);
            } else if (stmt instanceof IrStmt.NoOp np) {
                newStatements.add(np);  // pass through; nothing to resolve
            } else {
                newStatements.add(stmt);  // never silently drop an unhandled kind
            }
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
                        // A trait that references itself (directly or mutually)
                        // is a legitimate recursive type — an `Expr` trait whose
                        // method returns `Expr`. Resolve the cyclic occurrence to
                        // a nominal trait SHELL (name only) rather than expanding
                        // forever: the full contract lives in the preserved
                        // TypeAlias, and downstream identifies traits by name.
                        // Only a constructor-free ABBREVIATION cycle
                        // (`type A = [A|Int]`) is a real, non-contractive error.
                        if (aliases.get(n.name()) instanceof IrSort.Trait) {
                            yield new IrSort.Trait(
                                    n.name(), Map.of(), Map.of(), n.origin());
                        }
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
                IrSort resolvedBase = s.baseSort() == null
                        ? null : resolveSort(s.baseSort(), aliases, path);
                yield new IrSort.Structural(s.name(), resolvedMembers, resolvedBase, s.origin());
            }
            case IrSort.Method f -> {
                List<IrSort> resolvedParams = new ArrayList<>(f.paramSorts().size());
                for (IrSort p : f.paramSorts()) {
                    resolvedParams.add(resolveSort(p, aliases, path));
                }
                yield new IrSort.Method(resolvedParams, resolveSort(f.returnSort(), aliases, path), f.origin());
            }
            case IrSort.Dispatch d -> {
                List<IrSort> resolvedKeys = new ArrayList<>(d.keySorts().size());
                for (IrSort k : d.keySorts()) {
                    resolvedKeys.add(resolveSort(k, aliases, path));
                }
                yield new IrSort.Dispatch(resolvedKeys, resolveSort(d.returnSort(), aliases, path), d.origin());
            }
            case IrSort.Trait t -> {
                // Trait sort's method signatures are Function sorts; recurse
                // into each to substitute any aliased param/return types.
                Map<String, IrSort.Method> resolvedMethods = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort.Method> e : t.methods().entrySet()) {
                    resolvedMethods.put(
                            e.getKey(),
                            (IrSort.Method) resolveSort(e.getValue(), aliases, path));
                }
                Map<String, IrSort> resolvedAttrs = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort> e : t.attributes().entrySet()) {
                    resolvedAttrs.put(e.getKey(), resolveSort(e.getValue(), aliases, path));
                }
                yield new IrSort.Trait(t.name(), resolvedMethods, resolvedAttrs, t.origin());
            }
            case IrSort.Union u -> {
                List<IrSort> resolved = new ArrayList<>(u.branches().size());
                for (IrSort b : u.branches()) resolved.add(resolveSort(b, aliases, path));
                yield new IrSort.Union(resolved, u.origin());
            }
            case IrSort.Intersection i -> {
                List<IrSort> resolved = new ArrayList<>(i.branches().size());
                for (IrSort b : i.branches()) resolved.add(resolveSort(b, aliases, path));
                yield new IrSort.Intersection(resolved, i.origin());
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
        return new IrStmt.FunctionDecl(
                fd.name(), newParams, newReturn, newBody, fd.origin(), fd.topLevelLet());
    }

    // --- Expression rewriting (touches sort references inside Let, Lambda, Match patterns) ---

    private static IrExpr rewriteExpr(IrExpr expr, Map<String, IrSort> resolved) throws CompileException {
        return switch (expr) {
            case IrExpr.Lit l -> l;
            case IrExpr.Dec d -> d;
            case IrExpr.Chr c -> c;
            case IrExpr.Str s -> s;
            case IrExpr.Bool b -> b;
            case IrExpr.Var v -> v;
            case IrExpr.SelfRef s -> s;
            case IrExpr.DispatchRef d -> {
                List<IrSort> newKeys = new ArrayList<>(d.keySorts().size());
                for (IrSort k : d.keySorts()) newKeys.add(substituteResolved(k, resolved));
                yield new IrExpr.DispatchRef(d.functionName(), newKeys, d.origin());
            }
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
                    l.origin(),
                    l.claim() == null ? null : substituteResolved(l.claim(), resolved));
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
            case IrExpr.MethodCall mc -> {
                List<IrExpr> args = new ArrayList<>(mc.args().size());
                for (IrExpr a : mc.args()) args.add(rewriteExpr(a, resolved));
                yield new IrExpr.MethodCall(
                        rewriteExpr(mc.receiver(), resolved), mc.methodName(), args, mc.origin());
            }
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
                IrSort newBase = s.baseSort() == null
                        ? null : substituteResolved(s.baseSort(), resolved);
                yield new IrSort.Structural(s.name(), newMembers, newBase, s.origin());
            }
            case IrSort.Dispatch d -> {
                List<IrSort> newKeys = new ArrayList<>(d.keySorts().size());
                for (IrSort k : d.keySorts()) newKeys.add(substituteResolved(k, resolved));
                yield new IrSort.Dispatch(newKeys, substituteResolved(d.returnSort(), resolved), d.origin());
            }
            case IrSort.Method f -> {
                List<IrSort> newParams = new ArrayList<>(f.paramSorts().size());
                for (IrSort p : f.paramSorts()) newParams.add(substituteResolved(p, resolved));
                yield new IrSort.Method(newParams, substituteResolved(f.returnSort(), resolved), f.origin());
            }
            case IrSort.Trait t -> {
                Map<String, IrSort.Method> newMethods = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort.Method> e : t.methods().entrySet()) {
                    newMethods.put(
                            e.getKey(),
                            (IrSort.Method) substituteResolved(e.getValue(), resolved));
                }
                Map<String, IrSort> newAttrs = new LinkedHashMap<>();
                for (Map.Entry<String, IrSort> e : t.attributes().entrySet()) {
                    newAttrs.put(e.getKey(), substituteResolved(e.getValue(), resolved));
                }
                yield new IrSort.Trait(t.name(), newMethods, newAttrs, t.origin());
            }
            case IrSort.Union u -> {
                List<IrSort> newBranches = new ArrayList<>(u.branches().size());
                for (IrSort b : u.branches()) newBranches.add(substituteResolved(b, resolved));
                yield new IrSort.Union(newBranches, u.origin());
            }
            case IrSort.Intersection i -> {
                List<IrSort> newBranches = new ArrayList<>(i.branches().size());
                for (IrSort b : i.branches()) newBranches.add(substituteResolved(b, resolved));
                yield new IrSort.Intersection(newBranches, i.origin());
            }
        };
    }
}

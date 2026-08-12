package sibarum.pontif.ir;

import sibarum.pontif.core.QualifiedName;
import sibarum.pontif.types.TypeCatalog;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An early name-resolution gate: every free / static {@link IrExpr.Call} must name a declared
 * function (or an in-scope local callable). Run <b>before</b> {@link MethodOperatorResolver} (from
 * its {@code resolve}/{@code resolvePerModule} entry points), so an un-imported / undeclared call
 * like {@code TractionCD.of(...)} fails fast with a clear, correctly-located error — instead of
 * silently flowing on with an unknown type and surfacing three calls downstream as the misleading
 * "Cannot determine the type of the receiver of method '…'" (the root cause was pass ordering:
 * {@link SortChecker} already carries this check, but runs after the resolver).
 *
 * <p>At this stage operators are still {@link IrExpr.BinOp} and instance methods still
 * {@link IrExpr.MethodCall} — only free / static calls are {@code Call}, so a Call-name check sees
 * exactly the names to validate and naturally skips the constructs the resolver handles later.
 * Parse-routed operator-symbol Calls (e.g. {@code Call("+", …)}) are excluded explicitly and left
 * to operator routing. The known-function set reuses {@link SortChecker#collectFunctionReturns}
 * (declared functions + trait-impl methods/attributes + trait-contract methods + merged builtins).
 */
public final class CallNameCheck {

    private CallNameCheck() {}

    /** Validates every free/static call name in {@code module}; throws on the first unresolved one. */
    public static void check(IrModule module) throws CompileException {
        Set<String> known = SortChecker.collectFunctionReturns(module).keySet();
        // Struct shapes so a member body's unresolved dotted call whose head is one of the
        // enclosing type's fields (e.g. `left.walk()`, forgetting `this.`) gets a targeted hint
        // rather than the misleading "is it imported with `requires`?" module suggestion.
        Map<String, IrSort.Structural> structShapes = TypeCatalog.fromModule(module).structShapes();
        for (IrStmt stmt : module.statements()) {
            switch (stmt) {
                case IrStmt.FunctionDecl fd -> checkFunction(fd, known, Set.of());
                case IrStmt.TraitImpl ti -> {
                    Set<String> fields = fieldsOf(ti.typeName(), structShapes);
                    for (IrStmt.FunctionDecl m : ti.methods()) checkFunction(m, known, fields);
                    for (IrStmt.FunctionDecl a : ti.attributeProducers()) checkFunction(a, known, fields);
                }
                default -> { /* coercions/proofs/requires/exports declare no checkable body */ }
            }
        }
        if (module.main() != null) walk(module.main(), new HashSet<>(), known, Set.of());
    }

    /** Field names of the struct a trait impl targets, or empty when the shape is unknown. */
    private static Set<String> fieldsOf(String typeName, Map<String, IrSort.Structural> shapes) {
        IrSort.Structural s = shapes.get(typeName);
        if (s == null) s = shapes.get(QualifiedName.memberOf(typeName));   // combined module uses FQN keys
        return s == null ? Set.of() : s.members().keySet();
    }

    private static void checkFunction(IrStmt.FunctionDecl fd, Set<String> known, Set<String> fields)
            throws CompileException {
        Set<String> scope = new HashSet<>();
        for (IrParam p : fd.params()) {
            scope.add(p.name());
            collectBinders(p.sort(), scope);   // destructure-param binders (`a:[T(p)]` binds p)
        }
        walk(fd.body(), scope, known, fields);
    }

    private static void walk(IrExpr expr, Set<String> scope, Set<String> known, Set<String> fields)
            throws CompileException {
        switch (expr) {
            case IrExpr.Call c -> {
                String member = QualifiedName.memberOf(c.functionName());
                if (!c.functionName().startsWith("#")   // reserved synthetic call (e.g. #assign-self#), interpreter-handled
                        && !MethodOperatorResolver.isOperatorSymbol(member)
                        && !known.contains(c.functionName())
                        && !scope.contains(c.functionName())) {
                    throw new CompileException(unknownCallMessage(c.functionName(), fields), c.origin());
                }
                for (IrExpr a : c.args()) walk(a, scope, known, fields);
            }
            // Operators (still BinOp here) and instance methods (still MethodCall) are resolved by
            // MethodOperatorResolver — don't name-check them, just recurse to find nested Calls.
            case IrExpr.BinOp op -> { walk(op.left(), scope, known, fields); walk(op.right(), scope, known, fields); }
            case IrExpr.MethodCall mc -> {
                walk(mc.receiver(), scope, known, fields);
                for (IrExpr a : mc.args()) walk(a, scope, known, fields);
            }
            case IrExpr.LetIn l -> {
                walk(l.value(), scope, known, fields);
                Set<String> ext = new HashSet<>(scope);
                ext.add(l.name());
                walk(l.body(), ext, known, fields);
            }
            case IrExpr.Lambda lam -> {
                Set<String> ext = new HashSet<>(scope);
                for (IrParam p : lam.params()) ext.add(p.name());
                walk(lam.body(), ext, known, fields);
            }
            case IrExpr.Apply app -> {
                walk(app.fn(), scope, known, fields);
                for (IrExpr a : app.args()) walk(a, scope, known, fields);
            }
            case IrExpr.Match m -> {
                walk(m.scrutinee(), scope, known, fields);
                for (IrExpr.MatchBranch b : m.branches()) {
                    Set<String> ext = new HashSet<>(scope);
                    collectBinders(b.pattern(), ext);   // pattern binders (pre-DestructureResolver path)
                    walk(b.result(), ext, known, fields);
                }
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) walk(v, scope, known, fields);
            }
            case IrExpr.FieldAccess fa -> walk(fa.base(), scope, known, fields);
            case IrExpr.Iterate it -> {
                walk(it.source(), scope, known, fields);
                for (IrExpr cs : it.coSources()) walk(cs, scope, known, fields);
                Set<String> ext = new HashSet<>(scope);
                ext.add(it.element());
                for (IrExpr.OutputSpec os : it.outputs()) {
                    if (os.init() != null) walk(os.init(), scope, known, fields);
                    if (os.kind() == IrExpr.OutputKind.ACCUMULATOR) ext.add(os.name());
                }
                for (IrExpr.Arm arm : it.arms()) {
                    for (IrExpr.Write w : arm.writes()) {
                        if (w.key() != null) walk(w.key(), ext, known, fields);
                        walk(w.value(), ext, known, fields);
                    }
                }
            }
            case IrExpr.Emit em -> { walk(em.event(), scope, known, fields); walk(em.body(), scope, known, fields); }
            case IrExpr.Cast cast -> walk(cast.value(), scope, known, fields);
            // Leaves with no sub-expressions / no free-call children: Lit, Dec, Chr, Str, Bool,
            // SelfRef, Var (a bare name — the unbound-variable check is SortChecker's job),
            // DispatchRef (SortChecker validates the metareference target).
            default -> { /* no nested expression to walk */ }
        }
    }

    /**
     * The error for an unresolved free/static call. When the name is dotted and its head is a field
     * of the enclosing type — the classic "wrote {@code left.walk()} instead of {@code this.left.walk()}"
     * slip — a bare field isn't in local scope, so {@code left.walk} was read as a {@code module.function}
     * call and fell through to here. Point at {@code this} instead of the misleading import hint.
     */
    private static String unknownCallMessage(String functionName, Set<String> fields) {
        int dot = functionName.indexOf('.');
        if (dot > 0) {
            String head = functionName.substring(0, dot);
            String rest = functionName.substring(dot + 1);
            if (fields.contains(head)) {
                return "Unknown function '" + functionName + "' — '" + head + "' is a field of the "
                        + "enclosing type, not a module. Inside a member body, fields are reached "
                        + "through `this` — did you mean `this." + head + "." + rest + "(...)`?";
            }
        }
        return "Unknown function '" + functionName + "' — not in scope (no declared function of "
                + "that name; if it comes from another module, is it imported with `requires`?).";
    }

    /**
     * Adds destructure-pattern binder names to {@code into} — a structural pattern's member values
     * that are {@link IrSort.Named} binders (recursing into nested structural slots), excluding the
     * discard {@code _}. Lenient by design: over-collecting a name only suppresses a false "unknown
     * function", never causes one. Covers the single-file path where binders aren't yet desugared
     * to {@code let}s (DestructureResolver runs only in the linker).
     */
    private static void collectBinders(IrSort pattern, Set<String> into) {
        if (pattern instanceof IrSort.Structural s) {
            for (IrSort member : s.members().values()) {
                if (member instanceof IrSort.Named n) {
                    if (!n.name().equals("_")) into.add(n.name());
                } else {
                    collectBinders(member, into);
                }
            }
        }
    }
}

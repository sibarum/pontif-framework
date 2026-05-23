package sibarum.pontif.ir;

import java.util.HashMap;
import java.util.Map;

/**
 * Compile-time sort propagation, focused on the field-access path. Walks the
 * IR with a type environment (name → declared sort), and for every
 * {@link IrExpr.FieldAccess} whose base sort can be statically inferred as
 * {@link IrSort.Structural}, verifies the field name exists in that sort.
 *
 * <p>What's covered today:
 * <ul>
 *   <li>Bare variable bases — sort from let-binding / param / lambda param.</li>
 *   <li>Chained field accesses — recursive inference through structural sorts.</li>
 *   <li>Match scrutinees — within a structural-pattern branch, the scrutinee
 *       Var's sort is narrowed to the pattern's sort, so destructuring-emitted
 *       field accesses validate against the right shape.</li>
 * </ul>
 *
 * <p>What's not covered (skipped silently — runtime catches them):
 * <ul>
 *   <li>Field access whose base is a non-Var expression with no static sort
 *       (e.g., a literal {@code (record …)} or {@code (call …)} result).</li>
 *   <li>Record-shape vs. declared-sort mismatches at construction sites.</li>
 *   <li>Field access inside refinement predicates (handled by the symbolic
 *       layer; not walked from here).</li>
 * </ul>
 *
 * <p>Errors are {@link CompileException}s with the offending {@code FieldAccess}'s
 * origin.
 */
public final class SortChecker {

    private SortChecker() {}

    public static void check(IrModule module) throws CompileException {
        // Each function declaration body is checked with its params in scope.
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                Map<String, IrSort> typeEnv = new HashMap<>();
                for (IrParam p : fd.params()) {
                    typeEnv.put(p.name(), p.sort());
                }
                checkExpr(fd.body(), typeEnv);
            }
        }
        // Main runs in the empty environment.
        checkExpr(module.main(), new HashMap<>());
    }

    private static void checkExpr(IrExpr expr, Map<String, IrSort> typeEnv) throws CompileException {
        switch (expr) {
            case IrExpr.Lit l -> {}
            case IrExpr.Bool b -> {}
            case IrExpr.SelfRef s -> {}
            case IrExpr.Var v -> {}
            case IrExpr.BinOp op -> {
                checkExpr(op.left(), typeEnv);
                checkExpr(op.right(), typeEnv);
            }
            case IrExpr.LetIn l -> {
                checkExpr(l.value(), typeEnv);
                Map<String, IrSort> extended = new HashMap<>(typeEnv);
                extended.put(l.name(), l.declaredSort());
                checkExpr(l.body(), extended);
            }
            case IrExpr.Call c -> {
                for (IrExpr arg : c.args()) checkExpr(arg, typeEnv);
            }
            case IrExpr.Lambda lam -> {
                Map<String, IrSort> extended = new HashMap<>(typeEnv);
                for (IrParam p : lam.params()) extended.put(p.name(), p.sort());
                checkExpr(lam.body(), extended);
            }
            case IrExpr.Apply app -> {
                checkExpr(app.fn(), typeEnv);
                for (IrExpr a : app.args()) checkExpr(a, typeEnv);
            }
            case IrExpr.Match m -> {
                checkExpr(m.scrutinee(), typeEnv);
                for (IrExpr.MatchBranch b : m.branches()) {
                    Map<String, IrSort> branchEnv = new HashMap<>(typeEnv);
                    // Narrowing: within a structural branch, if the scrutinee
                    // is a Var, we know it satisfies the pattern's sort inside
                    // the branch. Override its env entry to the pattern.
                    if (m.scrutinee() instanceof IrExpr.Var v
                            && b.pattern() instanceof IrSort.Structural) {
                        branchEnv.put(v.name(), b.pattern());
                    }
                    checkExpr(b.result(), branchEnv);
                }
            }
            case IrExpr.Record r -> {
                for (IrExpr v : r.members().values()) checkExpr(v, typeEnv);
            }
            case IrExpr.FieldAccess fa -> {
                checkExpr(fa.base(), typeEnv);
                IrSort baseSort = inferSort(fa.base(), typeEnv);
                if (baseSort instanceof IrSort.Structural sp) {
                    if (!sp.members().containsKey(fa.fieldName())) {
                        throw new CompileException(
                                "Record of sort '" + sp.name() + "' has no field '"
                                        + fa.fieldName() + "'; available fields: "
                                        + sp.members().keySet(),
                                fa.origin());
                    }
                }
                // Skip otherwise — base sort unknown or non-structural. Runtime
                // catches genuine errors via RecordValue.get's diagnostic.
            }
        }
    }

    /**
     * Tries to determine the static sort of an expression. Returns null if the
     * sort can't be inferred from the local lexical context (which is fine —
     * sort propagation is best-effort).
     */
    private static IrSort inferSort(IrExpr expr, Map<String, IrSort> typeEnv) {
        return switch (expr) {
            case IrExpr.Var v -> typeEnv.get(v.name());
            case IrExpr.FieldAccess fa -> {
                IrSort base = inferSort(fa.base(), typeEnv);
                if (base instanceof IrSort.Structural sp) {
                    yield sp.members().get(fa.fieldName());
                }
                yield null;
            }
            default -> null;
        };
    }
}

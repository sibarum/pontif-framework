package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.DispatchTable;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.Simplifier;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.core.symbolic.algebra.ProofResult;
import sibarum.pontif.core.types.Sort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IrCompiler {

    private final Simplifier simplifier;

    public IrCompiler(Simplifier simplifier) {
        this.simplifier = simplifier;
    }

    public CompiledModule compile(IrModule module) {
        DispatchTable dispatch = new DispatchTable();
        Map<FunctionDecl, CompiledModule.CompiledFunction> functions = new LinkedHashMap<>();
        List<ProofResult> diagnostics = new ArrayList<>();

        for (IrStmt stmt : module.statements()) {
            switch (stmt) {
                case IrStmt.FunctionDecl fd -> compileFunctionDecl(fd, dispatch, functions, diagnostics);
            }
        }

        return new CompiledModule(module.name(), dispatch, functions, module.main(), diagnostics);
    }

    private void compileFunctionDecl(
            IrStmt.FunctionDecl fd,
            DispatchTable dispatch,
            Map<FunctionDecl, CompiledModule.CompiledFunction> functions,
            List<ProofResult> diagnostics) {
        List<FunctionDecl.Param> params = new ArrayList<>();
        for (IrParam p : fd.params()) {
            params.add(new FunctionDecl.Param(p.name(), compileSort(p.sort())));
        }
        Sort returnSort = compileSort(fd.returnSort());
        FunctionDecl decl = FunctionDecl.declaration(fd.name(), params, returnSort);
        dispatch.register(decl);

        functions.put(
                decl,
                new CompiledModule.CompiledFunction(decl, fd.body(), fd.params(), ProofResult.passed()));
        diagnostics.add(ProofResult.passed());
    }

    public Sort compileSort(IrSort sort) {
        return switch (sort) {
            case IrSort.Named n -> Sort.of(n.name());
            case IrSort.Refined r -> Sort.refined(r.name(), compileSymExpr(r.predicate()));
        };
    }

    public SymExpr compileSymExpr(IrExpr expr) {
        return switch (expr) {
            case IrExpr.Lit l -> SymExpr.lit(l.value());
            case IrExpr.Bool b -> SymExpr.bool(b.value());
            case IrExpr.Var v -> SymExpr.var(v.name());
            case IrExpr.SelfRef s -> SymExpr.self();
            case IrExpr.BinOp op -> compileBinOp(op);
            case IrExpr.LetIn l -> {
                // LetIn in a refinement predicate context: rare. Encode as
                // App(Lam(name, body), value) so substitution machinery handles it.
                yield SymExpr.app(
                        SymExpr.lam(l.name(), compileSymExpr(l.body())),
                        compileSymExpr(l.value()));
            }
            case IrExpr.Call c -> throw new UnsupportedOperationException(
                    "Function calls inside refinement predicates are not yet supported (call to '"
                            + c.functionName() + "')");
            case IrExpr.Lambda lambda -> throw new UnsupportedOperationException(
                    "Lambdas inside refinement predicates are not yet supported");
            case IrExpr.Apply apply -> throw new UnsupportedOperationException(
                    "Function applications inside refinement predicates are not yet supported");
        };
    }

    private SymExpr compileBinOp(IrExpr.BinOp op) {
        SymExpr l = compileSymExpr(op.left());
        SymExpr r = compileSymExpr(op.right());
        return switch (op.op()) {
            case ADD -> SymExpr.add(l, r);
            case MUL -> SymExpr.mul(l, r);
            case SUB -> SymExpr.add(l, SymExpr.mul(SymExpr.lit(-1), r));
            case LT -> SymExpr.cmp(l, SymExpr.CmpOp.LT, r);
            case LE -> SymExpr.cmp(l, SymExpr.CmpOp.LE, r);
            case GT -> SymExpr.cmp(l, SymExpr.CmpOp.GT, r);
            case GE -> SymExpr.cmp(l, SymExpr.CmpOp.GE, r);
            case EQ -> SymExpr.cmp(l, SymExpr.CmpOp.EQ, r);
            case NE -> SymExpr.cmp(l, SymExpr.CmpOp.NE, r);
        };
    }

    public Simplifier simplifier() {
        return simplifier;
    }
}

package sibarum.pontif.ir;

import sibarum.pontif.core.symbolic.DispatchTable;
import sibarum.pontif.core.symbolic.FunctionDecl;
import sibarum.pontif.core.symbolic.algebra.ProofResult;

import java.util.List;
import java.util.Map;

public record CompiledModule(
        String name,
        DispatchTable dispatch,
        Map<FunctionDecl, CompiledFunction> functions,
        IrExpr main,
        List<ProofResult> diagnostics) {

    public CompiledModule {
        functions = Map.copyOf(functions);
        diagnostics = List.copyOf(diagnostics);
    }

    public record CompiledFunction(
            FunctionDecl decl,
            IrExpr body,
            List<IrParam> params,
            ProofResult verification) {}
}

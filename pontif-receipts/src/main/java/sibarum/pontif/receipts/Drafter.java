package sibarum.pontif.receipts;

import sibarum.pontif.core.symbolic.Substitute;
import sibarum.pontif.core.symbolic.SymExpr;
import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrCompiler;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrParam;
import sibarum.pontif.ir.IrStmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pontif's built-in deterministic receipt-graph builder. Given an
 * {@link IrModule}, walks each function declaration and transcribes the
 * body into a {@link ReceiptGraph}. No reasoning happens here — only
 * transcription. Standalone and stateless; the same module always drafts
 * to the same graph.
 *
 * <p>The drafter is the only component that touches {@link IrModule};
 * downstream consumers (issuers, notary) operate on the receipt-graph,
 * not on IR.
 *
 * <p><b>Current slice (vertical):</b> non-recursive arithmetic bodies — no
 * match arms, no recursive or external calls. The function's body is
 * transcribed into a single unconditional {@link Branch} carrying one
 * {@link InitialReceipt} of shape {@code r_0 = body}, where the body's
 * parameter references have been renamed to their call-instance form
 * ({@code n} → {@code n_0}).
 *
 * <p><b>Subsequent slices</b> will layer in:
 * <ul>
 *   <li>Match arms → one {@link Branch} per arm with guard + initial receipt
 *       for the arm body.
 *   <li>Recursive calls → {@link CallRef} as a back-reference (by name) to
 *       the function's own root, with a fresh {@link Var} per call instance.
 *   <li>External calls → {@link CallRef} to other functions in the module.
 * </ul>
 *
 * <p>See {@code docs/receipt-graph.md} for the design and worked example.
 */
public final class Drafter {

    private Drafter() {}

    /** Drafts a receipt-graph for every {@link IrStmt.FunctionDecl} in the module. */
    public static ReceiptGraph draft(IrModule module) throws CompileException {
        Map<String, Node> roots = new LinkedHashMap<>();
        for (IrStmt stmt : module.statements()) {
            if (stmt instanceof IrStmt.FunctionDecl fd) {
                roots.put(fd.name(), draftFunction(fd));
            }
        }
        return new ReceiptGraph(roots);
    }

    /**
     * Drafts a single function as call instance 0. Each parameter {@code n}
     * becomes the symbolic variable {@code n_0}; the result variable is
     * {@code r_0}. Body references to params are renamed accordingly so the
     * receipt reads in terms of the call-instance variables.
     */
    private static Node draftFunction(IrStmt.FunctionDecl fd) throws CompileException {
        int callIndex = 0;

        // Build params + a rename map (body's IrExpr.Var("n") → SymExpr.Var("n_0")).
        List<Param> params = new ArrayList<>(fd.params().size());
        Map<String, SymExpr> renameBindings = new HashMap<>();
        for (IrParam p : fd.params()) {
            String varName = p.name() + "_" + callIndex;
            params.add(new Param(varName, IrCompiler.compileSort(p.sort())));
            renameBindings.put(p.name(), SymExpr.var(varName));
        }

        Var resultVar = new Var(
                "r_" + callIndex,
                IrCompiler.compileSort(fd.returnSort()));

        // Lift the body to a SymExpr, then rename param references.
        SymExpr bodySym = IrCompiler.compileSymExpr(fd.body());
        SymExpr renamedBody = Substitute.apply(bodySym, renameBindings);

        // Body equation: result_var == body (with renamed param refs).
        InitialReceipt bodyReceipt = new InitialReceipt(
                SymExpr.cmp(SymExpr.var(resultVar.name()), SymExpr.CmpOp.EQ, renamedBody));

        Branch branch = new Branch(
                Optional.empty(),
                List.of(bodyReceipt),
                List.of());

        return new Node(fd.name(), params, resultVar, List.of(branch));
    }
}

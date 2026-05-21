package sibarum.pontif.ir;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import sibarum.pontif.ast.binary.Add;
import sibarum.pontif.ast.binary.Cmp;
import sibarum.pontif.ast.binary.Mul;
import sibarum.pontif.ast.binary.Sub;
import sibarum.pontif.ast.bind.Let;
import sibarum.pontif.ast.bind.Var;
import sibarum.pontif.ast.func.CallNode;
import sibarum.pontif.ast.func.FunctionEntryNode;
import sibarum.pontif.ast.func.FunctionRegistry;
import sibarum.pontif.ast.literal.Bool;
import sibarum.pontif.ast.literal.IntLiteral;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.PontifRootNode;
import sibarum.pontif.core.Resolver;
import sibarum.pontif.core.symbolic.FunctionDecl;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TruffleLowering {

    private final IrCompiler compiler;

    public TruffleLowering(IrCompiler compiler) {
        this.compiler = compiler;
    }

    public TruffleProgram lower(CompiledModule module) {
        FunctionRegistry registry = new FunctionRegistry();

        // Lower each function's body and create a CallTarget for it. CallNodes inside
        // each body reference the (mutable) registry; lookups happen at execute time, so
        // mutual recursion works as long as everything is registered before main runs.
        Map<FunctionDecl, CallTarget> targets = new LinkedHashMap<>();
        for (Map.Entry<FunctionDecl, CompiledModule.CompiledFunction> entry : module.functions().entrySet()) {
            FunctionDecl decl = entry.getKey();
            CompiledModule.CompiledFunction func = entry.getValue();

            Resolver resolver = new Resolver();
            int[] paramSlots = new int[func.params().size()];
            for (int i = 0; i < func.params().size(); i++) {
                String paramName = func.params().get(i).name();
                paramSlots[i] = resolver.allocateSlot(paramName);
                resolver.pushScope(paramName, paramSlots[i]);
            }

            PontifNode body = lowerExpr(func.body(), module, registry);
            body.resolve(resolver);

            FunctionEntryNode entryNode = new FunctionEntryNode(paramSlots, body);
            FrameDescriptor descriptor = resolver.build();
            PontifRootNode root = new PontifRootNode(null, descriptor, entryNode);
            targets.put(decl, root.getCallTarget());
        }

        for (Map.Entry<FunctionDecl, CallTarget> e : targets.entrySet()) {
            registry.register(e.getKey(), e.getValue());
        }

        // Lower main
        Resolver mainResolver = new Resolver();
        PontifNode mainBody = lowerExpr(module.main(), module, registry);
        mainBody.resolve(mainResolver);
        FrameDescriptor mainDesc = mainResolver.build();
        PontifRootNode mainRoot = new PontifRootNode(null, mainDesc, mainBody);
        return new TruffleProgram(mainRoot.getCallTarget(), registry);
    }

    private PontifNode lowerExpr(IrExpr expr, CompiledModule module, FunctionRegistry registry) {
        PontifNode node = switch (expr) {
            case IrExpr.Lit l -> IntLiteral.of(l.value());
            case IrExpr.Bool b -> Bool.of(b.value());
            case IrExpr.Var v -> Var.of(v.name());
            case IrExpr.SelfRef s -> throw new IllegalStateException(
                    "Self has no runtime meaning — it's a typing-context placeholder; "
                            + "should not appear in executable expressions");
            case IrExpr.BinOp op -> lowerBinOp(op, module, registry);
            case IrExpr.LetIn l -> Let.of(
                    l.name(),
                    lowerExpr(l.value(), module, registry),
                    lowerExpr(l.body(), module, registry));
            case IrExpr.Call c -> lowerCall(c, module, registry);
            case IrExpr.Lambda lambda -> throw new UnsupportedOperationException(
                    "Lambda lowering to Truffle is not yet implemented; will land in the paired Truffle slice. "
                            + "Origin: " + lambda.origin());
            case IrExpr.Apply apply -> throw new UnsupportedOperationException(
                    "Apply lowering to Truffle is not yet implemented; will land in the paired Truffle slice. "
                            + "Origin: " + apply.origin());
        };
        node.withOrigin(expr.origin());
        return node;
    }

    private PontifNode lowerBinOp(IrExpr.BinOp op, CompiledModule module, FunctionRegistry registry) {
        PontifNode l = lowerExpr(op.left(), module, registry);
        PontifNode r = lowerExpr(op.right(), module, registry);
        return switch (op.op()) {
            case ADD -> Add.of(l, r);
            case MUL -> Mul.of(l, r);
            case SUB -> Sub.of(l, r);
            case LT -> Cmp.of(l, r, Cmp.Op.LT);
            case LE -> Cmp.of(l, r, Cmp.Op.LE);
            case GT -> Cmp.of(l, r, Cmp.Op.GT);
            case GE -> Cmp.of(l, r, Cmp.Op.GE);
            case EQ -> Cmp.of(l, r, Cmp.Op.EQ);
            case NE -> Cmp.of(l, r, Cmp.Op.NE);
        };
    }

    private PontifNode lowerCall(IrExpr.Call call, CompiledModule module, FunctionRegistry registry) {
        PontifNode[] argNodes = new PontifNode[call.args().size()];
        for (int i = 0; i < call.args().size(); i++) {
            argNodes[i] = lowerExpr(call.args().get(i), module, registry);
        }
        return CallNode.of(
                call.functionName(),
                argNodes,
                module.dispatch(),
                compiler.simplifier(),
                registry);
    }
}

package sibarum.pontif.ir;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import sibarum.pontif.ast.binary.Add;
import sibarum.pontif.ast.binary.Div;
import sibarum.pontif.ast.binary.Mod;
import sibarum.pontif.ast.binary.Pow;
import sibarum.pontif.ast.binary.And;
import sibarum.pontif.ast.binary.Cmp;
import sibarum.pontif.ast.binary.Mul;
import sibarum.pontif.ast.binary.Or;
import sibarum.pontif.ast.binary.Sub;
import sibarum.pontif.ast.bind.Let;
import sibarum.pontif.ast.bind.Var;
import sibarum.pontif.ast.func.CallNode;
import sibarum.pontif.ast.func.FunctionEntryNode;
import sibarum.pontif.ast.func.FunctionRegistry;
import sibarum.pontif.ast.lambda.ApplyNode;
import sibarum.pontif.ast.lambda.LambdaNode;
import sibarum.pontif.ast.literal.Bool;
import sibarum.pontif.ast.literal.DecimalLiteral;
import sibarum.pontif.ast.literal.IntLiteral;
import sibarum.pontif.ast.match.MatchNode;
import sibarum.pontif.ast.record.FieldAccessNode;
import sibarum.pontif.ast.record.RecordNode;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.PontifRootNode;
import sibarum.pontif.core.Resolver;
import sibarum.pontif.core.symbolic.FunctionDecl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

        // Lower main — prefixed with the force chain: every top-level let
        // evaluates before main (declaration order), so its claims are
        // notarized whether or not anything references it. Built here, at
        // lowering time, so the chain never enters the module IR (ledgers
        // and receipts never see it). Mirrors IrInterpreter.eval.
        IrExpr mainExpr = module.main();
        List<String> lets = module.topLevelLets();
        for (int i = lets.size() - 1; i >= 0; i--) {
            mainExpr = new IrExpr.LetIn(
                    "__force$" + i,
                    null,
                    new IrExpr.Call(lets.get(i), List.of(), sibarum.pontif.core.Origin.NONE),
                    mainExpr,
                    sibarum.pontif.core.Origin.NONE);
        }
        Resolver mainResolver = new Resolver();
        PontifNode mainBody = lowerExpr(mainExpr, module, registry);
        mainBody.resolve(mainResolver);
        FrameDescriptor mainDesc = mainResolver.build();
        PontifRootNode mainRoot = new PontifRootNode(null, mainDesc, mainBody);
        return new TruffleProgram(mainRoot.getCallTarget(), registry);
    }

    /**
     * Lower one expression to its execution-AST root for display/inspection —
     * the exact node tree {@link #lowerExpr} builds, minus the CallTarget /
     * RootNode / FunctionEntryNode execution wrappers and slot resolution
     * (structure only; not runnable). Used by the IR/AST inspector report.
     */
    public PontifNode lowerForDisplay(IrExpr expr, CompiledModule module) {
        return lowerExpr(expr, module, new FunctionRegistry());
    }

    private PontifNode lowerExpr(IrExpr expr, CompiledModule module, FunctionRegistry registry) {
        PontifNode node = switch (expr) {
            case IrExpr.Lit l -> IntLiteral.of(l.value());
            case IrExpr.Dec d -> DecimalLiteral.of(d.value());
            case IrExpr.Chr c -> sibarum.pontif.ast.literal.CharLiteral.of(c.codePoint());
            case IrExpr.Str s -> sibarum.pontif.ast.literal.StringLiteral.of(s.value());
            case IrExpr.DispatchRef d -> {
                List<sibarum.pontif.core.types.Sort> keys = new ArrayList<>(d.keySorts().size());
                try {
                    for (IrSort k : d.keySorts()) keys.add(IrCompiler.compileSort(k));
                } catch (CompileException ce) {
                    throw new IllegalStateException(
                            "Metareference key sort failed to compile (should have been "
                                    + "caught at sort-check): " + ce.getMessage(), ce);
                }
                yield sibarum.pontif.ast.literal.DispatchRefLiteral.of(
                        sibarum.pontif.core.types.Metaref.of(d.functionName(), keys, false));
            }
            case IrExpr.Bool b -> Bool.of(b.value());
            case IrExpr.Var v -> Var.of(v.name());
            case IrExpr.SelfRef s -> throw new IllegalStateException(
                    "Self has no runtime meaning — it's a typing-context placeholder; "
                            + "should not appear in executable expressions");
            case IrExpr.BinOp op -> lowerBinOp(op, module, registry);
            case IrExpr.LetIn l -> {
                PontifNode valueNode = lowerExpr(l.value(), module, registry);
                // Binding claim kept by ConstructionGate (UNKNOWN verdict) —
                // wrap the value so it is judged at bind, mirroring the
                // IrInterpreter path and the record-member check above.
                if (l.claim() != null) {
                    valueNode = sibarum.pontif.ast.record.ConstructionCheckNode.of(
                            valueNode, module.sortFor(l.claim()),
                            l.name(), compiler.simplifier());
                    valueNode.withOrigin(l.value().origin());
                }
                yield Let.of(
                        l.name(),
                        valueNode,
                        lowerExpr(l.body(), module, registry));
            }
            case IrExpr.Call c -> lowerCall(c, module, registry);
            case IrExpr.Lambda lambda -> lowerLambda(lambda, module, registry);
            case IrExpr.Apply apply -> lowerApply(apply, module, registry);
            case IrExpr.Match m -> lowerMatch(m, module, registry);
            case IrExpr.Record r -> lowerRecord(r, module, registry);
            case IrExpr.FieldAccess fa -> FieldAccessNode.of(lowerExpr(fa.base(), module, registry), fa.fieldName());
            case IrExpr.MethodCall mc -> throw MethodResolver.unresolved(mc, "TruffleLowering");
            // REVISIT (docs/iteration.md §10): the Truffle path does not lower the
            // iteration construct yet; the IrInterpreter path is slice 1.
            case IrExpr.Iterate it -> throw new UnsupportedOperationException(
                    "Iterate: Truffle lowering not yet implemented (docs/iteration.md §10)");
            // The closed built-in Int→Decimal tower — the one cast the compiler
            // inserts implicitly (NumericCoercion). Other cast targets (String
            // render, user coercions) remain interpreter-only for now.
            case IrExpr.Cast cast when "Decimal".equals(cast.targetSort().baseName()) ->
                    sibarum.pontif.ast.coerce.IntToDecimalNode.of(
                            lowerExpr(cast.value(), module, registry));
            case IrExpr.Cast cast -> throw new UnsupportedOperationException(
                    "Cast (Type:value): Truffle lowering not yet implemented — "
                            + "the interpreter path is slice 1");
            // The event substrate (emit) runs on the interpreter path (docs/events.md);
            // the Truffle backend does not lower it yet.
            case IrExpr.Emit em -> throw new UnsupportedOperationException(
                    "emit: Truffle lowering not yet implemented — the interpreter path is slice 1b");
        };
        node.withOrigin(expr.origin());
        return node;
    }

    private PontifNode lowerRecord(IrExpr.Record record, CompiledModule module, FunctionRegistry registry) {
        List<String> fieldNames = new ArrayList<>(record.members().size());
        List<PontifNode> valueNodes = new ArrayList<>(record.members().size());
        for (Map.Entry<String, IrExpr> e : record.members().entrySet()) {
            fieldNames.add(e.getKey());
            PontifNode valueNode = lowerExpr(e.getValue(), module, registry);
            // Construction-claim checks stamped by ConstructionGate — wrap the
            // member so the value is judged at construction, mirroring the
            // IrInterpreter path.
            IrSort checkSort = record.runtimeChecks().get(e.getKey());
            if (checkSort != null) {
                valueNode = sibarum.pontif.ast.record.ConstructionCheckNode.of(
                        valueNode, module.sortFor(checkSort),
                        record.typeName() + "." + e.getKey(), compiler.simplifier());
                valueNode.withOrigin(e.getValue().origin());
            }
            valueNodes.add(valueNode);
        }
        // A native constructor builds its carrier scalar instead of a
        // RecordValue — the registry's construct map is injected here (the
        // ast module sits below the registry).
        NativeConstructors.Entry nativeCons = NativeConstructors.get(record.typeName());
        if (nativeCons != null) {
            return sibarum.pontif.ast.record.NativeConstructNode.of(
                    valueNodes, (values, origin) -> nativeCons.construct().apply(values, origin));
        }
        return RecordNode.of(record.typeName(), fieldNames, valueNodes);
    }

    private PontifNode lowerMatch(IrExpr.Match match, CompiledModule module, FunctionRegistry registry) {
        PontifNode scrutinee = lowerExpr(match.scrutinee(), module, registry);
        List<MatchNode.Branch> branches = new ArrayList<>(match.branches().size());
        for (IrExpr.MatchBranch b : match.branches()) {
            branches.add(MatchNode.Branch.of(
                    module.sortFor(b.pattern()),
                    lowerExpr(b.result(), module, registry)));
        }
        return MatchNode.of(scrutinee, compiler.simplifier(), branches);
    }

    private PontifNode lowerBinOp(IrExpr.BinOp op, CompiledModule module, FunctionRegistry registry) {
        PontifNode l = lowerExpr(op.left(), module, registry);
        PontifNode r = lowerExpr(op.right(), module, registry);
        return switch (op.op()) {
            case ADD -> Add.of(l, r);
            case MUL -> Mul.of(l, r);
            case SUB -> Sub.of(l, r);
            case DIV -> Div.of(l, r);
            case MOD -> Mod.of(l, r);
            case POW -> Pow.of(l, r);
            case LT -> Cmp.of(l, r, Cmp.Op.LT);
            case LE -> Cmp.of(l, r, Cmp.Op.LE);
            case GT -> Cmp.of(l, r, Cmp.Op.GT);
            case GE -> Cmp.of(l, r, Cmp.Op.GE);
            case EQ -> Cmp.of(l, r, Cmp.Op.EQ);
            case NE -> Cmp.of(l, r, Cmp.Op.NE);
            case APPROX -> Cmp.of(l, r, Cmp.Op.APPROX);
            case AND -> And.of(l, r);
            case OR -> Or.of(l, r);
        };
    }

    private PontifNode lowerLambda(IrExpr.Lambda lambda, CompiledModule module, FunctionRegistry registry) {
        // Captures = body's free vars minus the lambda's own params.
        LinkedHashSet<String> freeVars = IrFreeVars.freeVars(lambda.body());
        for (IrParam p : lambda.params()) {
            freeVars.remove(p.name());
        }
        List<String> captureNames = new ArrayList<>(freeVars);

        // Build a Resolver for the lambda body. Frame layout:
        //   [capture_0, ..., capture_{N-1}, param_0, ..., param_{M-1}, locals...]
        // FunctionEntryNode unpacks frame.getArguments() = [captures..., args...] into
        // these slots in order, so the body's Var lookups resolve to the right slots.
        Resolver bodyResolver = new Resolver();
        int[] entrySlots = new int[captureNames.size() + lambda.params().size()];
        int slotIndex = 0;
        for (String capName : captureNames) {
            int slot = bodyResolver.allocateSlot(capName);
            bodyResolver.pushScope(capName, slot);
            entrySlots[slotIndex++] = slot;
        }
        for (IrParam p : lambda.params()) {
            int slot = bodyResolver.allocateSlot(p.name());
            bodyResolver.pushScope(p.name(), slot);
            entrySlots[slotIndex++] = slot;
        }

        PontifNode body = lowerExpr(lambda.body(), module, registry);
        body.resolve(bodyResolver);

        FunctionEntryNode entryNode = new FunctionEntryNode(entrySlots, body);
        FrameDescriptor descriptor = bodyResolver.build();
        PontifRootNode root = new PontifRootNode(null, descriptor, entryNode);
        CallTarget callTarget = root.getCallTarget();

        return LambdaNode.of(callTarget, captureNames, lambda.params().size());
    }

    private PontifNode lowerApply(IrExpr.Apply apply, CompiledModule module, FunctionRegistry registry) {
        PontifNode fnNode = lowerExpr(apply.fn(), module, registry);
        PontifNode[] argNodes = new PontifNode[apply.args().size()];
        for (int i = 0; i < apply.args().size(); i++) {
            argNodes[i] = lowerExpr(apply.args().get(i), module, registry);
        }
        return ApplyNode.of(fnNode, argNodes);
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

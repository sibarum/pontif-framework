package sibarum.pontif.parser;

import org.junit.jupiter.api.Test;
import sibarum.pontif.ir.*;

class ImplProbeTest {
    @Test
    void probe() throws Exception {
        String src = "trait AlgExpr{}\n"
            + "trait Algebraic{ast:AlgExpr, evalAt(x:Decimal):Decimal}\n"
            + "assign trait AlgebraicDispatch:Algebraic {\n"
            + "  ast:AlgExpr -> Const(0.0)\n"
            + "  evalAt(x:Decimal):Decimal -> x\n"
            + "}\n0\n";
        IrModule m = PontifParser.parseModule(src, "probe.ptf");
        for (IrStmt s : m.statements()) {
            if (s instanceof IrStmt.TraitImpl ti) {
                System.out.println(">>> IMPL " + ti.typeName() + ":" + ti.traitName()
                    + " methods=" + ti.methods().stream().map(IrStmt.FunctionDecl::name).toList()
                    + " attrs=" + ti.attributeProducers().stream().map(IrStmt.FunctionDecl::name).toList());
            } else if (s instanceof IrStmt.TypeAlias ta && ta.sort() instanceof IrSort.Trait t) {
                System.out.println(">>> TRAIT " + t.name() + " methods=" + t.methods().keySet()
                    + " attrs=" + t.attributes().keySet());
            }
        }
    }
}

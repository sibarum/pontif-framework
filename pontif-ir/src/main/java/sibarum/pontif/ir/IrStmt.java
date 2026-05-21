package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.util.List;

public sealed interface IrStmt permits IrStmt.FunctionDecl {

    Origin origin();

    static FunctionDecl functionDecl(
            String name,
            List<IrParam> params,
            IrSort returnSort,
            IrExpr body) {
        return new FunctionDecl(name, params, returnSort, body, Origin.NONE);
    }

    record FunctionDecl(
            String name,
            List<IrParam> params,
            IrSort returnSort,
            IrExpr body,
            Origin origin) implements IrStmt {
        public FunctionDecl {
            params = List.copyOf(params);
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Function name must be non-empty");
            }
        }
    }
}

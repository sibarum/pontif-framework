package sibarum.pontif.ir;

import java.util.List;

public record IrModule(String name, List<IrStmt> statements, IrExpr main) {

    public IrModule {
        statements = List.copyOf(statements);
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Module name must be non-empty");
        }
    }
}

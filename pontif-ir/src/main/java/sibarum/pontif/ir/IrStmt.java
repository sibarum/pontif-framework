package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

import java.util.List;

public sealed interface IrStmt permits IrStmt.FunctionDecl, IrStmt.TypeAlias, IrStmt.NoOp {

    Origin origin();

    static FunctionDecl functionDecl(
            String name,
            List<IrParam> params,
            IrSort returnSort,
            IrExpr body) {
        return new FunctionDecl(name, params, returnSort, body, Origin.NONE);
    }

    static TypeAlias typeAlias(String name, IrSort sort) {
        return new TypeAlias(name, sort, Origin.NONE);
    }

    static NoOp noOp(String label) {
        return new NoOp(label, Origin.NONE);
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

    /**
     * Binds a name to a sort. Resolved away by {@link AliasResolver} before
     * the rest of the compilation pipeline runs — every {@link IrSort.Named}
     * whose name matches a {@code TypeAlias} declaration gets substituted
     * with the aliased sort, transitively.
     */
    record TypeAlias(String name, IrSort sort, Origin origin) implements IrStmt {
        public TypeAlias {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Type alias name must be non-empty");
            }
            if (sort == null) {
                throw new IllegalArgumentException("Type alias sort must be non-null");
            }
        }
    }

    /**
     * Placeholder for syntactic forms the parser recognizes but the IR
     * doesn't yet support (e.g., {@code requires}, {@code exports}, spec-only
     * functions, {@code method} declarations, top-level {@code let} without a
     * body). Carries a human-readable {@code label} of the original form for
     * diagnostics; otherwise contributes nothing to compilation or execution.
     */
    record NoOp(String label, Origin origin) implements IrStmt {
        public NoOp {
            if (label == null) {
                throw new IllegalArgumentException("NoOp label must be non-null");
            }
        }
    }
}

package sibarum.pontif.core.symbolic;

import sibarum.pontif.core.types.Sort;

import java.util.List;

public record FunctionDecl(
        String name,
        List<Param> parameters,
        Sort returnSort,
        SymExpr body) {

    public FunctionDecl {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Function name must be non-empty");
        }
        if (returnSort == null) {
            throw new IllegalArgumentException("Return sort must be non-null");
        }
        parameters = List.copyOf(parameters);
    }

    public record Param(String name, Sort sort) {
        public Param {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Parameter name must be non-empty");
            }
            if (sort == null) {
                throw new IllegalArgumentException("Parameter sort must be non-null");
            }
        }
    }

    public static FunctionDecl declaration(String name, List<Param> parameters, Sort returnSort) {
        return new FunctionDecl(name, parameters, returnSort, null);
    }

    public static FunctionDecl definition(String name, List<Param> parameters, Sort returnSort, SymExpr body) {
        if (body == null) {
            throw new IllegalArgumentException("Definition body must be non-null; use declaration() for bodyless functions");
        }
        return new FunctionDecl(name, parameters, returnSort, body);
    }

    public boolean hasBody() {
        return body != null;
    }
}

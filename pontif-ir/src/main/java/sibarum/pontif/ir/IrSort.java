package sibarum.pontif.ir;

import sibarum.pontif.core.Origin;

public sealed interface IrSort permits IrSort.Named, IrSort.Refined {

    Origin origin();

    static Named named(String name) {
        return new Named(name, Origin.NONE);
    }

    static Refined refined(String name, IrExpr predicate) {
        return new Refined(name, predicate, Origin.NONE);
    }

    record Named(String name, Origin origin) implements IrSort {}

    record Refined(String name, IrExpr predicate, Origin origin) implements IrSort {}
}

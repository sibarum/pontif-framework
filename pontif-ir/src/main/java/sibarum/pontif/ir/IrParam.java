package sibarum.pontif.ir;

public record IrParam(String name, IrSort sort) {

    public IrParam {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Parameter name must be non-empty");
        }
        if (sort == null) {
            throw new IllegalArgumentException("Parameter sort must be non-null");
        }
    }
}

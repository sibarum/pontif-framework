package sibarum.pontif.supirvast;

import dev.supirvast.vastir.type.Type;
import sibarum.pontif.ir.IrSort;

/**
 * Lowers a Pontif {@link IrSort} to a SuperVast scalar {@link Type}, for the Int-only v1 subset.
 *
 * <p>The honest maps: {@code Int} → {@code int64} (Pontif's Int is 64-bit, so int64 loses nothing; int32 would
 * truncate, which would be a lie), and {@code Bool} → {@code bool}. A refinement {@code [Int:@>0]} lowers to its
 * base machine type — a refinement is a narrowing of the same representation, proven Pontif-side, so the GPU
 * value is still an honest {@code int64}; the predicate rides along as a Pontif obligation and does not change
 * the wire type. Everything else (Decimal/Char/String, structs, traits, unions, function/dispatch sorts, and
 * parametric applications such as {@code Array[Int]}) fails closed with a {@link LoweringError}; buffer element
 * types for kernel columns are resolved separately by the kernel lowering, not here.
 */
public final class SortLowering {

    /** Lowers a scalar value sort to its GPU wire type, or throws {@link LoweringError} if v1 cannot carry it. */
    public Type lowerScalar(IrSort sort) {
        return switch (sort) {
            case IrSort.Named named -> {
                if (!named.typeArgs().isEmpty()) {
                    throw LoweringError.unsupportedScalar(sort, named.name() + "[...]");
                }
                yield primitive(sort, named.name());
            }
            case IrSort.Refined refined -> {
                if (!refined.typeArgs().isEmpty()) {
                    throw LoweringError.unsupportedScalar(sort, refined.name() + "[...]");
                }
                // A refinement narrows its base without changing the machine representation.
                yield primitive(sort, refined.name());
            }
            case IrSort.Structural s -> throw LoweringError.aggregateSort(sort, "Struct '" + s.name() + "'");
            case IrSort.Trait t -> throw LoweringError.aggregateSort(sort, "Trait '" + t.name() + "'");
            case IrSort.Union ignored -> throw LoweringError.aggregateSort(sort, "Union");
            case IrSort.Intersection ignored -> throw LoweringError.aggregateSort(sort, "Intersection");
            case IrSort.CallSig c -> throw LoweringError.aggregateSort(
                    sort, c.typeName() + " call signature");
        };
    }

    private Type primitive(IrSort sort, String baseName) {
        return switch (baseName) {
            case "Int" -> Type.int64();
            case "Bool" -> Type.BOOL;
            default -> throw LoweringError.unsupportedScalar(sort, baseName);
        };
    }
}

package sibarum.pontif.types;

import org.junit.jupiter.api.Test;
import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrSort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential harness (roadmap §4.1, "the license to delete"): drives the target
 * {@link Assignability} engine and the legacy {@link CoercionResolver} over the same
 * corpus of {@code (from, to)} sort pairs and compares their one shared question —
 * <b>is this an implicitly-legal binding (no explicit cast required)?</b>
 *
 * <ul>
 *   <li>Where they <b>AGREE</b>, migrating the call site onto {@code Assignability} is safe.</li>
 *   <li>The <b>DIVERGENCES</b> are the documented gaps ({@code KNOWN_DIVERGENCES}, roadmap §4.2)
 *       that must be closed — or the target behavior consciously accepted — before the legacy
 *       copy is deleted.</li>
 * </ul>
 *
 * A divergence NOT in {@code KNOWN_DIVERGENCES} fails the test, so drift on either engine is
 * caught. This is the baseline the whole C3 migration deletes against.
 */
class AssignabilityDifferentialTest {

    private static final IrSort INT = IrSort.named("Int");
    private static final IrSort DECIMAL = IrSort.named("Decimal");

    private static IrSort.Structural shape(String name, IrSort base, String... fields) {
        Map<String, IrSort> m = new LinkedHashMap<>();
        for (String f : fields) m.put(f, INT);
        return new IrSort.Structural(name, m, base, Origin.NONE);
    }

    /** Point {x,y}; Point3D:[Point]{x,y,z} (demotion); A{v} & B{v} (same-structure siblings);
     *  AnyNumber = union(Int,Decimal) transparent alias; Showable a trait Point3D satisfies. */
    private static TypeCatalog catalog() {
        TypeCatalog cat = new TypeCatalog();
        cat.register("Point", new TypeInfo.Struct(shape("Point", null, "x", "y")));
        cat.register("Point3D", new TypeInfo.Struct(shape("Point3D", IrSort.named("Point"), "x", "y", "z")));
        cat.register("A", new TypeInfo.Struct(shape("A", null, "v")));
        cat.register("B", new TypeInfo.Struct(shape("B", null, "v")));
        cat.register("AnyNumber", new TypeInfo.Alias(IrSort.union(List.of(INT, DECIMAL))));
        cat.register("Showable", new TypeInfo.Trait(IrSort.trait("Showable", Map.of())));
        return cat;
    }

    private static final Map<String, Set<String>> TRAIT_IMPLS = Map.of("Point3D", Set.of("Showable"));

    private static boolean legalAssignability(IrSort from, IrSort to) {
        Assignability.Assignment v =
                Assignability.assign(from, to, AssignabilityContext.of(catalog(), TRAIT_IMPLS));
        return v == Assignability.Assignment.EXACT || v == Assignability.Assignment.WIDEN;
    }

    private static boolean legalCoercion(IrSort from, IrSort to) {
        Coercion c = CoercionResolver.resolve(from, to, new CoercionContext(catalog()));
        return !(c instanceof Coercion.Mismatch);
    }

    private record Pair(String label, IrSort from, IrSort to) {}

    private static IrSort n(String name) {
        return IrSort.named(name);
    }

    private static List<Pair> corpus() {
        return List.of(
                new Pair("exact: Point -> Point", n("Point"), n("Point")),
                new Pair("demote: Point3D -> Point", n("Point3D"), n("Point")),
                new Pair("promote(illegal): Point -> Point3D", n("Point"), n("Point3D")),
                new Pair("siblings: A -> B", n("A"), n("B")),
                new Pair("trait upcast: Point3D -> Showable", n("Point3D"), n("Showable")),
                new Pair("trait (non-impl): Point -> Showable", n("Point"), n("Showable")),
                new Pair("union member: Int -> AnyNumber", INT, n("AnyNumber")),
                new Pair("union non-member: Point -> AnyNumber", n("Point"), n("AnyNumber")),
                new Pair("mismatch: Point -> Int", n("Point"), INT),
                new Pair("int->decimal embed: Int -> Decimal", INT, DECIMAL));
    }

    /**
     * Divergences we know about (label -> reason), from the first run. Two kinds:
     * <ul>
     *   <li><b>engine WEAKER — a gap to CLOSE</b> (roadmap §4.2): the engine rejects something the
     *       legacy accepts because a capability is missing.</li>
     *   <li><b>engine STRICTER — accepted, the target is better</b>: the engine eagerly rejects a
     *       false-positive the legacy defers to a later stage (trait satisfaction → SortChecker;
     *       unresolved-alias membership → abstains to {@code None}). Migrating tightens the check;
     *       the deferred stage rejected these anyway, so no valid program regresses.</li>
     * </ul>
     * A divergence outside this set — or a listed one that no longer diverges — fails the test.
     */
    private static final Map<String, String> KNOWN_DIVERGENCES = Map.of(
            "int->decimal embed: Int -> Decimal",
            "engine WEAKER (gap to close, §4.2): legacy IntToDecimal is legal; Assignability lacks "
                    + "the primitive-coercion embedding.",
            "trait (non-impl): Point -> Showable",
            "engine STRICTER (accepted): Point does not satisfy Showable — the engine rejects "
                    + "eagerly; legacy returns TraitCast and defers satisfaction to SortChecker.",
            "union non-member: Point -> AnyNumber",
            "engine STRICTER (accepted): the engine resolves the alias and rejects (Point is not "
                    + "Int|Decimal); legacy abstains on the unresolved alias (None).");

    @Test
    void engineAgreesWithLegacyExceptOnKnownGaps() {
        StringBuilder report = new StringBuilder("\nAssignability vs CoercionResolver (implicitly-legal?):\n");
        List<String> unexpected = new ArrayList<>();
        for (Pair p : corpus()) {
            boolean a = legalAssignability(p.from(), p.to());
            boolean c = legalCoercion(p.from(), p.to());
            boolean diverges = a != c;
            boolean known = KNOWN_DIVERGENCES.containsKey(p.label());
            report.append(String.format("  %-40s engine=%-5s legacy=%-5s %s%n",
                    p.label(), a, c, diverges ? (known ? "DIVERGE(known)" : "DIVERGE(!)") : "agree"));
            if (diverges && !known) {
                unexpected.add(p.label() + " -- engine=" + a + " legacy=" + c);
            }
            if (!diverges && known) {
                unexpected.add(p.label() + " — no longer diverges; remove from KNOWN_DIVERGENCES");
            }
        }
        System.out.print(report);
        assertTrue(unexpected.isEmpty(),
                () -> report + "\nUnexpected differential drift:\n  " + String.join("\n  ", unexpected));
    }
}

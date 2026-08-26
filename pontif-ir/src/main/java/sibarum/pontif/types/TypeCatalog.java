package sibarum.pontif.types;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import sibarum.pontif.ir.IrModule;
import sibarum.pontif.ir.IrSort;
import sibarum.pontif.ir.IrStmt;
import sibarum.pontif.ir.NativeConstructors;

/**
 * The single answerer for "what is the type named {@code X}?" — the consolidated registry that replaces
 * the two independently-built copies of this information: the parser's separate {@code declaredStructs}
 * / {@code declaredTraits} / {@code declaredSortAliases} maps (built incrementally as it parses) and the
 * IR's struct-only {@code TypeRegistry.collect} (rebuilt from a finished module). The <em>rules</em> for
 * interpreting a declaration into a {@link TypeInfo} live here once, whether the data arrives one
 * declaration at a time ({@link #register}, the parser) or all at once ({@link #fromModule}, the IR).
 *
 * <p>An absent name is an honest {@link Optional#empty()} — for the parser that is "not declared yet"
 * (a forward or cross-module reference, the signal to defer resolution to link time); for a finished
 * module it is genuinely unknown. Built-in constructors and primitives are answered by fallback, so a
 * catalog need not have them registered to know {@code Decimal} or {@code Int}.
 *
 * <p>This is now the sole type registry — the old IR {@code TypeRegistry} it subsumed has been
 * decommissioned (James 2026-07-07, who kept the {@code TypeCatalog} name).
 */
public final class TypeCatalog {

    /** Built-in scalar sorts with no user declaration. */
    private static final Set<String> PRIMITIVES = Set.of("Int", "Bool", "Decimal", "Char", "String");

    private final Map<String, TypeInfo> byName = new LinkedHashMap<>();

    public TypeCatalog() {}

    // --- population ---------------------------------------------------------

    /** Register {@code name} as {@code info}, replacing any prior entry (last declaration wins). */
    public void register(String name, TypeInfo info) {
        byName.put(name, info);
    }

    /** Register {@code name} only if not already present (for a type's secondary/internal name). */
    public void registerIfAbsent(String name, TypeInfo info) {
        byName.putIfAbsent(name, info);
    }

    /**
     * Copies every entry of {@code other} into this catalog, in {@code other}'s registration order —
     * the parser's declaration pre-pass seeding the catalog it will parse bodies against, so a type
     * is visible from the first line of the file regardless of where it is declared. Entries are
     * <em>replaced</em> as the real pass re-reaches each declaration (last declaration wins, exactly
     * as {@link #register}), so a seeded shape is never authoritative over a parsed one.
     */
    public void seedFrom(TypeCatalog other) {
        byName.putAll(other.byName);
    }

    /**
     * Builds a catalog from a finished module's preserved declarations — the single interpretation of
     * struct / trait / alias type-aliases, in source order. A struct is registered under both the alias
     * name and its own internal name (they coincide in the Pontif syntax; the S-expr {@code deftype} form
     * lets them differ, and both must resolve now that struct references stay nominal). Primitives and
     * native constructors are not stored — {@link #lookup} answers them by fallback.
     */
    public static TypeCatalog fromModule(IrModule module) {
        TypeCatalog cat = new TypeCatalog();
        for (IrStmt stmt : module.statements()) {
            if (!(stmt instanceof IrStmt.TypeAlias ta)) continue;
            switch (ta.sort()) {
                case IrSort.Structural s -> {
                    cat.register(ta.name(), new TypeInfo.Struct(s));
                    cat.registerIfAbsent(s.name(), new TypeInfo.Struct(s));
                }
                case IrSort.Trait t -> {
                    cat.register(ta.name(), new TypeInfo.Trait(t));
                    cat.registerIfAbsent(t.name(), new TypeInfo.Trait(t));
                }
                default -> cat.register(ta.name(), new TypeInfo.Alias(ta.sort()));
            }
        }
        return cat;
    }

    // --- lookup -------------------------------------------------------------

    /**
     * What {@code name} resolves to, or empty when the catalog has no such type. A registered name wins;
     * otherwise a built-in constructor ({@link TypeInfo.Native}) or a bare primitive
     * ({@link TypeInfo.Primitive}) is answered by fallback.
     */
    public Optional<TypeInfo> lookup(String name) {
        TypeInfo info = byName.get(name);
        if (info != null) return Optional.of(info);
        if (NativeConstructors.has(name)) {
            return Optional.of(new TypeInfo.Native(NativeConstructors.get(name).shape()));
        }
        if (PRIMITIVES.contains(name)) return Optional.of(new TypeInfo.Primitive(name));
        return Optional.empty();
    }

    /** Whether {@code name} is a known type (declared, built-in constructor, or primitive). */
    public boolean knows(String name) {
        return lookup(name).isPresent();
    }

    /** The structural shape of {@code name} — a struct or a native constructor — or empty otherwise. */
    public Optional<IrSort.Structural> shapeOf(String name) {
        return lookup(name).map(t -> switch (t) {
            case TypeInfo.Struct s -> s.shape();
            case TypeInfo.Native n -> n.shape();
            default -> null;
        });
    }

    /**
     * Whether {@code name} was explicitly declared (a struct, trait, or alias registered here) — as
     * opposed to a fallback primitive or native constructor that {@link #lookup} would still answer.
     */
    public boolean isDeclared(String name) {
        return byName.containsKey(name);
    }

    /** Whether {@code name} is a user-declared struct. */
    public boolean isStruct(String name) {
        return byName.get(name) instanceof TypeInfo.Struct;
    }

    /** Whether {@code name} is a declared trait. */
    public boolean isTrait(String name) {
        return byName.get(name) instanceof TypeInfo.Trait;
    }

    /** Whether {@code name} is a transparent alias. */
    public boolean isAlias(String name) {
        return byName.get(name) instanceof TypeInfo.Alias;
    }

    /** Whether {@code name} is a built-in scalar ({@code Int}/{@code Bool}/{@code Decimal}/
     *  {@code Char}/{@code String}) — the one home for that fact, so callers (e.g. the parser's
     *  binding-sort decision) don't restate the set. */
    public boolean isPrimitive(String name) {
        return PRIMITIVES.contains(name);
    }

    // --- bulk view ----------------------------------------------------------

    /**
     * The struct (and native) {@code name → shape} map, in registration order — for callers that
     * genuinely need the whole set at once (a module's struct table for {@link
     * sibarum.pontif.ir.InferenceContext}, {@code SortChecker}, the gates) rather than a per-name
     * {@link #shapeOf} query. In practice struct-only: natives are answered by {@link #lookup} fallback,
     * never registered, so they do not appear here unless explicitly registered.
     */
    public Map<String, IrSort.Structural> structShapes() {
        Map<String, IrSort.Structural> m = new LinkedHashMap<>();
        for (Map.Entry<String, TypeInfo> e : byName.entrySet()) {
            switch (e.getValue()) {
                case TypeInfo.Struct s -> m.put(e.getKey(), s.shape());
                case TypeInfo.Native n -> m.put(e.getKey(), n.shape());
                default -> { }
            }
        }
        return m;
    }
}

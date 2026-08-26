package sibarum.pontif.anybox;

import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums;
import dev.vexelray.gui.core.style.Role;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading Pontif values on the Anybox boundary: the three enum-backed vocabularies
 * ({@link Role}, {@link LayoutEnums.Justify}/{@link LayoutEnums.AlignItems}), the {@link Length}
 * union, and the small scalar accessors.
 *
 * <p>Every Pontif {@code enum} case carries an explicit {@code key} payload naming its vexelray
 * counterpart, so this side reads a field rather than parsing a mangled type name — {@code
 * Role.Panel} arrives as a record whose {@code key} is {@code "PANEL"}. That is the whole reason
 * the enums have a payload at all: {@code _ordinal} would couple the two declaration orders, and a
 * type name would couple to the {@code E$Case} spelling.
 */
final class Atoms {

    private Atoms() {}

    // ------------------------------------------------------------------ scalars

    static String str(RecordValue rv, String field) {
        return rv.members().get(field) instanceof StringValue s ? s.content() : "";
    }

    static boolean bool(RecordValue rv, String field) {
        return rv.members().get(field) instanceof Boolean b && b;
    }

    static double num(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Integer n) return n;
        if (o instanceof BigDecimal d) return d.doubleValue();
        return 0.0;
    }

    /** The bare type name of a record — {@code pontif.gui/Gap} → {@code Gap}. */
    static String bareType(RecordValue rv) {
        String n = rv.typeName();
        if (n == null) return "";
        int slash = n.lastIndexOf('/');
        return slash < 0 ? n : n.substring(slash + 1);
    }

    /** An enum case's {@code key} payload — the name of its vexelray counterpart. */
    private static String key(Object o) {
        return o instanceof RecordValue rv ? str(rv, "key") : "";
    }

    // ------------------------------------------------------------------ config

    /** A named member of the {@code window({title = …})} config record, or {@code def}. */
    static String cfgStr(Object cfg, String field, String def) {
        if (!(cfg instanceof RecordValue rv) || !rv.members().containsKey(field)) return def;
        return rv.members().get(field) instanceof StringValue s ? s.content() : def;
    }

    static int cfgInt(Object cfg, String field, int def) {
        if (!(cfg instanceof RecordValue rv) || !rv.members().containsKey(field)) return def;
        return (int) num(rv.members().get(field));
    }

    // ------------------------------------------------------------------ vocabularies

    /**
     * A {@code Role} case → the vexelray role of the same name. An unknown key resolves to
     * {@link Role#INK} rather than throwing: a themeable colour is never worth failing a window
     * over, and the wrong ink is visible where an exception is not.
     */
    static Role role(Object o) {
        return switch (key(o)) {
            case "NONE" -> Role.NONE;
            case "WELL" -> Role.WELL;
            case "PAGE" -> Role.PAGE;
            case "CHROME" -> Role.CHROME;
            case "PANEL" -> Role.PANEL;
            case "RAISED" -> Role.RAISED;
            case "LINE" -> Role.LINE;
            case "TRACK" -> Role.TRACK;
            case "SELECTION" -> Role.SELECTION;
            case "EDGE" -> Role.EDGE;
            case "GRIP" -> Role.GRIP;
            case "DIM" -> Role.DIM;
            case "FAINT" -> Role.FAINT;
            case "ACCENT" -> Role.ACCENT;
            case "ACTION" -> Role.ACTION;
            case "ON_ACTION" -> Role.ON_ACTION;
            case "DANGER" -> Role.DANGER;
            case "ON_DANGER" -> Role.ON_DANGER;
            case "HIGHLIGHT" -> Role.HIGHLIGHT;
            case "SHADOW" -> Role.SHADOW;
            case "SCRIM" -> Role.SCRIM;
            default -> Role.INK;
        };
    }

    /** A {@code Placement} case on the main axis. {@code Stretch} has no main-axis meaning → START. */
    static LayoutEnums.Justify justify(Object o) {
        return switch (key(o)) {
            case "CENTER" -> LayoutEnums.Justify.CENTER;
            case "END" -> LayoutEnums.Justify.END;
            case "SPACE_BETWEEN" -> LayoutEnums.Justify.SPACE_BETWEEN;
            default -> LayoutEnums.Justify.START;
        };
    }

    /** A {@code Placement} case on the cross axis. {@code Between} has no cross-axis meaning → STRETCH. */
    static LayoutEnums.AlignItems align(Object o) {
        return switch (key(o)) {
            case "START" -> LayoutEnums.AlignItems.START;
            case "CENTER" -> LayoutEnums.AlignItems.CENTER;
            case "END" -> LayoutEnums.AlignItems.END;
            default -> LayoutEnums.AlignItems.STRETCH;
        };
    }

    /**
     * A {@code Length} atom → the vexelray length. Unlike the enums these are structs with a
     * numeric payload, so they are told apart by their bare type name; an unrecognised one is
     * {@link Length#AUTO} (size to content), the inert choice.
     */
    static Length length(Object o) {
        if (!(o instanceof RecordValue rv)) return Length.AUTO;
        float v = (float) num(rv.members().get("v"));
        return switch (bareType(rv)) {
            case "Rem" -> Length.rem(v);
            case "Em" -> Length.em(v);
            case "Dp" -> Length.dp(v);
            case "Percent" -> Length.percent(v);
            case "Grow" -> Length.grow((float) num(rv.members().get("factor")));
            case "Fill" -> Length.FILL;
            default -> Length.AUTO;
        };
    }

    // ------------------------------------------------------------------ aggregates

    /**
     * The members of a Pontif aggregate, in order. A {@code _tuple} record's members ARE the
     * elements; anything else is treated as a single element, so a lone child needs no wrapping.
     */
    static List<Object> items(Object aggregate) {
        if (aggregate == null) return List.of();
        if (aggregate instanceof RecordValue rv && "_tuple".equals(rv.typeName())) {
            return List.copyOf(rv.members().values());
        }
        return List.of(aggregate);
    }

    /** An event record for {@code fireEvent} — the inbound-emit door back into Pontif. */
    static RecordValue event(String type, String f1, String v1) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(f1, new StringValue(v1));
        return new RecordValue(type, m);
    }

    static RecordValue event(String type, String f1, String v1, String f2, String v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(f1, new StringValue(v1));
        m.put(f2, new StringValue(v2));
        return new RecordValue(type, m);
    }
}

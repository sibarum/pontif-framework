package sibarum.pontif.gui;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.ui.Ui;
import sibarum.pontif.core.types.RecordValue;
import sibarum.pontif.core.types.StringValue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared leaf constants and pure marshalling/formatting helpers for the GUI/plot bridge. Everything
 * here is stateless and has no window/GL dependency (except {@link #plotSceneView}, a pure builder),
 * so every collaborator ({@link DasumBridge}, {@code SceneBuilder}, {@code ChartBuilder}, …) imports
 * it instead of reaching into one another. Split out of the former god-class {@code DasumBridge}.
 */
final class GuiShared {
    private GuiShared() {}

    static final int WIDTH = 900;
    static final int HEIGHT = 600;
    static final Color TEXT = new Color(0.92f, 0.92f, 0.96f, 1f);
    static final Color TRANSPARENT = new Color(0f, 0f, 0f, 0f);
    static final Color BACKGROUND = new Color(0.05f, 0.07f, 0.12f, 1f);
    static final Color PLOT_BG = new Color(0.04f, 0.05f, 0.08f, 1f);
    static final Color SERIES_COLOR = new Color(0.40f, 0.80f, 1.0f, 1f);

    /** Distinct series colours, cycled by curve index in a composed chart. */
    static final Color[] SERIES_PALETTE = {
            new Color(0.40f, 0.80f, 1.00f, 1f),   // cyan
            new Color(1.00f, 0.55f, 0.35f, 1f),   // orange
            new Color(0.55f, 0.95f, 0.55f, 1f),   // green
            new Color(0.95f, 0.55f, 0.85f, 1f),   // magenta
            new Color(0.95f, 0.85f, 0.40f, 1f),   // yellow
    };

    static RecordValue element(String type, String field, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(field, new StringValue(value));
        return new RecordValue(type, m);
    }

    /** A plot/scene viewport built through the {@code Ui} builder — fill + grow + interactive by
     *  default (a scene has no intrinsic size, so filling its slot is the correct default and keeps a
     *  plot from collapsing when it isn't the whole window). The plot background is applied here. */
    static Component.SceneView plotSceneView() {
        return (Component.SceneView) Ui.sceneView().background(PLOT_BG).build();
    }

    /** A visible red error label — an unknown/broken node renders as this rather than failing silently. */
    static Component errorLabel(String message) {
        return new Component.Text(message, sibarum.dasum.gui.core.em.Em.of(1f), new Color(0.95f, 0.4f, 0.4f, 1f));
    }

    /** A colour channel clamped to the renderable [0,1] range (a {@link Color} out of range throws). */
    static float clamp01(double v) {
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    /** A {@code #rrggbb} CSS hex string for a {@link Color}'s RGB (alpha dropped) — used to colour-code
     *  each equation in the exported multi-plot title via a wrapping {@code <g style="color:…">}. */
    static String hex(Color c) {
        return String.format("#%02x%02x%02x",
                Math.round(clamp01(c.r()) * 255f), Math.round(clamp01(c.g()) * 255f), Math.round(clamp01(c.b()) * 255f));
    }

    /** A compact numeric label: up to 3 decimals, trailing zeros trimmed ({@code 2.0 → "2"}). */
    static String fmt(double v) {
        double r = Math.round(v * 1000.0) / 1000.0;
        if (r == Math.rint(r) && !Double.isInfinite(r)) return Long.toString((long) r);
        return java.math.BigDecimal.valueOf(r).stripTrailingZeros().toPlainString();
    }

    /** Compact number for a colorbar/label: integer when whole, else up to 3 significant decimals. */
    static String fmtNum(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString(Math.round(v));
        String s = String.format("%.3f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    static double[] toArray(List<Double> xs) {
        double[] a = new double[xs.size()];
        for (int i = 0; i < a.length; i++) a[i] = xs.get(i);
        return a;
    }

    /** {@code args[i]} as a double scalar, or 0.0 when absent. */
    static double arg(List<Object> args, int i) {
        return i < args.size() ? toDouble(args.get(i)) : 0.0;
    }

    /** A config field as a boolean, or {@code def} when absent — {@code args[i]} is {@code {field = …}}. */
    static boolean cfgBool(List<Object> args, int i, String field, boolean def) {
        if (i < args.size() && args.get(i) instanceof RecordValue rv
                && rv.members().get(field) instanceof Boolean b) {
            return b;
        }
        return def;
    }

    /** A numeric cfg field (Int/Decimal) as a positive int, or {@code def} when absent / non-positive. */
    static int cfgInt(List<Object> args, int i, String field, int def) {
        if (i < args.size() && args.get(i) instanceof RecordValue rv) {
            Object v = rv.members().get(field);
            if (v instanceof Long l && l > 0) return Math.toIntExact(l);
            if (v instanceof Integer n && n > 0) return n;
            if (v instanceof BigDecimal d && d.signum() > 0) return d.intValue();
        }
        return def;
    }

    /** A config field as a String, or "" — {@code args[i]} is the config record {field = …}. */
    static String cfgStr(List<Object> args, int i, String field) {
        return i < args.size() && args.get(i) instanceof RecordValue rv ? str(rv, field) : "";
    }

    /** A struct field as a double (Int/Decimal scalar), or 0.0. */
    static double memberD(RecordValue rv, String field) {
        return toDouble(rv.members().get(field));
    }

    /** A numeric member as a double, or {@code def} when the field is absent — so a partial config
     *  record falls back to the profile default per missing field. */
    static double memberOr(RecordValue rv, String field, double def) {
        return rv.members().containsKey(field) ? toDouble(rv.members().get(field)) : def;
    }

    static String str(RecordValue rv, String field) {
        return rv.members().get(field) instanceof StringValue s ? s.content() : "";
    }

    static double toDouble(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Integer n) return n;
        if (o instanceof BigDecimal d) return d.doubleValue();
        return 0.0;
    }

    static String bareType(String typeName) {
        if (typeName == null) return "";
        int slash = typeName.lastIndexOf('/');
        return slash < 0 ? typeName : typeName.substring(slash + 1);
    }

    static RecordValue emptyTuple() {
        return new RecordValue("_tuple", new LinkedHashMap<>());
    }

    /**
     * Converts a Pontif numeric aggregate (a {@code _tuple} RecordValue whose members are Pontif
     * Int/Decimal scalars) to a {@code double[]} in member order — the data marshalling across the
     * native boundary (only primitives cross). Non-record or non-numeric members yield 0.0.
     */
    static double[] doubles(Object value) {
        if (!(value instanceof RecordValue rv)) return new double[0];
        double[] out = new double[rv.members().size()];
        int i = 0;
        for (Object member : rv.members().values()) out[i++] = toDouble(member);
        return out;
    }

    /**
     * Flattens a Pontif aggregate of {@code {x,y,z}} triples (a {@code _tuple} whose members are
     * each a {@code _tuple} of three numeric scalars) to a row-major {@code float[]} of length
     * {@code 3*N}. Missing coordinates default to 0.
     */
    static float[] xyzTriples(Object value) {
        if (!(value instanceof RecordValue rv)) return new float[0];
        float[] out = new float[rv.members().size() * 3];
        int p = 0;
        for (Object point : rv.members().values()) {
            if (point instanceof RecordValue pr) {
                int j = 0;
                for (Object coord : pr.members().values()) {
                    if (j < 3) out[p * 3 + j] = (float) toDouble(coord);
                    j++;
                }
            }
            p++;
        }
        return out;
    }

    static JustifyContent justify(String s) {
        return switch (s) {
            case "center" -> JustifyContent.CENTER;
            case "end" -> JustifyContent.END;
            default -> JustifyContent.START;
        };
    }

    static AlignItems align(String s) {
        return switch (s) {
            case "center", "middle" -> AlignItems.CENTER;
            case "end" -> AlignItems.END;
            case "stretch" -> AlignItems.STRETCH;
            default -> AlignItems.START;
        };
    }
}

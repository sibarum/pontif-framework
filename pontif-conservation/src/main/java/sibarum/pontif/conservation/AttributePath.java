package sibarum.pontif.conservation;

import java.util.ArrayList;
import java.util.List;

/**
 * A dotted attribute path rooted at a call-instance variable: {@code n_0},
 * {@code p_0.x}, {@code p_0.inner.v}, {@code t_0._1}, {@code r_0.fullName}.
 * The atoms the conservation ledger tracks. A path <em>covers</em> another
 * when it is equal to it or an ancestor of it — emitting a whole aggregate
 * conserves every attribute under it.
 */
public record AttributePath(String root, List<String> segments) {

    public AttributePath {
        if (root == null || root.isEmpty()) {
            throw new IllegalArgumentException("AttributePath root must be non-empty");
        }
        segments = List.copyOf(segments);
    }

    public static AttributePath of(String root) {
        return new AttributePath(root, List.of());
    }

    public AttributePath child(String segment) {
        List<String> next = new ArrayList<>(segments);
        next.add(segment);
        return new AttributePath(root, next);
    }

    /** True when this path is equal to {@code other} or an ancestor of it. */
    public boolean covers(AttributePath other) {
        if (!root.equals(other.root)) return false;
        if (segments.size() > other.segments.size()) return false;
        for (int i = 0; i < segments.size(); i++) {
            if (!segments.get(i).equals(other.segments.get(i))) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        if (segments.isEmpty()) return root;
        return root + "." + String.join(".", segments);
    }
}

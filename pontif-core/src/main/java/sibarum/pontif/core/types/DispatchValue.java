package sibarum.pontif.core.types;

import java.util.List;

/**
 * Runtime value of a metareference: a first-class DISPATCH ({@code inc[Int]})
 * — the name and the key sorts it was referenced at. NOT a function pointer:
 * invocation (application — {@code ref(2)}) reruns runtime dispatch over the
 * name's candidates, narrowings intact, exactly as a direct call would.
 * The reference is created from statics only; no data content flows into it.
 */
public record DispatchValue(String functionName, List<Sort> keySorts) {

    public DispatchValue {
        if (functionName == null || functionName.isEmpty()) {
            throw new IllegalArgumentException("DispatchValue functionName must be non-empty");
        }
        keySorts = List.copyOf(keySorts);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(functionName).append('[');
        for (int i = 0; i < keySorts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(keySorts.get(i));
        }
        return sb.append(']').toString();
    }
}

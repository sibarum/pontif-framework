package sibarum.pontif.ir;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

public final class Environment {

    private final Map<String, Object> bindings;

    public Environment(Map<String, Object> bindings) {
        this.bindings = Map.copyOf(bindings);
    }

    public static Environment empty() {
        return new Environment(Map.of());
    }

    public Environment extend(String name, Object value) {
        Map<String, Object> next = new HashMap<>(bindings);
        next.put(name, value);
        return new Environment(next);
    }

    public Object lookup(String name) {
        if (!bindings.containsKey(name)) {
            throw new NoSuchElementException("Unbound variable: " + name);
        }
        return bindings.get(name);
    }

    public boolean contains(String name) {
        return bindings.containsKey(name);
    }
}

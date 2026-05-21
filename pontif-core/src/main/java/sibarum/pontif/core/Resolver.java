package sibarum.pontif.core;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public final class Resolver {

    private final FrameDescriptor.Builder descriptorBuilder = FrameDescriptor.newBuilder();
    private final Deque<Map<String, Integer>> scopes = new ArrayDeque<>();

    public Resolver() {
        scopes.push(new HashMap<>());
    }

    public int allocateSlot(String name) {
        return descriptorBuilder.addSlot(FrameSlotKind.Object, name, null);
    }

    public void pushScope(String name, int slot) {
        Map<String, Integer> next = new HashMap<>(scopes.peek());
        next.put(name, slot);
        scopes.push(next);
    }

    public void popScope() {
        scopes.pop();
    }

    public int lookup(String name) {
        Integer slot = scopes.peek().get(name);
        if (slot == null) {
            throw new UnboundVariableException("Unbound variable: " + name);
        }
        return slot;
    }

    public FrameDescriptor build() {
        return descriptorBuilder.build();
    }
}

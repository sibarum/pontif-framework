package sibarum.pontif.ast.bind;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.Resolver;

import java.util.List;

public final class Var extends PontifNode {

    private final String name;
    private int slot = -1;

    private Var(String name) {
        this.name = name;
    }

    public static Var of(String name) {
        return new Var(name);
    }

    public String name() {
        return name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (slot < 0) {
            throw new IllegalStateException("Var " + name + " not resolved; call Pontif.eval (which runs Resolver) instead of constructing RootNodes directly");
        }
        return frame.getObject(slot);
    }

    @Override
    public List<PontifNode> children() {
        return List.of();
    }

    @Override
    public void resolve(Resolver resolver) {
        this.slot = resolver.lookup(name);
    }
}

package sibarum.pontif.ast.bind;

import com.oracle.truffle.api.frame.VirtualFrame;
import sibarum.pontif.core.PontifNode;
import sibarum.pontif.core.Resolver;

import java.util.List;

public final class Let extends PontifNode {

    private final String name;
    @Child private PontifNode value;
    @Child private PontifNode body;
    private int slot = -1;

    private Let(String name, PontifNode value, PontifNode body) {
        this.name = name;
        this.value = value;
        this.body = body;
    }

    public static Let of(String name, PontifNode value, PontifNode body) {
        return new Let(name, value, body);
    }

    public String name() {
        return name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (slot < 0) {
            throw new IllegalStateException("Let " + name + " not resolved; call Pontif.eval (which runs Resolver) instead of constructing RootNodes directly");
        }
        Object v = value.execute(frame);
        frame.setObject(slot, v);
        return body.execute(frame);
    }

    @Override
    public List<PontifNode> children() {
        return List.of(value, body);
    }

    @Override
    public void resolve(Resolver resolver) {
        value.resolve(resolver);
        this.slot = resolver.allocateSlot(name);
        resolver.pushScope(name, slot);
        body.resolve(resolver);
        resolver.popScope();
    }
}

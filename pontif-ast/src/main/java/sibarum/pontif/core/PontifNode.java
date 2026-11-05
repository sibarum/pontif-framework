package sibarum.pontif.core;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

import java.util.List;

public abstract class PontifNode extends Node {

    private Origin origin = Origin.NONE;

    public abstract Object execute(VirtualFrame frame);

    public abstract List<PontifNode> children();

    public void resolve(Resolver resolver) {
        for (PontifNode child : children()) {
            child.resolve(resolver);
        }
    }

    public Origin origin() {
        return origin;
    }

    /** Fluent setter — returns this for chaining at construction sites. */
    public PontifNode withOrigin(Origin origin) {
        this.origin = origin == null ? Origin.NONE : origin;
        return this;
    }
}

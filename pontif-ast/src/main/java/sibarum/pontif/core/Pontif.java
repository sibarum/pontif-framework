package sibarum.pontif.core;

import com.oracle.truffle.api.frame.FrameDescriptor;

public final class Pontif {

    private Pontif() {}

    public static Object eval(PontifNode tree) {
        Resolver resolver = new Resolver();
        tree.resolve(resolver);
        FrameDescriptor descriptor = resolver.build();
        PontifRootNode root = new PontifRootNode(null, descriptor, tree);
        return root.getCallTarget().call();
    }

    public static long evalLong(PontifNode tree) {
        return (Long) eval(tree);
    }

    public static boolean evalBoolean(PontifNode tree) {
        return (Boolean) eval(tree);
    }
}

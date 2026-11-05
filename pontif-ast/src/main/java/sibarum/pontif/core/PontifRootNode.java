package sibarum.pontif.core;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

public final class PontifRootNode extends RootNode {

    @Child private PontifNode body;

    public PontifRootNode(TruffleLanguage<?> language, FrameDescriptor descriptor, PontifNode body) {
        super(language, descriptor);
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return body.execute(frame);
    }
}

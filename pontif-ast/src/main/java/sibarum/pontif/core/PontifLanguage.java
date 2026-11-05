package sibarum.pontif.core;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;

@TruffleLanguage.Registration(
        id = PontifLanguage.ID,
        name = "Pontif"
)
public final class PontifLanguage extends TruffleLanguage<PontifContext> {

    public static final String ID = "pontif";

    @Override
    protected PontifContext createContext(Env env) {
        return new PontifContext();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        throw new UnsupportedOperationException(
                "Pontif is BYO-parser; build ASTs programmatically with the pontif-ast builders and call Pontif.eval.");
    }
}

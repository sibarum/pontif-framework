package sibarum.pontif.net.debug;

import sibarum.elektro.queue.message.ArrayMessageRegistry;
import sibarum.elektro.queue.message.MessageRegistry;

/**
 * Builds the {@link MessageRegistry} both ends of the debug port share. It registers every
 * {@code @Message} in this module through the registrar generated beside them,
 * {@link ElektroRegistrar} &mdash; the same reflection-free, id-indexed registration the
 * rest of elektro-Q uses.
 */
public final class DebugRegistry {

    private DebugRegistry() {}

    /** A fresh registry with all debug-protocol message types registered. */
    public static MessageRegistry newRegistry() {
        return ArrayMessageRegistry.of(ElektroRegistrar.INSTANCE);
    }
}

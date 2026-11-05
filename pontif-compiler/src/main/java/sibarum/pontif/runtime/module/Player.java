package sibarum.pontif.runtime.module;

/**
 * A cooperative worker in a {@link Conductor}'s orchestra — ticked on its cadence, retiring when its
 * {@link #tick} returns {@code false}. In the Orchestration API (docs/orchestration.md) a Player is a Pontif
 * conduit (a logic clock firing events) or a graphics render (advancing one frame); this is the host-level
 * interface the runtime Conductor drives, mirroring the supirvast spike's {@code Player} so the render host
 * (pontif-builtin-vulkan) can seat the same shape.
 */
@FunctionalInterface
public interface Player {

    /** Play one tick at logical time {@code nowNanos}; return {@code false} to retire from the orchestra. */
    boolean tick(long nowNanos);
}

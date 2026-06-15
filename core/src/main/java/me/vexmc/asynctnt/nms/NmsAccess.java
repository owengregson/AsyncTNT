package me.vexmc.asynctnt.nms;

import java.util.List;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import me.vexmc.asynctnt.common.engine.EntityPush;
import me.vexmc.asynctnt.common.engine.ExplosionResult;
import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.Vec3d;
import me.vexmc.asynctnt.common.snapshot.BlastResistanceView;
import me.vexmc.asynctnt.common.snapshot.BlockCollisionView;
import me.vexmc.asynctnt.common.snapshot.BodyState;
import me.vexmc.asynctnt.common.snapshot.EntitySnapshot;
import me.vexmc.asynctnt.common.snapshot.FluidView;

/**
 * The single boundary between the off-thread engine and the live server. Every
 * method here runs on the owning region thread (Folia) / main thread (Paper) —
 * the engine never calls these from a worker. Implementations isolate all
 * version-specific reflection (routed through reflection-remapper) behind this
 * interface, per the {@code paper-cross-version} / {@code nms-archaeology}
 * conventions.
 *
 * <p>The snapshot-capture methods read the world into immutable POJOs; the
 * apply methods mutate it. The pure physics core ({@code common.engine}) does
 * the computation in between, off-thread.
 */
public interface NmsAccess {

    /** False when required reflection could not be resolved; the engine then disables itself. */
    boolean available();

    // ── classification ──────────────────────────────────────────────────────
    boolean isPrimedTnt(@NotNull Entity entity);

    boolean isFallingBlock(@NotNull Entity entity);

    // ── takeover ────────────────────────────────────────────────────────────
    /** Neutralize the vanilla tick (gravity off, vanilla velocity zeroed, fuse held safe). */
    void neutralize(@NotNull Entity entity);

    /**
     * Undo {@link #neutralize}, returning the entity to ordinary vanilla ticking with
     * the engine's current authoritative state (velocity + fuse) handed back.
     */
    void restore(@NotNull Entity entity, @NotNull BodyState state);

    // ── reads (owning thread) ────────────────────────────────────────────────
    @NotNull BodyState readBody(@NotNull Entity entity);

    @NotNull BlockCollisionView captureCollision(@NotNull World world, @NotNull Aabb bounds);

    @NotNull FluidView captureFluid(@NotNull World world, @NotNull Aabb bounds);

    /**
     * Snapshot the blast cube (centre ± radius blocks) into immutable arrays so
     * the off-thread solver can read resistance/air/destroyable without the
     * live world.
     */
    @NotNull BlastResistanceView captureBlast(@NotNull World world, @NotNull Vec3d center, int radius);

    @NotNull List<EntitySnapshot> captureEntities(@NotNull World world, @NotNull Vec3d center,
                                                  double diameter, long ignoredEntityId);

    /** Explosion centre for a primed TNT: {@code getY(0.0625)}. */
    @NotNull Vec3d explosionCenter(@NotNull Entity tnt);

    // ── apply (owning thread) ────────────────────────────────────────────────
    /** Move the shadow entity to the engine-computed state (region-safe position set). */
    void applyState(@NotNull Entity entity, @NotNull BodyState state);

    /** Destroy the solved block set in this world (caller guarantees region ownership). */
    void destroyBlocks(@NotNull World world, @NotNull ExplosionResult result);

    /** Apply one entity's explosion knockback/damage (push or player packet path). */
    void applyPush(@NotNull World world, @NotNull EntityPush push);

    /** Emit the explosion sound + particles at the centre. */
    void emitExplosionEffects(@NotNull World world, @NotNull Vec3d center);

    /** Remove a detonated/landed body's shadow entity. */
    void removeEntity(@NotNull Entity entity);

    /** Resolve a captured entity by its engine id for the apply phase (or null if gone). */
    Entity resolveEntity(@NotNull World world, long entityId);
}

package me.vexmc.asynctnt.nms;

import java.util.List;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.BlockPos;
import me.vexmc.asynctnt.common.math.Vec3d;
import me.vexmc.asynctnt.common.snapshot.BlastResistanceView;
import me.vexmc.asynctnt.common.snapshot.BlockCollisionView;
import me.vexmc.asynctnt.common.snapshot.BodyState;
import me.vexmc.asynctnt.common.snapshot.EntitySnapshot;
import me.vexmc.asynctnt.common.snapshot.FluidView;

/**
 * The single boundary between the off-thread engine and the live server. Every
 * method runs on the owning region thread (Folia) / main thread (Paper) — the
 * engine never calls these from a worker.
 *
 * <p>Vanilla's explosion events are fired through here so protection/logging
 * plugins (WorldGuard, factions, CoreProtect, …) see AsyncTNT explosions exactly
 * as they see vanilla ones: {@link #fireExplosionPrime} (cancellable, can change
 * radius/fire) before the blast, and {@link #fireEntityExplode} (cancellable,
 * mutable block list, yield) before blocks are destroyed.
 */
public interface NmsAccess {

    /** False when required reflection could not be resolved; the engine then disables itself. */
    boolean available();

    // ── classification ──────────────────────────────────────────────────────
    boolean isPrimedTnt(@NotNull Entity entity);

    boolean isFallingBlock(@NotNull Entity entity);

    // ── takeover ────────────────────────────────────────────────────────────
    void neutralize(@NotNull Entity entity);

    void restore(@NotNull Entity entity, @NotNull BodyState state);

    // ── reads (owning thread) ────────────────────────────────────────────────
    @NotNull BodyState readBody(@NotNull Entity entity);

    @NotNull BlockCollisionView captureCollision(@NotNull World world, @NotNull Aabb bounds);

    @NotNull FluidView captureFluid(@NotNull World world, @NotNull Aabb bounds);

    /** Snapshot the blast cube (centre ± radius blocks) into immutable arrays for the off-thread solve. */
    @NotNull BlastResistanceView captureBlast(@NotNull World world, @NotNull Vec3d center, int radius);

    /** Live entities in blast range, paired with their immutable snapshots (incl. exposure). */
    @NotNull List<ExplosionTarget> captureExplosionTargets(@NotNull World world, @NotNull Vec3d center,
                                                           double diameter, long ignoredEntityId);

    /** Explosion centre for a primed TNT: {@code getY(0.0625)}. */
    @NotNull Vec3d explosionCenter(@NotNull Entity tnt);

    /**
     * Whether the chunk containing {@code (x,z)} is owned by the region thread
     * currently executing — i.e. whether it is safe to read/move a body there on
     * this thread. Always true on Paper (one tick thread); on Folia it gates the
     * engine's single ordered pass so a coordinator only ever ticks bodies its own
     * region owns. A body in another region is left to its own region's pass.
     */
    boolean regionOwnsChunkAt(@NotNull World world, double x, double z);

    // ── apply (owning thread) ────────────────────────────────────────────────
    void applyState(@NotNull Entity entity, @NotNull BodyState state);

    /**
     * Broadcast the engine's velocity to the players tracking this entity so the
     * client dead-reckons a smooth glide between the sparse server position syncs
     * (a falling block / primed TNT renders by extrapolating its own velocity).
     * Render-only: the real server-side velocity stays zero so vanilla physics
     * never re-consumes it. A no-op where the packet path could not be resolved.
     */
    void broadcastRenderVelocity(@NotNull Entity entity, @NotNull Vec3d velocity);

    /** Apply one entity's explosion knockback (and damage, for living entities), same-tick. */
    void applyPush(@NotNull Entity victim, @NotNull Vec3d knockback, float damage);

    /** Fire {@code ExplosionPrimeEvent}; the result carries the (possibly modified) radius/fire and cancellation. */
    @NotNull PrimeResult fireExplosionPrime(@NotNull Entity tnt);

    /**
     * Fire {@code EntityExplodeEvent} with the solved block set; the result is the
     * surviving block list (plugins may remove protected blocks), the yield, and
     * whether the explosion was cancelled.
     */
    @NotNull ExplodeResult fireEntityExplode(@NotNull Entity tnt, @NotNull Vec3d center,
                                             @NotNull List<BlockPos> broken, float yield);

    /** Destroy the given blocks (applyPhysics so water/redstone react) and drop items at the yield. */
    void destroyBlocks(@NotNull World world, @NotNull List<BlockPos> blocks, float yield, long dropSeed);

    /** Emit the explosion sound + particles at the centre. */
    void emitExplosionEffects(@NotNull World world, @NotNull Vec3d center);

    /** Place (or drop) a landed falling block. */
    void landFallingBlock(@NotNull World world, @NotNull BodyState state, @Nullable BlockData data);

    /** Remove a detonated/landed/despawned body's shadow entity. */
    void removeEntity(@NotNull Entity entity);

    // ── records ──────────────────────────────────────────────────────────────
    /** A live entity in blast range paired with its immutable physics snapshot. */
    record ExplosionTarget(@NotNull Entity entity, @NotNull EntitySnapshot snapshot) {
    }

    /** Outcome of {@code ExplosionPrimeEvent}. */
    record PrimeResult(boolean cancelled, float radius, boolean fire) {
    }

    /** Outcome of {@code EntityExplodeEvent}: surviving blocks + yield + cancellation. */
    record ExplodeResult(boolean cancelled, @NotNull List<BlockPos> blocks, float yield) {
    }
}

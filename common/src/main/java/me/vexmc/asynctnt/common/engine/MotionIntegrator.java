package me.vexmc.asynctnt.common.engine;

import java.util.List;

import me.vexmc.asynctnt.common.math.Aabb;
import me.vexmc.asynctnt.common.math.Mth;
import me.vexmc.asynctnt.common.math.Vec3d;
import me.vexmc.asynctnt.common.snapshot.BlockCollisionView;
import me.vexmc.asynctnt.common.snapshot.BodyState;
import me.vexmc.asynctnt.common.snapshot.FluidView;
import me.vexmc.asynctnt.common.version.ForkFlags;
import me.vexmc.asynctnt.common.version.PhysicsProfile;

/**
 * The pure, deterministic per-tick movement integrator for primed TNT and
 * falling blocks. Reproduces {@code Entity.move} + {@code PrimedTnt.tick} /
 * {@code FallingBlockEntity.tick} bit-for-bit against an immutable snapshot,
 * with zero Bukkit/NMS dependency so it runs off-thread and is unit-pinned.
 *
 * <p>Constants are byte-identical across 1.17.1 -> 26.1.x (research note 2.8).
 * The integration consumes no randomness — it is fully deterministic given the
 * state and the block/fluid snapshot.
 *
 * <p>Order matters and differs by kind:
 * <ul>
 *   <li>TNT: gravity -> move -> <b>drag(0.98)</b> -> ground(0.7,-0.5,0.7) -> fuse-- -> (water push)</li>
 *   <li>Falling block: gravity -> move -> ground(0.7,-0.5,0.7) -> <b>drag(0.98)</b> (last)</li>
 * </ul>
 */
public final class MotionIntegrator {

    private static final double GRAVITY = 0.04;
    private static final double DRAG = 0.98;
    private static final double GROUND_H = 0.7;
    private static final double GROUND_V = -0.5;

    // Water fluid push (Entity.updateFluidHeightAndDoFluidPushing).
    private static final double WATER_FLOW_SCALE = 0.014;
    private static final double WATER_PUSH_SPLIT = 0.4;
    private static final double SLOW_MOTION_THRESHOLD = 0.003;
    private static final double MIN_PUSH = 0.0045000000000000005;
    private static final double DEFLATE = 0.001;

    private MotionIntegrator() {
    }

    public static MotionResult tick(BodyState s,
                                    BlockCollisionView blocks,
                                    FluidView fluids,
                                    PhysicsProfile profile,
                                    ForkFlags forks) {
        // 1. Gravity (applied before move; getDefaultGravity == 0.04 for both kinds).
        double dx = s.dx();
        double dy = s.dy() - GRAVITY;
        double dz = s.dz();

        // 2. Stuck-speed (applyEffectsFromBlocks; identity for all cannon blocks).
        Aabb box = s.boundingBox();
        Vec3d stuck = blocks.stuckSpeedMultiplier(box);
        double mx = dx, my = dy, mz = dz;
        if (stuck.x() != 1.0 || stuck.y() != 1.0 || stuck.z() != 1.0) {
            mx *= stuck.x();
            my *= stuck.y();
            mz *= stuck.z();
            dx = 0.0;
            dy = 0.0;
            dz = 0.0;
        }

        // 3. move(SELF): collide the movement against the block shapes.
        Vec3d movement = new Vec3d(mx, my, mz);
        List<Aabb> shapes = blocks.collisionBoxes(box.expandTowards(mx, my, mz));
        Vec3d resolved = collide(box, shapes, movement, forks.eastWestCollisionFix());

        double nx = s.x() + resolved.x();
        double ny = s.y() + resolved.y();
        double nz = s.z() + resolved.z();

        boolean flagX = !Mth.equal(movement.x(), resolved.x());
        boolean flagZ = !Mth.equal(movement.z(), resolved.z());
        boolean horizontalCollision = flagX || flagZ;
        boolean verticalCollision = movement.y() != resolved.y();
        boolean onGround = verticalCollision && movement.y() < 0.0;

        // Post-move deltaMovement update (horizontal zero, vertical zero, blockSpeedFactor).
        if (horizontalCollision) {
            if (flagX) {
                dx = 0.0;
            }
            if (flagZ) {
                dz = 0.0;
            }
        }
        if (verticalCollision) {
            dy = 0.0; // default Block.updateEntityMovementAfterFallOn => multiply(1,0,1)
        }
        double speedFactor = blocks.blockSpeedFactor(nx, ny, nz);
        dx *= speedFactor;
        dz *= speedFactor;

        // 4. Per-kind drag/ground order, then lifecycle.
        if (s.kind() == BodyState.Kind.TNT) {
            dx *= DRAG;
            dy *= DRAG;
            dz *= DRAG;
            if (onGround) {
                dx *= GROUND_H;
                dy *= GROUND_V;
                dz *= GROUND_H;
            }
            int fuse = s.fuseOrTime() - 1;
            boolean detonate = fuse <= 0;
            if (!detonate) {
                // updateInWaterStateAndDoFluidPushing (water current propels TNT cannons).
                Vec3d pushed = applyWaterPush(new Vec3d(dx, dy, dz), nx, ny, nz, s, fluids);
                dx = pushed.x();
                dy = pushed.y();
                dz = pushed.z();
            }
            BodyState out = new BodyState(BodyState.Kind.TNT, nx, ny, nz, dx, dy, dz, fuse, onGround, s.blockStateId());
            return new MotionResult(out, detonate, false);
        } else {
            if (onGround) {
                dx *= GROUND_H;
                dy *= GROUND_V;
                dz *= GROUND_H;
            }
            dx *= DRAG;
            dy *= DRAG;
            dz *= DRAG;
            int time = s.fuseOrTime() + 1;
            BodyState out = new BodyState(BodyState.Kind.FALLING_BLOCK, nx, ny, nz, dx, dy, dz, time, onGround, s.blockStateId());
            return new MotionResult(out, false, onGround);
        }
    }

    /** Water current push: Entity.updateFluidHeightAndDoFluidPushing for the WATER tag. */
    private static Vec3d applyWaterPush(Vec3d motion, double nx, double ny, double nz,
                                        BodyState s, FluidView fluids) {
        if (!s.kind().pushedByFluid) {
            return motion;
        }
        Aabb deflated = Aabb.ofEntity(nx, ny, nz, s.kind().width, s.kind().height).deflate(DEFLATE);
        List<FluidView.FluidCell> cells = fluids.waterCells(deflated);
        if (cells.isEmpty()) {
            return motion;
        }
        double maxHeightDiff = 0.0;
        double totalPushes = 0.0;
        Vec3d push = Vec3d.ZERO;
        for (FluidView.FluidCell cell : cells) {
            double top = (double) ((float) cell.y() + cell.height());
            double diff = top - deflated.minY();
            if (diff < 0.0) {
                continue;
            }
            maxHeightDiff = Math.max(maxHeightDiff, diff);
            totalPushes += 1.0;
            Vec3d flow = cell.flow();
            push = maxHeightDiff < WATER_PUSH_SPLIT ? push.add(flow.scale(maxHeightDiff)) : push.add(flow);
        }
        if (push.x() == 0.0 && push.y() == 0.0 && push.z() == 0.0) {
            return motion;
        }
        push = push.scale(1.0 / totalPushes);
        push = push.normalize(); // TNT is not a Player
        push = push.scale(WATER_FLOW_SCALE);
        if (Math.abs(motion.x()) < SLOW_MOTION_THRESHOLD
                && Math.abs(motion.z()) < SLOW_MOTION_THRESHOLD
                && push.length() < MIN_PUSH) {
            push = push.normalize().scale(MIN_PUSH);
        }
        return motion.add(push);
    }

    /**
     * Reproduces {@code Entity.collideWithShapes}: resolve axes in vanilla's
     * order (Y first, then the larger-magnitude horizontal axis, then the
     * smaller — {@code Direction.axisStepOrder}), clipping each against every
     * candidate box moved by the already-resolved components. The east-west fix
     * forces a stable Y,X,Z order.
     */
    private static Vec3d collide(Aabb box, List<Aabb> shapes, Vec3d movement, boolean eastWestFix) {
        if (shapes.isEmpty()) {
            return movement;
        }
        int[] order = axisOrder(movement, eastWestFix);
        double rx = 0.0, ry = 0.0, rz = 0.0;
        for (int axis : order) {
            switch (axis) {
                case 1 -> { // Y
                    double d = movement.y();
                    if (d != 0.0) {
                        ry = clipY(box.move(rx, ry, rz), shapes, d);
                    }
                }
                case 0 -> { // X
                    double d = movement.x();
                    if (d != 0.0) {
                        rx = clipX(box.move(rx, ry, rz), shapes, d);
                    }
                }
                default -> { // Z
                    double d = movement.z();
                    if (d != 0.0) {
                        rz = clipZ(box.move(rx, ry, rz), shapes, d);
                    }
                }
            }
        }
        return new Vec3d(rx, ry, rz);
    }

    // 0=X, 1=Y, 2=Z. Vanilla: YZX when |x|<|z|, else YXZ.
    private static int[] axisOrder(Vec3d movement, boolean eastWestFix) {
        if (eastWestFix) {
            return new int[] {1, 0, 2};
        }
        return Math.abs(movement.x()) < Math.abs(movement.z())
                ? new int[] {1, 2, 0}
                : new int[] {1, 0, 2};
    }

    private static double clipX(Aabb e, List<Aabb> shapes, double d) {
        for (Aabb o : shapes) {
            if (e.maxY() > o.minY() && e.minY() < o.maxY() && e.maxZ() > o.minZ() && e.minZ() < o.maxZ()) {
                if (d > 0.0 && e.maxX() <= o.minX()) {
                    double diff = o.minX() - e.maxX();
                    if (diff < d) {
                        d = diff;
                    }
                } else if (d < 0.0 && e.minX() >= o.maxX()) {
                    double diff = o.maxX() - e.minX();
                    if (diff > d) {
                        d = diff;
                    }
                }
            }
        }
        return d;
    }

    private static double clipY(Aabb e, List<Aabb> shapes, double d) {
        for (Aabb o : shapes) {
            if (e.maxX() > o.minX() && e.minX() < o.maxX() && e.maxZ() > o.minZ() && e.minZ() < o.maxZ()) {
                if (d > 0.0 && e.maxY() <= o.minY()) {
                    double diff = o.minY() - e.maxY();
                    if (diff < d) {
                        d = diff;
                    }
                } else if (d < 0.0 && e.minY() >= o.maxY()) {
                    double diff = o.maxY() - e.minY();
                    if (diff > d) {
                        d = diff;
                    }
                }
            }
        }
        return d;
    }

    private static double clipZ(Aabb e, List<Aabb> shapes, double d) {
        for (Aabb o : shapes) {
            if (e.maxX() > o.minX() && e.minX() < o.maxX() && e.maxY() > o.minY() && e.minY() < o.maxY()) {
                if (d > 0.0 && e.maxZ() <= o.minZ()) {
                    double diff = o.minZ() - e.maxZ();
                    if (diff < d) {
                        d = diff;
                    }
                } else if (d < 0.0 && e.minZ() >= o.maxZ()) {
                    double diff = o.maxZ() - e.minZ();
                    if (diff > d) {
                        d = diff;
                    }
                }
            }
        }
        return d;
    }
}

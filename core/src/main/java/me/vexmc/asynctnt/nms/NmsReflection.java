package me.vexmc.asynctnt.nms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import me.vexmc.asynctnt.common.math.Vec3d;
import xyz.jpenilla.reflectionremapper.ReflectionRemapper;

/**
 * Resolves the two values the Bukkit API does not expose — a block's exact
 * explosion resistance and a fluid's exact flow/height — straight from NMS, with
 * names routed through reflection-remapper (reobf on spigot-mapped &lt;=1.20.4,
 * identity on Mojang-mapped 1.20.5+), mirroring Mental's FakePlayer bootstrap.
 *
 * <p>The NMS block state is reached via {@code CraftBlockData.getState()} — a
 * stable CraftBukkit method present on every version — rather than
 * {@code CraftBlock.getNMS()}, which is not uniform across the range. Every
 * capability is resolved lazily and independently; any failure disables just
 * that capability (returning NaN/null) so {@link BukkitNmsAccess} falls back to
 * its constant table / flow approximation without crashing. Knockback resistance
 * uses the public Bukkit attribute (no NMS).
 */
final class NmsReflection {

    private final JavaPlugin plugin;
    private final ReflectionRemapper remapper;

    NmsReflection(JavaPlugin plugin) {
        this.plugin = plugin;
        ReflectionRemapper r;
        try {
            r = ReflectionRemapper.forReobfMappingsInPaperJar();
        } catch (Throwable mojangMapped) {
            r = ReflectionRemapper.noop();
        }
        this.remapper = r;
    }

    // CraftBlockData.getState() -> NMS BlockState (the shared entry point).
    private volatile Method craftBlockDataGetState;

    private Object nmsBlockState(Block block) throws ReflectiveOperationException {
        Object data = block.getBlockData();
        Method getState = craftBlockDataGetState;
        if (getState == null) {
            getState = Reflect.noArgMethod(data.getClass(), "getState");
            if (getState == null) {
                throw new IllegalStateException("CraftBlockData.getState() not found on " + data.getClass());
            }
            craftBlockDataGetState = getState;
        }
        return getState.invoke(data);
    }

    // ── explosion resistance: blockState.getBlock().getExplosionResistance() ──
    private volatile boolean resistanceDisabled;
    private volatile Method blockStateGetBlock;
    private volatile Method blockGetResistance;

    float explosionResistance(Block block) {
        if (resistanceDisabled) {
            return Float.NaN;
        }
        try {
            Object nmsState = nmsBlockState(block);
            if (blockStateGetBlock == null) {
                String name = remapper.remapMethodName(nmsState.getClass(), "getBlock");
                blockStateGetBlock = Reflect.noArgMethod(nmsState.getClass(), name);
            }
            Object nmsBlock = blockStateGetBlock.invoke(nmsState);
            if (blockGetResistance == null) {
                String name = remapper.remapMethodName(nmsBlock.getClass(), "getExplosionResistance");
                blockGetResistance = Reflect.noArgMethod(nmsBlock.getClass(), name);
            }
            return ((Number) blockGetResistance.invoke(nmsBlock)).floatValue();
        } catch (Throwable failure) {
            disableResistance(failure);
            return Float.NaN;
        }
    }

    private void disableResistance(Throwable t) {
        if (!resistanceDisabled) {
            resistanceDisabled = true;
            plugin.getLogger().info("Exact NMS explosion resistance unavailable ("
                    + t.getClass().getSimpleName() + "); using the constant table.");
        }
    }

    // ── fluid flow + height: blockState.getFluidState().getFlow(level,pos) / getOwnHeight() ──
    private volatile boolean fluidDisabled;
    private volatile Method blockStateGetFluidState;
    private volatile Method fluidGetOwnHeight;
    private volatile Method fluidGetFlow;
    private volatile Method craftWorldGetHandle;
    private volatile Constructor<?> blockPosCtor;
    private volatile Field vec3X;
    private volatile Field vec3Y;
    private volatile Field vec3Z;

    @Nullable
    Vec3d fluidFlow(Block block) {
        if (fluidDisabled) {
            return null;
        }
        try {
            Object fluidState = fluidState(block);
            Object level = craftWorldGetHandle(block).invoke(block.getWorld());
            Object pos = blockPos(block);
            if (fluidGetFlow == null) {
                String name = remapper.remapMethodName(fluidState.getClass(), "getFlow");
                fluidGetFlow = Reflect.methodArity(fluidState.getClass(), name, 2, level.getClass(), pos.getClass());
            }
            Object vec = fluidGetFlow.invoke(fluidState, level, pos);
            resolveVec3Fields(vec);
            return new Vec3d(vec3X.getDouble(vec), vec3Y.getDouble(vec), vec3Z.getDouble(vec));
        } catch (Throwable failure) {
            disableFluid(failure);
            return null;
        }
    }

    float fluidHeight(Block block) {
        if (fluidDisabled) {
            return Float.NaN;
        }
        try {
            Object fluidState = fluidState(block);
            if (fluidGetOwnHeight == null) {
                String name = remapper.remapMethodName(fluidState.getClass(), "getOwnHeight");
                fluidGetOwnHeight = Reflect.noArgMethod(fluidState.getClass(), name);
            }
            return ((Number) fluidGetOwnHeight.invoke(fluidState)).floatValue();
        } catch (Throwable failure) {
            disableFluid(failure);
            return Float.NaN;
        }
    }

    private Object fluidState(Block block) throws ReflectiveOperationException {
        Object nmsState = nmsBlockState(block);
        if (blockStateGetFluidState == null) {
            String name = remapper.remapMethodName(nmsState.getClass(), "getFluidState");
            blockStateGetFluidState = Reflect.noArgMethod(nmsState.getClass(), name);
        }
        return blockStateGetFluidState.invoke(nmsState);
    }

    private Method craftWorldGetHandle(Block block) {
        Method m = craftWorldGetHandle;
        if (m == null) {
            m = Reflect.noArgMethod(block.getWorld().getClass(), "getHandle");
            craftWorldGetHandle = m;
        }
        return m;
    }

    private Object blockPos(Block block) throws ReflectiveOperationException {
        Constructor<?> ctor = blockPosCtor;
        if (ctor == null) {
            Class<?> nmsStateClass = nmsBlockState(block).getClass();
            String posName = remapper.remapClassName("net.minecraft.core.BlockPos");
            Class<?> posClass = Class.forName(posName, true, nmsStateClass.getClassLoader());
            ctor = posClass.getConstructor(int.class, int.class, int.class);
            ctor.setAccessible(true);
            blockPosCtor = ctor;
        }
        return ctor.newInstance(block.getX(), block.getY(), block.getZ());
    }

    private void resolveVec3Fields(Object vec) throws NoSuchFieldException {
        if (vec3X != null) {
            return;
        }
        Class<?> c = vec.getClass();
        vec3X = field(c, remapper.remapFieldName(c, "x"), "x");
        vec3Y = field(c, remapper.remapFieldName(c, "y"), "y");
        vec3Z = field(c, remapper.remapFieldName(c, "z"), "z");
    }

    private static Field field(Class<?> owner, String remapped, String plain) throws NoSuchFieldException {
        for (String name : new String[] {remapped, plain}) {
            try {
                Field f = owner.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the next candidate
            }
        }
        throw new NoSuchFieldException(plain + " on " + owner.getName());
    }

    private void disableFluid(Throwable t) {
        if (!fluidDisabled) {
            fluidDisabled = true;
            plugin.getLogger().info("Exact NMS fluid flow unavailable ("
                    + t.getClass().getSimpleName() + "); using the flow approximation.");
        }
    }

    // ── explosion knockback resistance via the public Bukkit attribute ──
    private volatile boolean kbResolved;
    private volatile org.bukkit.attribute.Attribute kbAttribute;

    double explosionKnockbackResistance(LivingEntity entity) {
        if (!kbResolved) {
            resolveKnockbackAttribute();
        }
        org.bukkit.attribute.Attribute attr = kbAttribute;
        if (attr == null) {
            return 0.0;
        }
        try {
            org.bukkit.attribute.AttributeInstance inst = entity.getAttribute(attr);
            return inst != null ? inst.getValue() : 0.0;
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private synchronized void resolveKnockbackAttribute() {
        if (kbResolved) {
            return;
        }
        kbResolved = true;
        for (String name : new String[] {"EXPLOSION_KNOCKBACK_RESISTANCE", "GENERIC_EXPLOSION_KNOCKBACK_RESISTANCE"}) {
            try {
                Field f = org.bukkit.attribute.Attribute.class.getField(name);
                kbAttribute = (org.bukkit.attribute.Attribute) f.get(null);
                return;
            } catch (Throwable absent) {
                // try the next candidate name
            }
        }
        kbAttribute = null;
    }

    // ── Folia region ownership: may we touch this chunk on the current thread? ──
    private volatile boolean ownershipResolved;
    private volatile Method isOwnedByCurrentRegion;

    /**
     * On Folia, whether the chunk at {@code (chunkX, chunkZ)} is owned by the
     * region currently ticking — i.e. whether it is safe to reposition an entity
     * into it directly on this thread (a within-region move) rather than via a
     * cross-region async teleport. Reached reflectively because core compiles
     * against the 1.17 API floor, which has no Folia methods. Returns false when
     * the method is absent (paged callers only consult this on Folia anyway).
     */
    boolean ownedByCurrentRegion(org.bukkit.World world, int chunkX, int chunkZ) {
        if (!ownershipResolved) {
            synchronized (this) {
                if (!ownershipResolved) {
                    try {
                        isOwnedByCurrentRegion = org.bukkit.Bukkit.class.getMethod(
                                "isOwnedByCurrentRegion", org.bukkit.World.class, int.class, int.class);
                    } catch (Throwable absent) {
                        isOwnedByCurrentRegion = null;
                    }
                    ownershipResolved = true;
                }
            }
        }
        Method m = isOwnedByCurrentRegion;
        if (m == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(m.invoke(null, world, chunkX, chunkZ));
        } catch (Throwable failure) {
            return false;
        }
    }

    // ── direct NMS reposition: relative-move packets (smooth) instead of teleport (snap) ──
    private volatile boolean setPosDisabled;
    private volatile Method craftEntityGetHandle;
    private volatile Method nmsSetPos;

    /**
     * Move the entity by writing its NMS position directly, the way a vanilla
     * tick does. The entity tracker then emits relative-move packets the client
     * interpolates smoothly, instead of the absolute teleport packets a Bukkit
     * {@code teleport} produces (which the client snaps to with no in-between
     * frames — why an engine-driven falling block appears to jump straight to
     * its landing spot). {@code setPos} also runs the entity's level callback, so
     * chunk-section membership stays correct as it crosses chunks.
     *
     * <p>Resolved once; on any failure it disables itself and returns false so
     * the caller falls back to a teleport.
     *
     * @return true if the NMS reposition was applied
     */
    /**
     * Resolve the NMS handle for any Bukkit entity, resolving {@code getHandle}
     * on the shared {@code CraftEntity} base (NOT the concrete subclass: the
     * subclass override e.g. {@code CraftTNTPrimed.getHandle} is a distinct
     * Method that throws if invoked on a {@code CraftFallingBlock} or
     * {@code CraftPlayer}). The base Method dispatches virtually and works for
     * every entity type, so the same cached Method serves TNT, falling blocks
     * and players alike.
     */
    private Object handle(org.bukkit.entity.Entity entity) throws ReflectiveOperationException {
        Method getHandle = craftEntityGetHandle;
        if (getHandle == null) {
            Class<?> craftEntity = entity.getClass();
            while (craftEntity != null && !craftEntity.getName().endsWith(".CraftEntity")) {
                craftEntity = craftEntity.getSuperclass();
            }
            Class<?> target = craftEntity != null ? craftEntity : entity.getClass();
            getHandle = Reflect.noArgMethod(target, "getHandle");
            if (getHandle == null) {
                throw new IllegalStateException("CraftEntity.getHandle() not found on " + target);
            }
            craftEntityGetHandle = getHandle;
        }
        return getHandle.invoke(entity);
    }

    boolean setPos(org.bukkit.entity.Entity entity, double x, double y, double z) {
        if (setPosDisabled) {
            return false;
        }
        try {
            Object handle = handle(entity);
            Method setPos = nmsSetPos;
            if (setPos == null) {
                // setPos is declared on NMS Entity; remap against THAT class (not the
                // TNT/falling-block subclass) so the inherited name resolves on
                // spigot-mapped versions. Try the remapped name, then the literal
                // Mojang/legacy names as a fallback.
                Class<?> entityClass = handle.getClass();
                while (entityClass != null && !entityClass.getName().endsWith(".Entity")) {
                    entityClass = entityClass.getSuperclass();
                }
                Class<?> remapTarget = entityClass != null ? entityClass : handle.getClass();
                for (String candidate : new String[] {
                        remapper.remapMethodName(remapTarget, "setPos"), "setPos", "setPosition"}) {
                    setPos = Reflect.methodArity(handle.getClass(), candidate, 3,
                            double.class, double.class, double.class);
                    if (setPos != null) {
                        break;
                    }
                }
                if (setPos == null) {
                    throw new IllegalStateException(
                            "Entity.setPos(double,double,double) not found on " + handle.getClass());
                }
                nmsSetPos = setPos;
            }
            setPos.invoke(handle, x, y, z);
            return true;
        } catch (Throwable failure) {
            setPosDisabled = true;
            plugin.getLogger().info("NMS setPos unavailable on this version ("
                    + failure.getClass().getSimpleName() + "); driving entities with teleport instead.");
            return false;
        }
    }

    // ── client render velocity: ClientboundSetEntityMotionPacket to the trackers ──
    // The engine keeps the real server-side deltaMovement at zero (so vanilla never
    // re-consumes it and moves the shadow entity), but a falling block / primed TNT
    // renders on the CLIENT by dead-reckoning its velocity (the client runs the
    // entity's own tick, moving it by its last-known deltaMovement between the sparse
    // server position syncs). A zero velocity therefore freezes the client render and
    // it snaps on each periodic position packet. Broadcasting the engine's real
    // velocity each tick gives the client the vector to glide along — exactly what
    // vanilla does — without perturbing server physics. Render-only and self-disabling:
    // a failure degrades to the (cosmetic) snap, never to a physics change.
    private static final double VELOCITY_CLAMP = 3.9; // motion-packet short encoding caps at ~3.9 b/t

    private volatile boolean broadcastDisabled;
    private volatile Constructor<?> motionPacketCtor; // ClientboundSetEntityMotionPacket(int, Vec3)
    private volatile Constructor<?> vec3Ctor;         // Vec3(double, double, double)
    private volatile boolean trackedByResolved;
    private volatile Method entityGetTrackedBy;       // Bukkit/Paper Entity#getTrackedBy() (may be absent)
    private volatile Field serverPlayerConnection;    // ServerPlayer.connection
    private volatile Method connectionSend;           // ServerPlayerConnection.send(Packet)

    /**
     * Broadcast {@code (vx,vy,vz)} as this entity's client-side velocity to the
     * players tracking it, so an engine-driven body glides smoothly instead of
     * snapping between position syncs. Runs on the entity's owning thread (called
     * from {@code applyState}); the tracker set / packet send touch only this
     * region's state. Self-disables on any reflection failure.
     *
     * @return true if the broadcast path is live (resolved and attempted)
     */
    boolean broadcastVelocity(org.bukkit.entity.Entity entity, double vx, double vy, double vz) {
        if (broadcastDisabled) {
            return false;
        }
        try {
            Object packet = motionPacket(entity, vx, vy, vz);
            for (Player viewer : viewers(entity)) {
                try {
                    sendPacket(viewer, packet);
                } catch (Throwable perViewer) {
                    // A single off-region / disconnecting viewer must not disable the path.
                }
            }
            return true;
        } catch (Throwable failure) {
            broadcastDisabled = true;
            plugin.getLogger().info("NMS velocity broadcast unavailable on this version ("
                    + failure.getClass().getSimpleName()
                    + "); engine-driven entities render on the vanilla tracker cadence.");
            return false;
        }
    }

    private Object motionPacket(org.bukkit.entity.Entity entity, double vx, double vy, double vz)
            throws ReflectiveOperationException {
        if (motionPacketCtor == null) {
            Object handle = handle(entity); // any NMS entity — used only for its classloader
            ClassLoader cl = handle.getClass().getClassLoader();
            Class<?> vec3Class = Class.forName(
                    remapper.remapClassName("net.minecraft.world.phys.Vec3"), true, cl);
            Constructor<?> v = vec3Class.getConstructor(double.class, double.class, double.class);
            v.setAccessible(true);
            vec3Ctor = v;
            Class<?> packetClass = Class.forName(
                    remapper.remapClassName("net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket"),
                    true, cl);
            Constructor<?> p = packetClass.getConstructor(int.class, vec3Class);
            p.setAccessible(true);
            motionPacketCtor = p;
        }
        Object vec = vec3Ctor.newInstance(clamp(vx), clamp(vy), clamp(vz));
        return motionPacketCtor.newInstance(entity.getEntityId(), vec);
    }

    /** Players tracking this entity: the exact viewer set via the Paper API where present, else every player in the world. */
    @SuppressWarnings("unchecked")
    private Collection<? extends Player> viewers(org.bukkit.entity.Entity entity) {
        if (!trackedByResolved) {
            synchronized (this) {
                if (!trackedByResolved) {
                    try {
                        entityGetTrackedBy = org.bukkit.entity.Entity.class.getMethod("getTrackedBy");
                    } catch (Throwable absent) {
                        entityGetTrackedBy = null;
                    }
                    trackedByResolved = true;
                }
            }
        }
        Method m = entityGetTrackedBy;
        if (m != null) {
            try {
                Object tracked = m.invoke(entity);
                if (tracked instanceof Collection<?> c) {
                    return (Collection<? extends Player>) c;
                }
            } catch (Throwable fallThrough) {
                // older/odd build — fall back to the world player list
            }
        }
        return entity.getWorld().getPlayers();
    }

    private void sendPacket(Player player, Object packet) throws ReflectiveOperationException {
        Object serverPlayer = handle(player); // CraftEntity.getHandle dispatches to CraftPlayer -> ServerPlayer
        Field connField = serverPlayerConnection;
        if (connField == null) {
            connField = fieldAny(serverPlayer.getClass(),
                    remapper.remapFieldName(serverPlayer.getClass(), "connection"),
                    "connection", "playerConnection", "f");
            serverPlayerConnection = connField;
        }
        Object conn = connField.get(serverPlayer);
        Method send = connectionSend;
        if (send == null) {
            for (String candidate : new String[] {
                    remapper.remapMethodName(conn.getClass(), "send"), "send", "sendPacket"}) {
                send = Reflect.methodArity(conn.getClass(), candidate, 1, packet.getClass());
                if (send != null) {
                    break;
                }
            }
            if (send == null) {
                throw new IllegalStateException("ServerPlayerConnection.send(Packet) not found on " + conn.getClass());
            }
            connectionSend = send;
        }
        send.invoke(conn, packet);
    }

    private static double clamp(double v) {
        return v < -VELOCITY_CLAMP ? -VELOCITY_CLAMP : (v > VELOCITY_CLAMP ? VELOCITY_CLAMP : v);
    }

    private static Field fieldAny(Class<?> owner, String... names) throws NoSuchFieldException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (String name : names) {
                if (name == null) {
                    continue;
                }
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // try the next candidate / superclass
                }
            }
        }
        throw new NoSuchFieldException(names[names.length - 1] + " on " + owner.getName());
    }
}

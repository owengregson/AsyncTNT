package me.vexmc.asynctnt.nms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
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
}

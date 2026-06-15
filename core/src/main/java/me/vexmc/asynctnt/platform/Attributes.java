package me.vexmc.asynctnt.platform;

import java.lang.reflect.Field;
import java.util.Optional;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cross-version attribute constants.
 *
 * <p>{@code org.bukkit.attribute.Attribute} was an enum with {@code GENERIC_}
 * prefixed constants through 1.21.1 and became a registry-backed interface
 * with unprefixed constants in 1.21.3 — an outright binary break in both
 * directions. Constants are therefore resolved by name once at class load,
 * trying the modern spelling first, and cached; lookups after boot are plain
 * static field reads.</p>
 */
public final class Attributes {

    private static final @Nullable Attribute KNOCKBACK_RESISTANCE =
            resolve("KNOCKBACK_RESISTANCE", "GENERIC_KNOCKBACK_RESISTANCE");
    private static final @Nullable Attribute EXPLOSION_KNOCKBACK_RESISTANCE =
            resolve("EXPLOSION_KNOCKBACK_RESISTANCE", "GENERIC_EXPLOSION_KNOCKBACK_RESISTANCE");

    private Attributes() {}

    public static @Nullable Attribute knockbackResistance() {
        return KNOCKBACK_RESISTANCE;
    }

    /** Absent below 1.21.2 — callers fall back to vanilla's 0.0. */
    public static @Nullable Attribute explosionKnockbackResistance() {
        return EXPLOSION_KNOCKBACK_RESISTANCE;
    }

    /** The attribute's current value, or {@code fallback} when absent on this entity or version. */
    public static double valueOr(@NotNull LivingEntity entity, @Nullable Attribute attribute, double fallback) {
        if (attribute == null) {
            return fallback;
        }
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance != null ? instance.getValue() : fallback;
    }

    static @NotNull Optional<Attribute> lookup(@NotNull String modernName, @NotNull String legacyName) {
        return Optional.ofNullable(resolve(modernName, legacyName));
    }

    private static @Nullable Attribute resolve(@NotNull String modernName, @NotNull String legacyName) {
        Attribute modern = staticField(modernName);
        return modern != null ? modern : staticField(legacyName);
    }

    private static @Nullable Attribute staticField(@NotNull String name) {
        try {
            Field field = Attribute.class.getField(name);
            Object value = field.get(null);
            return value instanceof Attribute attribute ? attribute : null;
        } catch (NoSuchFieldException | IllegalAccessException absent) {
            return null;
        }
    }
}

package me.vexmc.asynctnt.common.version;

/**
 * Per-version behavioural switches for the physics core. The numeric constants
 * (gravity 0.04, drag 0.98, ground friction 0.7/-0.5/0.7, explosion ray decay,
 * etc.) are byte-identical across 1.17.1 -> 26.1.x, so the only real deltas the
 * pure core cares about are captured here. See
 * {@code docs/research/2026-06-14-tnt-explosion-folia-research.md} sections 2.8
 * and 3.7.
 *
 * @param hasPortalTick       {@code handlePortal()} runs in the TNT/falling-block tick (1.21+)
 * @param hasBlockEffectsTick {@code applyEffectsFromBlocks()} runs in the tick (1.21+; stuck-speed)
 * @param soundPitchRng       the explosion consumes 2 extra {@code nextFloat} sound-pitch draws (<=1.20.6)
 * @param knockbackResist     how explosion knockback resistance is sourced
 */
public record PhysicsProfile(boolean hasPortalTick,
                             boolean hasBlockEffectsTick,
                             boolean soundPitchRng,
                             KnockbackResist knockbackResist) {

    /** Pre-1.20.5 reduce knockback via the protection-enchant dampener; 1.20.5+ via the attribute. */
    public enum KnockbackResist {
        ENCHANT_DAMPENER,
        ATTRIBUTE
    }

    /** Modern reference profile (1.21.4+, 26.1.x). */
    public static final PhysicsProfile MODERN =
            new PhysicsProfile(true, true, false, KnockbackResist.ATTRIBUTE);

    /**
     * Resolve the profile from a parsed Minecraft version. Year-major (>=26) and
     * 1.21+ get the modern tick additions and the 1.21 explosion-RNG layout;
     * 1.20.5/1.20.6 use the attribute but still emit the sound-pitch draws; below
     * 1.20.5 uses the enchant dampener.
     */
    public static PhysicsProfile forVersion(int major, int minor, int patch) {
        boolean yearScheme = major >= 26;
        boolean atLeast1_21 = yearScheme || (major == 1 && minor >= 21);
        boolean atLeast1_20_5 = yearScheme || (major == 1 && (minor > 20 || (minor == 20 && patch >= 5)));

        boolean portalTick = atLeast1_21;
        boolean blockEffectsTick = atLeast1_21;
        boolean soundPitchRng = !atLeast1_21; // 2 server sound-pitch nextFloat removed at 1.21
        KnockbackResist kb = atLeast1_20_5 ? KnockbackResist.ATTRIBUTE : KnockbackResist.ENCHANT_DAMPENER;
        return new PhysicsProfile(portalTick, blockEffectsTick, soundPitchRng, kb);
    }
}

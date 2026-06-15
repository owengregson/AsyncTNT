package me.vexmc.asynctnt.common.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import me.vexmc.asynctnt.common.version.PhysicsProfile.KnockbackResist;

/** Pins the per-version delta table (research note 2.8 / 3.7). */
class PhysicsProfileTest {

    @Test
    void v1_17_1_legacyTickAndEnchantDampener() {
        PhysicsProfile p = PhysicsProfile.forVersion(1, 17, 1);
        assertFalse(p.hasPortalTick());
        assertFalse(p.hasBlockEffectsTick());
        assertTrue(p.soundPitchRng());
        assertEquals(KnockbackResist.ENCHANT_DAMPENER, p.knockbackResist());
    }

    @Test
    void v1_20_6_attributeButStillSoundPitchRng() {
        PhysicsProfile p = PhysicsProfile.forVersion(1, 20, 6);
        assertFalse(p.hasPortalTick());
        assertTrue(p.soundPitchRng());
        assertEquals(KnockbackResist.ATTRIBUTE, p.knockbackResist());
    }

    @Test
    void v1_21_4_modernTickNoSoundPitchRng() {
        PhysicsProfile p = PhysicsProfile.forVersion(1, 21, 4);
        assertTrue(p.hasPortalTick());
        assertTrue(p.hasBlockEffectsTick());
        assertFalse(p.soundPitchRng());
        assertEquals(KnockbackResist.ATTRIBUTE, p.knockbackResist());
    }

    @Test
    void yearSchemeIsModern() {
        PhysicsProfile p = PhysicsProfile.forVersion(26, 1, 2);
        assertTrue(p.hasPortalTick());
        assertTrue(p.hasBlockEffectsTick());
        assertFalse(p.soundPitchRng());
        assertEquals(KnockbackResist.ATTRIBUTE, p.knockbackResist());
    }
}

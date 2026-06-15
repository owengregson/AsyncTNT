package me.vexmc.asynctnt.common.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FallingBlockResolverTest {

    @Test
    void placesWhenReplaceableAndSurvivable() {
        FallingBlockResolver.Landing l =
                FallingBlockResolver.resolve(true, true, false, 5, 9);
        assertEquals(FallingBlockResolver.Outcome.PLACE, l.outcome());
        assertEquals(5, l.blockStateId());
    }

    @Test
    void dropsWhenPositionNotReplaceable() {
        FallingBlockResolver.Landing l =
                FallingBlockResolver.resolve(false, true, false, 5, 9);
        assertEquals(FallingBlockResolver.Outcome.DROP, l.outcome());
    }

    @Test
    void dropsWhenCannotSurvive() {
        FallingBlockResolver.Landing l =
                FallingBlockResolver.resolve(true, false, false, 5, 9);
        assertEquals(FallingBlockResolver.Outcome.DROP, l.outcome());
    }

    @Test
    void concretePowderSolidifiesIntoSolidStateOnPlace() {
        FallingBlockResolver.Landing l =
                FallingBlockResolver.resolve(true, true, true, 5, 9);
        assertEquals(FallingBlockResolver.Outcome.PLACE, l.outcome());
        assertEquals(9, l.blockStateId());
    }
}

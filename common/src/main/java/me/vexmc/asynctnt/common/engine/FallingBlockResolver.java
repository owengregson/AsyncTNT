package me.vexmc.asynctnt.common.engine;

/**
 * Pure decision for what a falling block does when it lands, reproducing
 * {@code FallingBlockEntity}'s landing branch against snapshot booleans
 * captured on the owning thread. Movement/trajectory is handled by
 * {@link MotionIntegrator}; this only decides place-vs-drop once landed.
 *
 * <p>Vanilla: when on the ground at position P, the block is placed iff the
 * block currently at P can be replaced and the falling block can survive there;
 * concrete powder over water solidifies into its solid form; otherwise it drops
 * as an item (when entity drops are enabled).
 */
public final class FallingBlockResolver {

    public enum Outcome {
        /** Set the (solidified, if concrete powder) block at the landing position. */
        PLACE,
        /** Drop the block as an item (or vanish, when drops are disabled). */
        DROP
    }

    /**
     * @param positionReplaceable          the block currently at the landing pos can be replaced
     * @param canSurviveHere               the falling block's state can survive at the landing pos
     * @param concretePowderSolidifies     the block is concrete powder over water (solidify on place)
     * @param solidBlockStateId            the block-state id to place (solidified id when applicable)
     */
    public record Landing(Outcome outcome, int blockStateId) {
    }

    private FallingBlockResolver() {
    }

    public static Landing resolve(boolean positionReplaceable,
                                  boolean canSurviveHere,
                                  boolean concretePowderSolidifies,
                                  int fallingBlockStateId,
                                  int solidifiedBlockStateId) {
        if (positionReplaceable && canSurviveHere) {
            int id = concretePowderSolidifies ? solidifiedBlockStateId : fallingBlockStateId;
            return new Landing(Outcome.PLACE, id);
        }
        return new Landing(Outcome.DROP, fallingBlockStateId);
    }
}

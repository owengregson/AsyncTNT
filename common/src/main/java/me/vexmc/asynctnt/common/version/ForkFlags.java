package me.vexmc.asynctnt.common.version;

/**
 * Opt-in "cannon stabilization" toggles (TacoSpigot/PandaSpigot lineage). All
 * default to false so AsyncTNT's out-of-the-box behaviour is byte-identical
 * vanilla; servers that want fork-style consistency enable them in config.
 *
 * @param zeroSpawnKick          zero the random ±0.02 horizontal spawn scatter
 * @param fixedFuse80            force every primed TNT's fuse to 80 ticks
 * @param deterministicRedstone  fixed redstone firing-direction order (W,E,N,S,D,U)
 * @param eastWestCollisionFix   force a stable X/Z collision-resolution order
 *                               (removes vanilla's |dx|<|dz| asymmetry)
 */
public record ForkFlags(boolean zeroSpawnKick,
                        boolean fixedFuse80,
                        boolean deterministicRedstone,
                        boolean eastWestCollisionFix) {

    public static final ForkFlags VANILLA = new ForkFlags(false, false, false, false);
}

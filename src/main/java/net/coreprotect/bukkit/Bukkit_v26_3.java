package net.coreprotect.bukkit;

import java.util.Set;

import org.bukkit.Material;

import net.coreprotect.model.BlockGroup;

/**
 * Bukkit adapter implementation for Minecraft 26.3.
 */
public class Bukkit_v26_3 extends Bukkit_v26_2 {

    public Bukkit_v26_3() {
        initializeBlockGroups();
    }

    private void initializeBlockGroups() {
        // Not covered by the flowers tag, and breaks with the block below it.
        addIfPresent(BlockGroup.TRACK_TOP, "RED_SHRUB");
        addIfPresent(BlockGroup.NON_ATTACHABLE, "RED_SHRUB");
        addIfPresent(BlockGroup.NATURAL_BLOCKS, "RED_SHRUB");

        // Breaks with the block it is attached to; faces away from it, like a wall torch.
        addIfPresent(BlockGroup.TRACK_SIDE, "SHELF_MUSHROOM");
        addIfPresent(BlockGroup.NON_ATTACHABLE, "SHELF_MUSHROOM");
        addIfPresent(BlockGroup.NATURAL_BLOCKS, "SHELF_MUSHROOM");

        // Not in the beds tag, so it is missing from the fixed list of beds.
        addIfPresent(BlockGroup.TRACK_SIDE, "STRAW_BED");

        // Potted poplar sapling
        addIfPresent(BlockGroup.TRACK_TOP, "POTTED_POPLAR_SAPLING");
        addIfPresent(BlockGroup.NON_ATTACHABLE, "POTTED_POPLAR_SAPLING");

        // Poplar wall hanging sign
        addIfPresent(BlockGroup.TRACK_SIDE, "POPLAR_WALL_HANGING_SIGN");

        // Poplar leaves
        addIfPresent(BlockGroup.NATURAL_BLOCKS, "RED_POPLAR_LEAVES");
        addIfPresent(BlockGroup.NATURAL_BLOCKS, "ORANGE_POPLAR_LEAVES");
        addIfPresent(BlockGroup.NATURAL_BLOCKS, "YELLOW_POPLAR_LEAVES");

        // Poplar shelf
        Material poplarShelf = Material.getMaterial("POPLAR_SHELF");
        if (poplarShelf != null) {
            SHELVES.add(poplarShelf);
            BlockGroup.CONTAINERS.add(poplarShelf);
        }
    }

    private static void addIfPresent(Set<Material> group, String materialName) {
        Material material = Material.getMaterial(materialName);
        if (material != null) {
            group.add(material);
        }
    }

}

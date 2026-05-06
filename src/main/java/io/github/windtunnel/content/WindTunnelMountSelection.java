package io.github.windtunnel.content;

import io.github.windtunnel.WindTunnelMod;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Stores the player's currently selected mount interface block or entity in persistent player
 * NBT data.
 * <p>
 * The bind workflow is two-step:
 * <ol>
 * <li>Select an interface block on the aircraft (or an entity) — this stores the selection.</li>
 * <li>Shift-use the mount stand — this consumes the stored selection to create a binding.</li>
 * </ol>
 * <p>
 * Selections are stored per-player in {@link Player#getPersistentData()} so they survive
 * relogs and dimension changes.
 */
@SuppressWarnings("null")
public final class WindTunnelMountSelection {
    /** Root NBT key for the mount selection data. */
    private static final String ROOT_KEY = WindTunnelMod.MOD_ID + "_mount_selection";
    private static final String TYPE_KEY = "Type";
    private static final String DIMENSION_KEY = "Dimension";
    private static final String POS_KEY = "Pos";
    private static final String ENTITY_UUID_KEY = "EntityUuid";
    private static final String FACING_KEY = "Facing";

    private WindTunnelMountSelection() {
    }

    /** Stores a block position selection in the player's persistent data. */
    public static void storeBlock(Player player, ResourceKey<Level> dimension, BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TYPE_KEY, SelectionType.BLOCK.serializedName());
        tag.putString(DIMENSION_KEY, dimension.location().toString());
        tag.putLong(POS_KEY, pos.asLong());
        player.getPersistentData().put(ROOT_KEY, tag);
    }

    /** Stores an entity selection (with facing) in the player's persistent data. */
    public static void storeEntity(Player player, ResourceKey<Level> dimension, UUID entityId, Direction facing) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TYPE_KEY, SelectionType.ENTITY.serializedName());
        tag.putString(DIMENSION_KEY, dimension.location().toString());
        tag.putUUID(ENTITY_UUID_KEY, entityId);
        tag.putString(FACING_KEY, facing.getName());
        player.getPersistentData().put(ROOT_KEY, tag);
    }

    /** Retrieves the player's stored selection, or null if none exists. */
    @Nullable
    public static Selection get(Player player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(ROOT_KEY, Tag.TAG_COMPOUND)) {
            return null;
        }

        CompoundTag tag = root.getCompound(ROOT_KEY);
        ResourceLocation location = ResourceLocation.tryParse(tag.getString(DIMENSION_KEY));
        SelectionType type = SelectionType.fromSerializedName(tag.getString(TYPE_KEY));
        if (location == null) {
            return null;
        }

        ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, location);
        if (type == SelectionType.ENTITY) {
            if (!tag.hasUUID(ENTITY_UUID_KEY)) {
                return null;
            }
            return Selection.entity(
                    dimension,
                    tag.getUUID(ENTITY_UUID_KEY),
                    readDirection(tag.getString(FACING_KEY), Direction.NORTH)
            );
        }

        if (!tag.contains(POS_KEY)) {
            return null;
        }
        return Selection.block(dimension, BlockPos.of(tag.getLong(POS_KEY)));
    }

    /** Clears the player's stored selection (e.g., after successful binding). */
    public static void clear(Player player) {
        player.getPersistentData().remove(ROOT_KEY);
    }

    private static Direction readDirection(String name, Direction fallback) {
        Direction direction = Direction.byName(name);
        return direction != null ? direction : fallback;
    }

    /**
     * Immutable snapshot of the player's current interface pick.
     *
     * @param dimension      The dimension where the selection was made.
     * @param type           BLOCK or ENTITY selection type.
     * @param pos            Block position (only for BLOCK type).
     * @param entityId       Entity UUID (only for ENTITY type).
     * @param entityFacing   Entity facing direction (only for ENTITY type).
     */
    public record Selection(ResourceKey<Level> dimension, SelectionType type, @Nullable BlockPos pos, @Nullable UUID entityId,
                            @Nullable Direction entityFacing) {
        public static Selection block(ResourceKey<Level> dimension, BlockPos pos) {
            return new Selection(dimension, SelectionType.BLOCK, pos, null, null);
        }

        public static Selection entity(ResourceKey<Level> dimension, UUID entityId, Direction facing) {
            return new Selection(dimension, SelectionType.ENTITY, null, entityId, facing);
        }

        public boolean isBlockSelection() {
            return type == SelectionType.BLOCK && pos != null;
        }

        public boolean isEntitySelection() {
            return type == SelectionType.ENTITY && entityId != null;
        }
    }

    /** The type of selection stored. */
    public enum SelectionType {
        BLOCK("block"),
        ENTITY("entity");

        private final String serializedName;

        SelectionType(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static SelectionType fromSerializedName(String name) {
            for (SelectionType type : values()) {
                if (type.serializedName.equals(name)) {
                    return type;
                }
            }
            return BLOCK;
        }
    }
}

package io.github.windtunnel.content;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.registry.WindTunnelBlocks;
import io.github.windtunnel.registry.WindTunnelItems;
import net.minecraft.world.ItemInteractionResult;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Measurement stand block for aerodynamic testing.
 * <p>
 * Interaction model:
 * <ul>
 * <li><b>Right-click</b> — Opens the mount configuration GUI (if the stand is not on an aircraft).</li>
 * <li><b>Shift-right-click</b> — If the player has a stored interface selection, performs the
 *     binding action; if the stand already has a binding, clears it.</li>
 * <li><b>Shift-right-click with interface item</b> — Consumes the stored selection to create a
 *     binding between the stand and the selected aircraft interface point.</li>
 * </ul>
 * <p>
 * The two-step binding workflow: select an interface block on the aircraft, then shift-use the
 * mount to bind them.
 */
@SuppressWarnings("null")
public class WindTunnelMountBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<WindTunnelMountBlock> CODEC = simpleCodec(WindTunnelMountBlock::new);
    /** Horizontal facing for model orientation. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final Map<Direction, VoxelShape> SHAPES = createShapes();

    public WindTunnelMountBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.getOrDefault(state.getValue(FACING), SHAPES.get(Direction.NORTH));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return SHAPES.getOrDefault(state.getValue(FACING), SHAPES.get(Direction.NORTH));
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    // ---- Rotation and placement ----

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WindTunnelMountBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        // No per-tick logic — the mount service is driven by the global physics tick.
        return null;
    }

    // ---- Interaction ----

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (CreateCompat.isCreateWrench(player.getMainHandItem())) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (!(level.getBlockEntity(pos) instanceof WindTunnelMountBlockEntity mount)) {
            return InteractionResult.PASS;
        }

        // Mounts placed on aircraft cannot be used as stands — they're part of the structure.
        if (mount.isPlacedOnAircraft()) {
            player.displayClientMessage(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount.must_not_be_on_aircraft"), true);
            return InteractionResult.CONSUME;
        }

        if (player.isShiftKeyDown()) {
            WindTunnelMountSelection.Selection selection = WindTunnelMountSelection.get(player);
            if (selection != null) {
                // Shift-use with a stored selection performs the binding action directly.
                return bindSelection(level, player, mount, selection);
            }

            if (mount.hasBinding()) {
                mount.clearBinding();
                player.displayClientMessage(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount.binding_cleared"), true);
                return InteractionResult.CONSUME;
            }
        }

        // Open the mount GUI for pose control and measurement readout.
        MenuProvider provider = new SimpleMenuProvider(
                (containerId, inventory, user) -> new WindTunnelMountMenu(containerId, pos),
                Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount")
        );
        player.openMenu(provider, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                                                  net.minecraft.world.InteractionHand hand, BlockHitResult hitResult) {
        if (!stack.is(WindTunnelItems.WIND_TUNNEL_MOUNT_INTERFACE_ITEM.get()) || !player.isShiftKeyDown()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }

        if (!(level.getBlockEntity(pos) instanceof WindTunnelMountBlockEntity mount)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (mount.isPlacedOnAircraft()) {
            player.displayClientMessage(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount.must_not_be_on_aircraft"), true);
            return ItemInteractionResult.CONSUME;
        }

        WindTunnelMountSelection.Selection selection = WindTunnelMountSelection.get(player);
        if (selection == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        bindSelection(level, player, mount, selection);
        return ItemInteractionResult.CONSUME;
    }

    private InteractionResult bindSelection(Level level, Player player, WindTunnelMountBlockEntity mount, WindTunnelMountSelection.Selection selection) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.CONSUME;
        }

        if (!selection.dimension().equals(level.dimension())) {
            player.displayClientMessage(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount.selection_wrong_dimension"), true);
            return InteractionResult.CONSUME;
        }

        if (selection.isEntitySelection()) {
            Entity selectedEntity = serverLevel.getEntity(selection.entityId());
            if (selectedEntity == null || !selectedEntity.isAlive()) {
                WindTunnelMountSelection.clear(player);
                player.displayClientMessage(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount.selection_entity_invalid"), true);
                return InteractionResult.CONSUME;
            }

            mount.bindEntity(serverLevel, selectedEntity, selection.entityFacing() == null ? Direction.NORTH : selection.entityFacing());
            WindTunnelMountSelection.clear(player);
            player.displayClientMessage(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount.bound"), true);
            return InteractionResult.CONSUME;
        }

        if (!selection.isBlockSelection()) {
            WindTunnelMountSelection.clear(player);
            player.displayClientMessage(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount.selection_invalid"), true);
            return InteractionResult.CONSUME;
        }

        BlockState selectedState = level.getBlockState(selection.pos());
        if (!(selectedState.getBlock() instanceof WindTunnelMountInterfaceBlock)) {
            WindTunnelMountSelection.clear(player);
            player.displayClientMessage(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount.selection_invalid"), true);
            return InteractionResult.CONSUME;
        }

        if (!(dev.ryanhcode.sable.Sable.HELPER.getContaining(level, selection.pos()) instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel subLevel)) {
            player.displayClientMessage(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount.selection_not_aircraft"), true);
            return InteractionResult.CONSUME;
        }

        // Store both the target sublevel and the interface block used as the anchor point.
        mount.bind(serverLevel, subLevel.getUniqueId(), selection.pos(), selectedState.getValue(WindTunnelMountInterfaceBlock.FACING));
        WindTunnelMountSelection.clear(player);
        player.displayClientMessage(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount.bound"), true);
        return InteractionResult.CONSUME;
    }

    private static Map<Direction, VoxelShape> createShapes() {
        VoxelShape northShape = createNorthShape();
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.NORTH, northShape);
        shapes.put(Direction.EAST, rotateHorizontal(northShape, Direction.EAST));
        shapes.put(Direction.SOUTH, rotateHorizontal(northShape, Direction.SOUTH));
        shapes.put(Direction.WEST, rotateHorizontal(northShape, Direction.WEST));
        return shapes;
    }

    private static VoxelShape createNorthShape() {
        return Shapes.or(
                Block.box(0.0D, 0.0D, 1.0D, 16.0D, 2.0D, 16.0D),
                Block.box(0.0D, 2.0D, 2.0D, 1.0D, 15.0D, 16.0D),
                Block.box(15.0D, 2.0D, 2.0D, 16.0D, 15.0D, 16.0D),
                Block.box(1.0D, 2.0D, 15.0D, 15.0D, 15.0D, 16.0D),
                Block.box(1.0D, 2.0D, 3.0D, 15.0D, 9.0D, 4.0D),
                Block.box(1.0D, 9.0D, 3.0D, 15.0D, 11.0D, 16.0D)
        );
    }

    private static VoxelShape rotateHorizontal(VoxelShape shape, Direction direction) {
        if (direction == Direction.NORTH) {
            return shape;
        }

        VoxelShape rotated = Shapes.empty();
        for (AABB box : shape.toAabbs()) {
            rotated = Shapes.or(rotated, rotateBox(box, direction));
        }
        return rotated.optimize();
    }

    private static VoxelShape rotateBox(AABB box, Direction direction) {
        return switch (direction) {
            case EAST -> Shapes.create(new AABB(
                    1.0D - box.maxZ,
                    box.minY,
                    box.minX,
                    1.0D - box.minZ,
                    box.maxY,
                    box.maxX
            ));
            case SOUTH -> Shapes.create(new AABB(
                    1.0D - box.maxX,
                    box.minY,
                    1.0D - box.maxZ,
                    1.0D - box.minX,
                    box.maxY,
                    1.0D - box.minZ
            ));
            case WEST -> Shapes.create(new AABB(
                    box.minZ,
                    box.minY,
                    1.0D - box.maxX,
                    box.maxZ,
                    box.maxY,
                    1.0D - box.minX
            ));
            default -> Shapes.create(box);
        };
    }
}

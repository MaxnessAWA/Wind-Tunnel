package io.github.windtunnel.content;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntityTicker;
import io.github.windtunnel.registry.WindTunnelBlockEntities;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * User-facing controller block for a connected tunnel network.
 * <p>
 * Interaction model:
 * <ul>
 * <li><b>Shift-right-click</b> — Toggles the {@code ENABLED} state without opening the GUI,
 *     providing a quick redstone-like toggle path.</li>
 * <li><b>Right-click</b> — Opens the controller configuration GUI.</li>
 * <li><b>Redstone signal</b> — The {@code POWERED} property mirrors redstone input and acts as
 *     an external enable source. When powered by redstone, the controller contributes "active"
 *     status to the merged cluster state even if {@code ENABLED} is false.</li>
 * </ul>
 * <p>
 * The block implements {@link IWrenchable} for Create wrench rotation compatibility. All state
 * changes trigger {@link WindTunnelNetwork} refreshes to propagate settings to connected tunnels.
 */
@SuppressWarnings("null")
public class WindTunnelControllerBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<WindTunnelControllerBlock> CODEC = simpleCodec(WindTunnelControllerBlock::new);
    /** Horizontal facing for model orientation. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** Whether the block is receiving redstone power. */
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");
    /** User-toggled enable state (shift-right-click). */
    public static final BooleanProperty ENABLED = BooleanProperty.create("enabled");
    private static final VoxelShape SHAPE = Shapes.block();

    public WindTunnelControllerBlock() {
        this(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_ORANGE)
                .strength(3.5F, 6.0F)
                .sound(SoundType.COPPER)
                .noOcclusion()
                .requiresCorrectToolForDrops());
    }

    public WindTunnelControllerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, net.minecraft.core.Direction.NORTH)
                .setValue(POWERED, Boolean.FALSE)
                .setValue(ENABLED, Boolean.FALSE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED, ENABLED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(POWERED, Boolean.FALSE)
                .setValue(ENABLED, Boolean.FALSE);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WindTunnelControllerBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (blockEntityType != WindTunnelBlockEntities.WIND_TUNNEL_CONTROLLER.get()) {
            return null;
        }
        // Uses SmartBlockEntityTicker for Create behaviour support (no behaviours are active yet).
        return new SmartBlockEntityTicker<>();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        // Let Create wrenches handle rotation without opening the GUI.
        if (CreateCompat.isCreateWrench(player.getMainHandItem())) {
            return InteractionResult.PASS;
        }

        // Shift-right-click: fast toggle without GUI
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }

            level.setBlock(pos, state.cycle(ENABLED), Block.UPDATE_CLIENTS);
            WindTunnelNetwork.refreshFromController(level, pos);
            return InteractionResult.CONSUME;
        }

        // Normal right-click: open the configuration GUI
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        MenuProvider provider = new SimpleMenuProvider(
                (containerId, inventory, user) -> new WindTunnelControllerMenu(containerId, pos),
                Component.translatable("block.windtunnel.wind_tunnel_controller")
        );
        player.openMenu(provider, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                                  Player player, net.minecraft.world.InteractionHand hand,
                                                                  BlockHitResult hitResult) {
        // Pass item interactions through to useWithoutItem for consistency.
        return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);

        // Sync redstone power state and trigger network refresh on changes.
        boolean powered = level.hasNeighborSignal(pos);
        if (state.getValue(POWERED) != powered) {
            level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
            WindTunnelNetwork.refreshFromController(level, pos);
        }
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);

        if (!level.isClientSide && !state.is(oldState.getBlock())) {
            // Sync initial redstone power state and trigger cluster refresh.
            boolean powered = level.hasNeighborSignal(pos);
            if (state.getValue(POWERED) != powered) {
                level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
            }
            WindTunnelNetwork.refreshFromController(level, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (!level.isClientSide) {
                // When a controller is removed, adjacent networks need to re-scan — they may
                // split into separate clusters or fall back to local redstone-only power.
                WindTunnelNetwork.refreshAfterControllerRemoved(level, pos);
            }
            super.onRemove(state, level, pos, newState, isMoving);
            return;
        }

        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        InteractionResult result = IWrenchable.super.onWrenched(state, context);
        if (!result.consumesAction()) {
            return result;
        }

        Level level = context.getLevel();
        if (!level.isClientSide) {
            WindTunnelNetwork.refreshFromController(level, context.getClickedPos());
        }
        return result;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}

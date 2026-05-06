package io.github.windtunnel.content;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import io.github.windtunnel.registry.WindTunnelBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Basic wind tunnel segment block.
 * <p>
 * The wind tunnel projects an airflow volume in the direction it faces when powered (by redstone
 * or by a connected controller). The block itself mostly owns placement/state management and
 * delegates airflow logic to its block entity.
 * <p>
 * State properties:
 * <ul>
 * <li>{@code FACING} — The direction the tunnel faces (and projects airflow toward).</li>
 * <li>{@code POWERED} — Whether the segment is currently active (by redstone or controller).</li>
 * </ul>
 * <p>
 * The block implements {@link IWrenchable} so Create wrenches can rotate it, and triggers network
 * rescans on placement, removal, wrench, and neighbor changes.
 */
@SuppressWarnings("null")
public class WindTunnelBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<WindTunnelBlock> CODEC = simpleCodec(WindTunnelBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");
    private static final VoxelShape SHAPE = Shapes.block();

    public WindTunnelBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, Boolean.FALSE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // Uses the standard baked model pipeline (static shell) plus a BER for the animated fan.
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED);
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
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Keep the reused fan model aligned with Create's placement convention. The actual airflow
        // direction is resolved separately by the block entity.
        return this.defaultBlockState()
                .setValue(FACING, context.getNearestLookingDirection().getOpposite())
                .setValue(POWERED, Boolean.FALSE);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WindTunnelBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (blockEntityType != WindTunnelBlockEntities.WIND_TUNNEL.get()) {
            return null;
        }

        // Only the client needs a ticker — for particle spawning. The server airflow state is
        // driven by the network refresh path, not a per-tick polling loop.
        return level.isClientSide
                ? createTickerHelper(blockEntityType, WindTunnelBlockEntities.WIND_TUNNEL.get(), WindTunnelBlockEntity::clientTick)
                : null;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);

        if (!level.isClientSide && !state.is(oldState.getBlock())) {
            // A newly placed segment may join an existing controller/tunnel cluster, so rescan both
            // itself and any touching controllers.
            WindTunnelNetwork.refreshTunnel(level, pos);
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = pos.relative(direction);
                if (level.getBlockState(neighborPos).getBlock() instanceof WindTunnelControllerBlock) {
                    WindTunnelNetwork.refreshFromController(level, neighborPos);
                }
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && !state.is(newState.getBlock())) {
            // When a segment is removed, adjacent networks need to re-scan to update their
            // connectivity and possibly split into separate clusters.
            WindTunnelNetwork.refreshAdjacentNetworks(level, pos);
        }

        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        InteractionResult result = IWrenchable.super.onWrenched(state, context);
        if (!result.consumesAction()) {
            return result;
        }

        // After wrench rotation, re-scan the network to update facing-dependent airflow.
        Level level = context.getLevel();
        if (!level.isClientSide) {
            BlockPos pos = context.getClickedPos();
            WindTunnelNetwork.refreshTunnel(level, pos);
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = pos.relative(direction);
                if (level.getBlockState(neighborPos).getBlock() instanceof WindTunnelControllerBlock) {
                    WindTunnelNetwork.refreshFromController(level, neighborPos);
                }
            }
        }
        return result;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);

        if (!level.isClientSide) {
            // Redstone power changes are folded into the same network refresh path as controller
            // state changes, so the tunnel always reflects the correct combined power state.
            WindTunnelNetwork.refreshTunnel(level, pos);
        }
    }
}

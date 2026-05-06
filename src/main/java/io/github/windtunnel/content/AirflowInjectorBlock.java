package io.github.windtunnel.content;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import io.github.windtunnel.WindTunnelMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Aircraft-mounted airflow injector block.
 * <p>
 * When installed on a Sable sublevel (i.e., an aircraft), this block applies a uniform relative
 * airflow to that entire physical structure, effectively giving the aircraft "wind tunnel
 * conditions in-place" — useful for testing aerodynamic behavior without needing a ground-based
 * wind tunnel setup.
 * <p>
 * The block's visual shape uses corner pillars to suggest a frame structure, while the static
 * housing uses the normal baked block-model pipeline. Only the animated center core is rendered
 * by the block entity renderer.
 */
public class AirflowInjectorBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<AirflowInjectorBlock> CODEC = simpleCodec(AirflowInjectorBlock::new);
    /** Direction the injector faces (determines which way it "blows" relative to the craft). */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    private static final VoxelShape SHAPE = createShape();

    public AirflowInjectorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        DirectionProperty facing = Objects.requireNonNull(FACING);
        this.registerDefaultState(Objects.requireNonNull(this.stateDefinition.any().setValue(facing, Direction.NORTH)));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(@NotNull BlockState state) {
        // The static housing now uses the normal block-model pipeline. Only the animated core is
        // drawn by the block entity renderer.
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getVisualShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getOcclusionShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos) {
        return SHAPE;
    }

    @Override
    protected boolean useShapeForLightOcclusion(@NotNull BlockState state) {
        return true;
    }

    @Override
    protected boolean propagatesSkylightDown(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos) {
        return true;
    }

    @Override
    protected float getShadeBrightness(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos) {
        return 1.0F;
    }

    @Override
    public BlockState rotate(@NotNull BlockState state, @NotNull Rotation rotation) {
        DirectionProperty facing = Objects.requireNonNull(FACING);
        return state.setValue(facing, Objects.requireNonNull(rotation.rotate(
                Objects.requireNonNull(state.getValue(facing)))));
    }

    @Override
    public BlockState mirror(@NotNull BlockState state, @NotNull Mirror mirror) {
        DirectionProperty facing = Objects.requireNonNull(FACING);
        return rotate(state, Objects.requireNonNull(mirror.getRotation(
                Objects.requireNonNull(state.getValue(facing)))));
    }

    @Override
    protected void createBlockStateDefinition(@NotNull StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(@NotNull BlockPlaceContext context) {
        DirectionProperty facing = Objects.requireNonNull(FACING);
        return this.defaultBlockState().setValue(facing,
                Objects.requireNonNull(context.getNearestLookingDirection().getOpposite()));
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new AirflowInjectorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NotNull Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> blockEntityType) {
        // No per-tick logic needed — the BER animation is driven by game time, and airflow state
        // is updated by the network payload handler.
        return null;
    }

    @Override
    protected InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                                               @NotNull Player player, @NotNull BlockHitResult hitResult) {
        // Let Create wrenches handle rotation without opening the GUI.
        if (CreateCompat.isCreateWrench(player.getMainHandItem())) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockPos blockPos = Objects.requireNonNull(pos);
        if (!(level.getBlockEntity(blockPos) instanceof AirflowInjectorBlockEntity injector)) {
            return InteractionResult.PASS;
        }

        // The injector GUI is only accessible when the block is installed on an aircraft.
        if (!injector.isInstalledOnAircraft()) {
            player.displayClientMessage(Objects.requireNonNull(Component.translatable("block." + WindTunnelMod.MOD_ID + ".airflow_injector.not_on_aircraft")), true);
            return InteractionResult.CONSUME;
        }

        MenuProvider provider = new SimpleMenuProvider(
                (containerId, inventory, user) -> new AirflowInjectorMenu(containerId, blockPos),
                Objects.requireNonNull(Component.translatable("block." + WindTunnelMod.MOD_ID + ".airflow_injector"))
        );
        player.openMenu(provider, blockPos);
        return InteractionResult.CONSUME;
    }

    @Override
    public void onPlace(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState oldState, boolean isMoving) {
        super.onPlace(Objects.requireNonNull(state), Objects.requireNonNull(level), Objects.requireNonNull(pos), Objects.requireNonNull(oldState), isMoving);

        BlockPos blockPos = Objects.requireNonNull(pos);
        if (level.isClientSide || !(level.getBlockEntity(blockPos) instanceof AirflowInjectorBlockEntity injector)) {
            return;
        }

        DirectionProperty facingProp = Objects.requireNonNull(FACING);
        // Trigger re-evaluation of sublevel bindings when placed or facing changes.
        if (!state.is(Objects.requireNonNull(oldState.getBlock()))
                || (oldState.hasProperty(facingProp) && oldState.getValue(facingProp) != state.getValue(facingProp))) {
            injector.onFacingChanged();
        }
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        InteractionResult result = IWrenchable.super.onWrenched(state, context);
        if (!result.consumesAction()) {
            return result;
        }

        BlockPos clickedPos = Objects.requireNonNull(context.getClickedPos());
        if (context.getLevel().getBlockEntity(clickedPos) instanceof AirflowInjectorBlockEntity injector) {
            injector.onFacingChanged();
        }
        return result;
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(@NotNull ItemStack stack, @NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                                                                  @NotNull Player player, @NotNull net.minecraft.world.InteractionHand hand,
                                                                  @NotNull BlockHitResult hitResult) {
        return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * Creates the visual shape — corner pillars suggesting a frame around the center core.
     * The collision shape is still a full block.
     */
    private static VoxelShape createShape() {
        VoxelShape corners = Objects.requireNonNull(Shapes.or(
                Objects.requireNonNull(Block.box(0.0D, 0.0D, 0.0D, 4.0D, 4.0D, 4.0D)),
                Objects.requireNonNull(Block.box(12.0D, 0.0D, 0.0D, 16.0D, 4.0D, 4.0D)),
                Objects.requireNonNull(Block.box(0.0D, 0.0D, 12.0D, 4.0D, 4.0D, 16.0D)),
                Objects.requireNonNull(Block.box(12.0D, 0.0D, 12.0D, 16.0D, 4.0D, 16.0D)),
                Objects.requireNonNull(Block.box(0.0D, 12.0D, 0.0D, 4.0D, 16.0D, 4.0D)),
                Objects.requireNonNull(Block.box(12.0D, 12.0D, 0.0D, 16.0D, 16.0D, 4.0D)),
                Objects.requireNonNull(Block.box(0.0D, 12.0D, 12.0D, 4.0D, 16.0D, 16.0D)),
                Objects.requireNonNull(Block.box(12.0D, 12.0D, 12.0D, 16.0D, 16.0D, 16.0D))
        ));
        VoxelShape shell = Objects.requireNonNull(Shapes.join(
                Objects.requireNonNull(Block.box(2.0D, 2.0D, 2.0D, 14.0D, 14.0D, 14.0D)),
                Objects.requireNonNull(Block.box(4.0D, 4.0D, 4.0D, 12.0D, 12.0D, 12.0D)),
                Objects.requireNonNull(BooleanOp.ONLY_FIRST)
        ));
        return Objects.requireNonNull(Shapes.or(corners, shell));
    }
}

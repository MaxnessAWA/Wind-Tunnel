package io.github.windtunnel.content;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.windtunnel.WindTunnelMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Anchor marker block placed on the aircraft for the two-step mount binding workflow.
 * <p>
 * Players select this block first (shift-right-click) so the mount knows which aircraft and
 * which local point to hold during aerodynamic testing. The selection is stored in persistent
 * player NBT data until the player returns to the mount stand and binds them.
 * <p>
 * The block can only be placed on Sable aircraft sublevels (not on the ground). Its visual shape
 * is a low-profile plate with a raised center — an unobtrusive marker on the aircraft surface.
 */
@SuppressWarnings("null")
public class WindTunnelMountInterfaceBlock extends BaseEntityBlock implements IWrenchable {
    public static final MapCodec<WindTunnelMountInterfaceBlock> CODEC = simpleCodec(WindTunnelMountInterfaceBlock::new);
    /** Direction the interface faces (defaults to UP for surface mounting). */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    /** Low-profile plate shape: base (0-8px) + raised center (4-12px at 8-9 height). */
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 8.0D, 16.0D),
            Block.box(4.0D, 8.0D, 4.0D, 12.0D, 9.0D, 12.0D)
    );

    public WindTunnelMountInterfaceBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    // ---- Rotation (supports full 6-axis facing, not just horizontal) ----

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    // ---- Wrench support ----

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        InteractionResult result = IWrenchable.super.onWrenched(state, context);
        if (!result.consumesAction()) {
            return result;
        }

        Level level = context.getLevel();
        if (!level.isClientSide) {
            // When the interface is wrenched on the aircraft, all stands bound to it must update
            // their local-to-stand axis mapping.
            BlockState rotatedState = level.getBlockState(context.getClickedPos());
            if (rotatedState.getBlock() instanceof WindTunnelMountInterfaceBlock) {
                WindTunnelMountService.updateBindingsForInterfaceRotation(level, context.getClickedPos(), rotatedState.getValue(FACING));
            }
        }
        return result;
    }

    // ---- Visual shapes ----

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return SHAPE;
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

    // ---- Interaction ----

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (CreateCompat.isCreateWrench(player.getMainHandItem())) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // Guard: the interface must be placed on a Sable aircraft sublevel.
        if (!(Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel)) {
            player.displayClientMessage(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount_interface.not_on_aircraft"), true);
            return InteractionResult.CONSUME;
        }

        // Persist the pick on the player so they can walk to the stand and bind later.
        WindTunnelMountSelection.storeBlock(player, level.dimension(), pos);
        player.displayClientMessage(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount_interface.selected"), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WindTunnelMountInterfaceBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return null;
    }
}

package io.github.windtunnel.content;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

/**
 * Vertical variant of the symmetric airfoil.
 *
 * <p>The chord still follows {@link #FACING}, but the aerodynamic span is vertical instead of
 * horizontal. This keeps the same lift/drag solver while rotating the lifting surface into a
 * vertical stabilizer plane.</p>
 */
public class VerticalSymmetricAirfoilBlock extends SymmetricAirfoilBlock {
    public static final MapCodec<VerticalSymmetricAirfoilBlock> CODEC = simpleCodec(VerticalSymmetricAirfoilBlock::new);
    private static final VoxelShape NORTH_SOUTH_SHAPE = Block.box(6.0D, 0.0D, 0.0D, 10.0D, 16.0D, 16.0D);
    private static final VoxelShape EAST_WEST_SHAPE = Block.box(0.0D, 0.0D, 6.0D, 16.0D, 16.0D, 10.0D);

    public VerticalSymmetricAirfoilBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull net.minecraft.core.BlockPos pos,
                                  @NotNull CollisionContext context) {
        return shapeForState(state);
    }

    @Override
    protected VoxelShape getCollisionShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull net.minecraft.core.BlockPos pos,
                                           @NotNull CollisionContext context) {
        return shapeForState(state);
    }

    @Override
    protected VoxelShape getVisualShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull net.minecraft.core.BlockPos pos,
                                        @NotNull CollisionContext context) {
        return shapeForState(state);
    }

    @Override
    protected VoxelShape getOcclusionShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull net.minecraft.core.BlockPos pos) {
        return shapeForState(state);
    }

    @Override
    protected Direction getSpanDirection(BlockState state) {
        return Direction.UP;
    }

    private static VoxelShape shapeForState(BlockState state) {
        Direction facing = state.getValue(FACING);
        return facing.getAxis() == Direction.Axis.X ? EAST_WEST_SHAPE : NORTH_SOUTH_SHAPE;
    }
}

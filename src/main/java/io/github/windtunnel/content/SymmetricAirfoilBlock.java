package io.github.windtunnel.content;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Symmetric airfoil block with a fixed quarter-chord aerodynamic center.
 *
 * <p>The block uses its facing as the local chord direction. A separate half selector controls
 * whether the span points to the block's local left or right side, which makes the generated
 * lift sign deterministic for mirrored placements without introducing a block entity.</p>
 */
public class SymmetricAirfoilBlock extends Block implements IWrenchable, BlockSubLevelLiftProvider {
    public static final MapCodec<SymmetricAirfoilBlock> CODEC = simpleCodec(SymmetricAirfoilBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final EnumProperty<SpanHalf> SPAN_HALF = EnumProperty.create("span_half", SpanHalf.class);
    private static final int PLACEMENT_HELPER_ID = PlacementHelpers.register(new PlacementHelper());
    private static final VoxelShape SHAPE = Block.box(0.0D, 6.0D, 0.0D, 16.0D, 10.0D, 16.0D);

    public SymmetricAirfoilBlock(BlockBehaviour.Properties properties) {
        super(properties);
        DirectionProperty facing = Objects.requireNonNull(FACING);
        EnumProperty<SpanHalf> spanHalf = Objects.requireNonNull(SPAN_HALF);
        this.registerDefaultState(Objects.requireNonNull(this.stateDefinition.any()
                .setValue(facing, Direction.NORTH)
                .setValue(spanHalf, SpanHalf.LEFT)));
    }

    @Override
    public MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(@NotNull StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SPAN_HALF);
    }

    @Override
    public BlockState getStateForPlacement(@NotNull BlockPlaceContext context) {
        Direction facing = context.getNearestLookingDirection().getOpposite();
        if (facing.getAxis().isVertical()) {
            facing = context.getHorizontalDirection().getOpposite();
        }
        SpanHalf spanHalf = spanHalfForPlacement(context, facing);
        DirectionProperty facingProp = Objects.requireNonNull(FACING);
        EnumProperty<SpanHalf> spanHalfProp = Objects.requireNonNull(SPAN_HALF);
        return this.defaultBlockState()
                .setValue(facingProp, Objects.requireNonNull(facing))
                .setValue(spanHalfProp, Objects.requireNonNull(spanHalf));
    }

    @Override
    public BlockState rotate(@NotNull BlockState state, @NotNull Rotation rotation) {
        DirectionProperty facingProp = Objects.requireNonNull(FACING);
        return state.setValue(facingProp, Objects.requireNonNull(rotation.rotate(
                Objects.requireNonNull(state.getValue(facingProp)))));
    }

    @Override
    public BlockState mirror(@NotNull BlockState state, @NotNull Mirror mirror) {
        DirectionProperty facingProp = Objects.requireNonNull(FACING);
        EnumProperty<SpanHalf> spanHalfProp = Objects.requireNonNull(SPAN_HALF);
        BlockState mirrored = rotate(state, Objects.requireNonNull(mirror.getRotation(
                Objects.requireNonNull(state.getValue(facingProp)))));
        if (mirror == Mirror.NONE) {
            return mirrored;
        }
        return mirrored.setValue(spanHalfProp,
                Objects.requireNonNull(mirrored.getValue(spanHalfProp)) == SpanHalf.LEFT ? SpanHalf.RIGHT : SpanHalf.LEFT);
    }

    @Override
    protected VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPE;
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
    protected ItemInteractionResult useItemOn(@NotNull ItemStack stack, @NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                                              @NotNull Player player, @NotNull InteractionHand hand, @NotNull BlockHitResult hitResult) {
        if (!player.isShiftKeyDown() && player.mayBuild()) {
            IPlacementHelper placementHelper = PlacementHelpers.get(PLACEMENT_HELPER_ID);
            if (placementHelper.matchesItem(stack)) {
                placementHelper.getOffset(player, level, state, pos, hitResult)
                        .placeInWorld(level, Objects.requireNonNull((BlockItem) stack.getItem()), player, hand, hitResult);
                return ItemInteractionResult.SUCCESS;
            }
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public @NotNull Direction sable$getNormal(BlockState state) {
        return Objects.requireNonNull(surfaceNormal(state));
    }

    @Override
    @SuppressWarnings("null")
    public void sable$contributeLiftAndDrag(LiftProviderContext ctx, ServerSubLevel subLevel, @Nullable Pose3d localPose,
                                            double timeStep, Vector3dc linearVelocity, Vector3dc angularVelocity,
                                            Vector3d linearImpulse, Vector3d angularImpulse,
                                            @Nullable LiftProviderGroup group) {
        BlockState state = ctx.state();
        Direction chord = state.getValue(Objects.requireNonNull(FACING));
        Direction span = spanDirection(chord, state.getValue(Objects.requireNonNull(SPAN_HALF)));
        SymmetricAirfoilAerodynamics.contribute(
                new SymmetricAirfoilAerodynamics.BlockSubLevelAirfoilContext(ctx.pos(), state, chord, span),
                subLevel,
                localPose,
                timeStep,
                linearVelocity,
                angularVelocity,
                linearImpulse,
                angularImpulse,
                group
        );
    }

    private static SpanHalf spanHalfForPlacement(BlockPlaceContext context, Direction facing) {
        Direction playerHorizontal = context.getHorizontalDirection();
        if (playerHorizontal.getAxis() == facing.getAxis()) {
            return SpanHalf.LEFT;
        }
        Vector3d facingVec = directionVector(facing);
        Vector3d playerVec = directionVector(playerHorizontal);
        Vector3d cross = facingVec.cross(playerVec, new Vector3d());
        return cross.y() >= 0.0D ? SpanHalf.LEFT : SpanHalf.RIGHT;
    }

    private static Direction spanDirection(Direction chord, SpanHalf spanHalf) {
        return spanHalf == SpanHalf.LEFT
                ? chord.getClockWise(Direction.Axis.Y)
                : chord.getCounterClockWise(Direction.Axis.Y);
    }

    public static Direction surfaceNormal(BlockState state) {
        Direction chord = state.getValue(Objects.requireNonNull(FACING));
        Direction span = spanDirection(chord, state.getValue(Objects.requireNonNull(SPAN_HALF)));
        return directionFromVector(new Vector3d(directionVector(span)).cross(directionVector(chord)));
    }

    public static Direction.Axis surfaceNormalAxis(BlockState state) {
        return surfaceNormal(state).getAxis();
    }

    private static Direction directionFromVector(Vector3d vector) {
        return Direction.getNearest(vector.x(), vector.y(), vector.z());
    }

    private static Vector3d directionVector(Direction direction) {
        return new Vector3d(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static final class PlacementHelper implements IPlacementHelper {
        @Override
        public Predicate<ItemStack> getItemPredicate() {
            return stack -> stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() instanceof SymmetricAirfoilBlock;
        }

        @Override
        public Predicate<BlockState> getStatePredicate() {
            return state -> state.getBlock() instanceof SymmetricAirfoilBlock;
        }

        @Override
        public PlacementOffset getOffset(@NotNull Player player, @NotNull Level world, @NotNull BlockState state, @NotNull BlockPos pos,
                                         @NotNull BlockHitResult ray) {
            List<Direction> directions = IPlacementHelper.orderedByDistanceExceptAxis(
                    Objects.requireNonNull(pos),
                    Objects.requireNonNull(ray.getLocation()),
                    Objects.requireNonNull(surfaceNormalAxis(state)),
                    direction -> world.getBlockState(Objects.requireNonNull(pos.relative(
                            Objects.requireNonNull(direction)))).canBeReplaced()
            );

            if (directions.isEmpty()) {
                return PlacementOffset.fail();
            }

            Direction firstDir = Objects.requireNonNull(directions.get(0));
            DirectionProperty facingProp = Objects.requireNonNull(FACING);
            EnumProperty<SpanHalf> spanHalfProp = Objects.requireNonNull(SPAN_HALF);
            return PlacementOffset.success(
                    Objects.requireNonNull(pos.relative(firstDir)),
                    placedState -> placedState
                            .setValue(facingProp, Objects.requireNonNull(state.getValue(facingProp)))
                            .setValue(spanHalfProp, Objects.requireNonNull(state.getValue(spanHalfProp)))
            );
        }
    }

    public enum SpanHalf implements StringRepresentable {
        LEFT("left"),
        RIGHT("right");

        private final String serializedName;

        SpanHalf(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }
}

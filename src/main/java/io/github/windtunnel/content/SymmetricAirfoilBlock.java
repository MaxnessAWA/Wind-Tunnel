package io.github.windtunnel.content;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.windtunnel.registry.WindTunnelBlocks;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Symmetric airfoil block with a quarter-chord aerodynamic center.
 *
 * <p>The block uses its facing as the local chord direction. Its span is fixed horizontally so
 * the symmetric surface has a single meaningful state per facing. Same-kind airfoils connected
 * along that chord direction are treated as one aerodynamic section.</p>
 */
public class SymmetricAirfoilBlock extends Block implements IWrenchable, BlockSubLevelLiftProvider {
    public static final MapCodec<SymmetricAirfoilBlock> CODEC = simpleCodec(SymmetricAirfoilBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final int PLACEMENT_HELPER_ID = PlacementHelpers.register(new PlacementHelper());
    private static final VoxelShape SHAPE = Block.box(0.0D, 6.0D, 0.0D, 16.0D, 10.0D, 16.0D);

    public SymmetricAirfoilBlock(BlockBehaviour.Properties properties) {
        super(properties);
        DirectionProperty facing = Objects.requireNonNull(FACING);
        this.registerDefaultState(Objects.requireNonNull(this.stateDefinition.any()
                .setValue(facing, Direction.NORTH)));
    }

    @Override
    public MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(@NotNull StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(@NotNull BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        DirectionProperty facingProp = Objects.requireNonNull(FACING);
        return this.defaultBlockState()
                .setValue(facingProp, Objects.requireNonNull(facing));
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
        return rotate(state, Objects.requireNonNull(mirror.getRotation(
                Objects.requireNonNull(state.getValue(facingProp)))));
    }

    @Override
    protected VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPE;
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
        if (stack.getItem() instanceof DyeItem dyeItem) {
            ItemInteractionResult dyeResult = dye(level, pos, state, player, stack, Objects.requireNonNull(dyeItem.getDyeColor()));
            if (dyeResult != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                return dyeResult;
            }
        }

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

    private static ItemInteractionResult dye(Level level, BlockPos pos, BlockState state, Player player, ItemStack stack, DyeColor color) {
        DyeColor currentColor = WindTunnelBlocks.airfoilColor(state.getBlock());
        if (currentColor == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (currentColor == color) {
            return ItemInteractionResult.CONSUME;
        }

        Block recoloredBlock = WindTunnelBlocks.recoloredAirfoil(state.getBlock(), color);
        if (recoloredBlock == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        BlockState recolored = recoloredBlock.defaultBlockState()
                .setValue(Objects.requireNonNull(FACING), state.getValue(Objects.requireNonNull(FACING)));

        if (!level.isClientSide()) {
            level.setBlock(pos, recolored, Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    public @NotNull Direction sable$getNormal(BlockState state) {
        return Objects.requireNonNull(surfaceNormal(state));
    }

    protected Direction getSpanDirection(BlockState state) {
        Direction chord = state.getValue(Objects.requireNonNull(FACING));
        return horizontalSpanDirection(chord);
    }

    protected Direction getSurfaceNormal(BlockState state) {
        Direction chord = state.getValue(Objects.requireNonNull(FACING));
        Direction span = getSpanDirection(state);
        return directionFromVector(new Vector3d(directionVector(span)).cross(directionVector(chord)));
    }

    protected Direction.Axis getSurfaceNormalAxis(BlockState state) {
        return getSurfaceNormal(state).getAxis();
    }

    @Override
    @SuppressWarnings("null")
    public void sable$contributeLiftAndDrag(LiftProviderContext ctx, ServerSubLevel subLevel, @Nullable Pose3d localPose,
                                            double timeStep, Vector3dc linearVelocity, Vector3dc angularVelocity,
                                            Vector3d linearImpulse, Vector3d angularImpulse,
                                            @Nullable LiftProviderGroup group) {
        BlockState state = ctx.state();
        Direction chord = state.getValue(Objects.requireNonNull(FACING));
        Direction span = getSpanDirection(state);
        ConnectedAirfoilSection section = resolveConnectedAirfoilSection(ctx, subLevel, localPose, state, chord);
        if (section == null) {
            return;
        }
        SymmetricAirfoilAerodynamics.contribute(
                new SymmetricAirfoilAerodynamics.BlockSubLevelAirfoilContext(
                        section.frontPos(),
                        state,
                        chord,
                        span,
                        section.chordBlocks()
                ),
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

    @Nullable
    private ConnectedAirfoilSection resolveConnectedAirfoilSection(LiftProviderContext ctx, ServerSubLevel subLevel,
                                                                   @Nullable Pose3d localPose, BlockState state,
                                                                   Direction chord) {
        BlockPos pos = ctx.pos();
        BlockGetter localBlocks = localPose == null ? subLevel.getLevel() : AirfoilContraptionContext.getBlockGetter(ctx);
        if (localBlocks == null) {
            return new ConnectedAirfoilSection(pos, 1);
        }
        if (isConnectedAirfoil(localBlocks, pos.relative(chord), state, chord)) {
            return null;
        }

        int chordBlocks = 1;
        BlockPos cursor = pos;
        Direction tailDirection = chord.getOpposite();
        while (true) {
            BlockPos next = cursor.relative(tailDirection);
            if (!isConnectedAirfoil(localBlocks, next, state, chord)) {
                break;
            }
            cursor = next;
            chordBlocks++;
        }

        return new ConnectedAirfoilSection(pos, chordBlocks);
    }

    private static boolean isConnectedAirfoil(BlockGetter localBlocks, BlockPos pos, BlockState origin, Direction chord) {
        BlockState candidate = localBlocks.getBlockState(pos);
        return isSameAirfoilKind(origin, candidate)
                && candidate.hasProperty(FACING)
                && candidate.getValue(FACING) == chord;
    }

    private static boolean isSameAirfoilKind(BlockState origin, BlockState candidate) {
        if (!(candidate.getBlock() instanceof SymmetricAirfoilBlock)) {
            return false;
        }
        return (origin.getBlock() instanceof VerticalSymmetricAirfoilBlock)
                == (candidate.getBlock() instanceof VerticalSymmetricAirfoilBlock);
    }

    private static Direction horizontalSpanDirection(Direction chord) {
        return switch (chord) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            case UP, DOWN -> throw new IllegalArgumentException("Airfoil facing must be horizontal: " + chord);
        };
    }

    public static Direction surfaceNormal(BlockState state) {
        if (state.getBlock() instanceof SymmetricAirfoilBlock airfoil) {
            return airfoil.getSurfaceNormal(state);
        }
        Direction chord = state.getValue(Objects.requireNonNull(FACING));
        Direction span = horizontalSpanDirection(chord);
        return directionFromVector(new Vector3d(directionVector(span)).cross(directionVector(chord)));
    }

    public static Direction.Axis surfaceNormalAxis(BlockState state) {
        if (state.getBlock() instanceof SymmetricAirfoilBlock airfoil) {
            return airfoil.getSurfaceNormalAxis(state);
        }
        return surfaceNormal(state).getAxis();
    }

    private static Direction directionFromVector(Vector3d vector) {
        return Direction.getNearest(vector.x(), vector.y(), vector.z());
    }

    private static Vector3d directionVector(Direction direction) {
        return new Vector3d(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private record ConnectedAirfoilSection(BlockPos frontPos, int chordBlocks) {
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
            return PlacementOffset.success(
                    Objects.requireNonNull(pos.relative(firstDir)),
                    placedState -> placedState
                            .setValue(facingProp, Objects.requireNonNull(state.getValue(facingProp)))
            );
        }
    }
}

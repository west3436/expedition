package io.github.west3436.expedition.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import io.github.west3436.expedition.mover.DryRun;
import io.github.west3436.expedition.mover.MoveJob;
import io.github.west3436.expedition.mover.MoveRefusal;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.MoveResult;
import io.github.west3436.expedition.mover.Obstacle;
import io.github.west3436.expedition.mover.ShipMover;
import io.github.west3436.expedition.mover.ShipSurvey;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /expedition move}, an operator's way to drive the mover in a real world, for testing and
 * bug reports. It is not how players move ships: it ignores fuel, thrust and ownership.
 *
 * <pre>
 * /expedition move &lt;from&gt; &lt;to&gt; &lt;dimension&gt; &lt;destination&gt; [dry-run]
 * </pre>
 *
 * <p>{@code from} and {@code to} are two opposite corners of the box, in the command source's own
 * level (so {@code /execute in} picks the source dimension). {@code dimension} is the level the box
 * goes to, and {@code destination} is where the box's lowest corner lands there. Relative
 * coordinates are the source's, as in every vanilla command.
 *
 * <p>A move reports how it ended when its {@link MoveJob#result()} completes, which for a large box
 * is some ticks after the command returns: the counts, the mass and the ticks taken, or every
 * reason it was refused with the obstacles behind each. {@code dry-run} moves nothing and reports
 * the same reasons, or what the move would carry. Every message is in the mod's {@code en_us.json}.
 *
 * <p>It needs permission level 2, the level of {@code /fill}, {@code /clone} and {@code /tp}: it does
 * no more than they can together, and command blocks and GameTests can run it.
 */
public final class MoveCommand {
    /** The permission level the command needs: the game masters', as for {@code /clone}. */
    public static final int PERMISSION_LEVEL = Commands.LEVEL_GAMEMASTERS;

    /** How many obstacles of one reason are listed before "...and N more". */
    public static final int OBSTACLES_SHOWN = 8;

    private static final String KEY = "expedition.command.move.";

    private static final DynamicCommandExceptionType NOWHERE =
            new DynamicCommandExceptionType(at -> Component.translatable(KEY + "nowhere", at));

    private static final DynamicCommandExceptionType BUILD_HEIGHT =
            new DynamicCommandExceptionType(level -> Component.translatable(KEY + "build_height", level));

    private MoveCommand() {}

    /**
     * Listens for the server's command registration.
     *
     * @param gameBus NeoForge's game event bus
     */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(MoveCommand::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    /**
     * Adds the command to a dispatcher. Only {@code move} carries the permission level, so other
     * {@code /expedition} subcommands can have their own.
     *
     * @param dispatcher the server's dispatcher
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("expedition")
                .then(Commands.literal("move")
                        .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                .then(Commands.argument("destination", BlockPosArgument.blockPos())
                                                        .executes(context -> run(context, false))
                                                        .then(Commands.literal("dry-run")
                                                                .executes(context -> run(context, true)))))))));
    }

    private static int run(CommandContext<CommandSourceStack> context, boolean dryRun) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        // Not getLoadedBlockPos: a box or destination that isn't loaded is the mover's to refuse, with
        // every chunk named.
        BoundingBox box = BoundingBox.fromCorners(
                BlockPosArgument.getBlockPos(context, "from"), BlockPosArgument.getBlockPos(context, "to"));
        ServerLevel from = source.getLevel();
        ServerLevel to = DimensionArgument.getDimension(context, "dimension");
        BlockPos origin = BlockPosArgument.getBlockPos(context, "destination");
        MoveRequest request = new MoveRequest(from, box, to, origin);
        // The mover throws on these, since no world could let them go ahead; the operator is told why.
        if (request.sameLevel() && request.offset().equals(Vec3i.ZERO)) {
            throw NOWHERE.create(position(from.dimension(), origin));
        }
        requireInside(from, box);
        requireInside(to, request.destination());

        if (dryRun) {
            return dryRun(source, request);
        }
        MoveJob job = ShipMover.start(request);
        CompletableFuture<MoveResult> result = job.result();
        if (!result.isDone()) {
            source.sendSuccess(
                    () -> Component.translatable(
                            KEY + "started",
                            position(from.dimension(), lowest(box)),
                            position(to.dimension(), origin),
                            dimension(to.dimension())),
                    true);
        }
        result.whenComplete((moved, error) -> report(source, request, moved, error));
        if (!result.isDone()) {
            return 1;
        }
        return result.isCompletedExceptionally() || !result.join().moved() ? 0 : 1;
    }

    private static void requireInside(ServerLevel level, BoundingBox box) throws CommandSyntaxException {
        if (level.isOutsideBuildHeight(box.minY()) || level.isOutsideBuildHeight(box.maxY())) {
            throw BUILD_HEIGHT.create(dimension(level.dimension()));
        }
    }

    private static int dryRun(CommandSourceStack source, MoveRequest request) {
        DryRun dry = ShipMover.dryRun(request);
        if (dry.wouldMove()) {
            ShipSurvey survey = ShipSurvey.of(request.from(), request.box());
            source.sendSuccess(
                    () -> Component.translatable(
                            KEY + "dry_run.clear", survey.blocks(), survey.blockEntities(), survey.mass()),
                    false);
            return 1;
        }
        source.sendFailure(
                Component.translatable(KEY + "dry_run.refused", dry.obstacles().size()));
        reasons(source, dry);
        return 0;
    }

    /** Says how a move ended, once it has: on the server thread, as the move's result completes. */
    private static void report(CommandSourceStack source, MoveRequest request, MoveResult result, Throwable error) {
        if (error != null) {
            source.sendFailure(Component.translatable(KEY + "failed", String.valueOf(error)));
            return;
        }
        if (result.moved()) {
            source.sendSuccess(
                    () -> Component.translatable(
                            KEY + "moved",
                            position(request.to().dimension(), request.origin()),
                            dimension(request.to().dimension()),
                            result.blocks(),
                            result.blockEntities(),
                            result.entities(),
                            result.mass(),
                            result.ticks(),
                            String.format(Locale.ROOT, "%.1f", result.nanos() / 1e6)),
                    true);
            if (result.entitiesLeft() > 0) {
                source.sendFailure(Component.translatable(KEY + "entities_left", result.entitiesLeft()));
            }
            return;
        }
        String code = result.refusal().orElseThrow();
        source.sendFailure(Component.translatable(KEY + "refused", MoveRefusal.message(code), code));
        // The refusal names the first reason; a dry run of the same move, now, names every one. The
        // move changed nothing, so the world is as it was refused, unless it changed while waiting.
        DryRun dry = ShipMover.dryRun(request);
        if (!dry.wouldMove()) {
            reasons(source, dry);
        }
    }

    /** Each reason, with its message, and the first of its obstacles. */
    private static void reasons(CommandSourceStack source, DryRun dry) {
        for (String code : dry.codes()) {
            List<Obstacle> obstacles = dry.obstacles(code);
            List<Obstacle> listed = obstacles.stream()
                    .filter(o -> !(o instanceof Obstacle.OfMove))
                    .toList();
            source.sendSuccess(
                    () -> Component.translatable(
                                    KEY + "reason",
                                    Component.literal(code).withStyle(ChatFormatting.GOLD),
                                    MoveRefusal.message(code),
                                    obstacles.size())
                            .withStyle(ChatFormatting.YELLOW),
                    false);
            for (Obstacle obstacle : listed.subList(0, Math.min(listed.size(), OBSTACLES_SHOWN))) {
                Component line = describe(obstacle);
                source.sendSuccess(() -> line, false);
            }
            if (listed.size() > OBSTACLES_SHOWN) {
                int more = listed.size() - OBSTACLES_SHOWN;
                source.sendSuccess(() -> Component.translatable(KEY + "more", more), false);
            }
        }
    }

    private static Component describe(Obstacle obstacle) {
        return switch (obstacle) {
            case Obstacle.AtBlock block ->
                Component.translatable(
                        KEY + "obstacle.block",
                        block.state().getBlock().getName(),
                        position(block.level(), block.pos()),
                        dimension(block.level()));
            case Obstacle.OfEntity entity ->
                Component.translatable(
                        KEY + "obstacle.entity",
                        entity.type().getDescription(),
                        uuid(entity.uuid()),
                        position(entity.level(), entity.position()),
                        dimension(entity.level()));
            case Obstacle.InChunk chunk ->
                Component.translatable(
                        KEY + "obstacle.chunk", chunk(chunk.level(), chunk.chunk()), dimension(chunk.level()));
            case Obstacle.OfMove move -> MoveRefusal.message(move.code());
        };
    }

    private static BlockPos lowest(BoundingBox box) {
        return new BlockPos(box.minX(), box.minY(), box.minZ());
    }

    /** A block position as vanilla shows one: in green brackets, a click away from a teleport there. */
    private static Component position(ResourceKey<Level> level, BlockPos pos) {
        return teleport(
                Component.translatable("chat.coordinates", pos.getX(), pos.getY(), pos.getZ()),
                level,
                pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    private static Component position(ResourceKey<Level> level, Vec3 pos) {
        String x = String.format(Locale.ROOT, "%.2f", pos.x);
        String y = String.format(Locale.ROOT, "%.2f", pos.y);
        String z = String.format(Locale.ROOT, "%.2f", pos.z);
        return teleport(Component.translatable("chat.coordinates", x, y, z), level, x + " " + y + " " + z);
    }

    /** A chunk's coordinates, a click away from a teleport to its middle. */
    private static Component chunk(ResourceKey<Level> level, ChunkPos chunk) {
        return teleport(
                Component.translatable(KEY + "chunk_coordinates", chunk.x, chunk.z),
                level,
                chunk.getMiddleBlockX() + " ~ " + chunk.getMiddleBlockZ());
    }

    private static Component teleport(MutableComponent text, ResourceKey<Level> level, String coordinates) {
        String command = "/execute in " + level.location() + " run tp @s " + coordinates;
        return ComponentUtils.wrapInSquareBrackets(text)
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.coordinates.tooltip"))));
    }

    /** An entity's id, a click away from the clipboard. */
    private static Component uuid(UUID uuid) {
        String id = uuid.toString();
        return ComponentUtils.wrapInSquareBrackets(Component.literal(id))
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, id))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.copy.click"))));
    }

    private static Component dimension(ResourceKey<Level> level) {
        return Component.literal(level.location().toString());
    }
}

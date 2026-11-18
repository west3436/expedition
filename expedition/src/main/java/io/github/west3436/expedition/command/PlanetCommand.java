package io.github.west3436.expedition.command;

import com.google.gson.JsonElement;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.JsonOps;
import io.github.west3436.expedition.planet.PlanetLoader;
import io.github.west3436.expedition.planet.PlanetProblem;
import io.github.west3436.expedition.planet.PlanetRegistry;
import io.github.west3436.expedition.planet.References;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.conditions.ConditionalOps;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /expedition planet validate}: reads every planet file the server's datapacks hold, as they
 * are on disk now, and reports each problem with its file and field, without a restart or a {@code
 * /reload}. It changes nothing: the planets loaded stay as they are until the next {@code /reload}.
 *
 * <p>It checks what a {@code /reload} would, file by file and between files, and each gas against
 * ZAS³'s, which fails a planet as it does after a load. It also checks that each dimension is one
 * of the server's levels and each structure and tag one its datapacks give; those are warnings,
 * since such a planet still loads. Every message is in the mod's {@code en_us.json}.
 *
 * <p>It needs permission level 2, the game masters', as {@code /expedition move} does. It only
 * reads, but it lists the server's datapack files.
 */
public final class PlanetCommand {
    /** The permission level the command needs: the game masters'. */
    public static final int PERMISSION_LEVEL = Commands.LEVEL_GAMEMASTERS;

    private static final String KEY = "expedition.command.planet.validate.";

    private PlanetCommand() {}

    /**
     * Listens for the server's command registration.
     *
     * @param gameBus NeoForge's game event bus
     */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(PlanetCommand::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    /**
     * Adds the command to a dispatcher, beside the other {@code /expedition} subcommands.
     *
     * @param dispatcher the server's dispatcher
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("expedition")
                .then(Commands.literal("planet")
                        .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                        .then(Commands.literal("validate").executes(context -> validate(context.getSource())))));
    }

    /**
     * Loads the planet files as a {@code /reload} would, and checks them against the running world.
     *
     * @param server the server
     * @return what would load, with every problem
     */
    public static PlanetLoader.Result check(MinecraftServer server) {
        ConditionalOps<JsonElement> conditions = new ConditionalOps<>(
                server.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                server.getServerResources().managers().getConditionContext());
        PlanetLoader.Result result = PlanetLoader.checkGases(
                PlanetLoader.load(PlanetLoader.list(server.getResourceManager()), conditions, References.REGISTRIES));
        return result.with(PlanetLoader.checkWorld(result, server));
    }

    private static int validate(CommandSourceStack source) {
        PlanetLoader.Result result = check(source.getServer());
        long warnings = result.problems().stream()
                .filter(p -> p.severity() == PlanetProblem.Severity.WARNING)
                .count();
        long failed = result.failedFiles();
        source.sendSuccess(
                () -> Component.translatable(
                        KEY + "summary", result.read(), result.planets().size(), failed, warnings),
                false);
        for (PlanetProblem problem : result.problems()) {
            boolean error = problem.severity() == PlanetProblem.Severity.ERROR;
            Component line = Component.translatable(KEY + (error ? "error" : "warning"), problem.describe())
                    .withStyle(error ? ChatFormatting.RED : ChatFormatting.YELLOW);
            source.sendSuccess(() -> line, false);
        }
        if (!result.planets().equals(PlanetRegistry.server().planets())) {
            source.sendSuccess(() -> Component.translatable(KEY + "changed"), false);
        }
        return failed == 0 ? 1 : 0;
    }
}

package io.github.west3436.expedition.gametest;

import com.mojang.authlib.GameProfile;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.mover.DryRun;
import io.github.west3436.expedition.mover.MoveRefusal;
import io.github.west3436.expedition.mover.MoveRequest;
import io.github.west3436.expedition.mover.Obstacle;
import io.github.west3436.expedition.mover.ShipMover;
import io.github.west3436.expedition.planet.PlanetBorders;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Bounded planets (#56), on {@code expedition_test:bounded}, the one planet {@link
 * TestPlanetBorders} bounds. Its dimension is made by {@code expedition:planet} and has a {@code
 * coordinate_scale} of 8, like the Nether's. Its square is 512 blocks a side on a surface centred at
 * x 1000, z 8, off the chunk grid, so it runs from x 744 to 1256 and z -248 to 264, and terrain
 * generates for 32 blocks past it: to x 712 and 1288, z -280 and 296.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlanetBorderTests {
    private PlanetBorderTests() {}

    private static ServerLevel bounded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(TestPlanetBorders.BOUNDED);
        check(level != null, "no bounded test planet");
        return level;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    /** The border is the planet's square, read in the planet's own blocks; the Overworld's is vanilla's. */
    @GameTest(template = "empty")
    public static void the_border_is_the_planets_square(GameTestHelper helper) {
        ServerLevel level = bounded(helper);
        WorldBorder border = level.getWorldBorder();
        String where = "centre (" + border.getCenterX() + ", " + border.getCenterZ() + "), size " + border.getSize()
                + ", x " + border.getMinX() + " to " + border.getMaxX() + ", z " + border.getMinZ() + " to "
                + border.getMaxZ();
        check(border.getCenterX() == 1000 && border.getCenterZ() == 8, "the border's " + where);
        check(border.getSize() == 512, "the border's " + where);
        check(border.getMinX() == 744 && border.getMaxX() == 1256, "the border's " + where);
        check(border.getMinZ() == -248 && border.getMaxZ() == 264, "the border's " + where);
        check(PlanetBorders.isDetached(TestPlanetBorders.BOUNDED), "the border isn't detached");

        WorldBorder overworld = helper.getLevel().getServer().overworld().getWorldBorder();
        check(
                overworld.getCenterX() == 0 && overworld.getSize() == WorldBorder.DEFAULT_SETTINGS.getSize(),
                "the Overworld's border moved: " + overworld.getCenterX() + ", " + overworld.getSize());
        check(!PlanetBorders.isDetached(Level.NETHER), "the Nether, excluded, was detached");
        check(!PlanetBorders.isDetached(TestDimensions.KEPLER), "kepler, excluded, was detached");
        helper.succeed();
    }

    /** A change to the Overworld's border reaches the levels that copy it, and not the planet's. */
    @GameTest(template = "empty")
    public static void the_overworlds_border_no_longer_drives_it(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WorldBorder overworld = server.overworld().getWorldBorder();
        WorldBorder planet = bounded(helper).getWorldBorder();
        WorldBorder nether = server.getLevel(Level.NETHER).getWorldBorder();
        int before = overworld.getWarningBlocks();
        int planetBefore = planet.getWarningBlocks();
        overworld.setWarningBlocks(before + 7);
        int planetAfter = planet.getWarningBlocks();
        int netherAfter = nether.getWarningBlocks();
        overworld.setWarningBlocks(before);
        check(planetAfter == planetBefore, "the Overworld's change reached the planet's border");
        check(netherAfter == before + 7, "the Overworld's change didn't reach the Nether's border");
        helper.succeed();
    }

    /**
     * A player arriving on the planet is sent its border, with the centre as the Overworld stores
     * it: 8 times the planet's own, since the client's level divides it by the scale again. A player
     * arriving in the Nether is sent the Overworld's, as in vanilla.
     */
    @GameTest(template = "empty")
    public static void players_are_sent_their_planets_border(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = bounded(helper);
        ServerPlayer player = new ServerPlayer(
                server, level, new GameProfile(UUID.randomUUID(), "border-test"), ClientInformation.createDefault());
        // A connection to nowhere, outside the player list, so no other test sees the player.
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(
                server, connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));

        server.getPlayerList().sendLevelInfo(player, level);
        ClientboundInitializeBorderPacket planet = border(channel);
        check(planet != null, "no border packet for the planet");
        String sent =
                "centre (" + planet.getNewCenterX() + ", " + planet.getNewCenterZ() + "), size " + planet.getNewSize();
        check(planet.getNewCenterX() == 8000 && planet.getNewCenterZ() == 64, "the planet's packet: " + sent);
        check(planet.getNewSize() == 512, "the planet's packet: " + sent);

        server.getPlayerList().sendLevelInfo(player, server.getLevel(Level.NETHER));
        ClientboundInitializeBorderPacket nether = border(channel);
        WorldBorder overworld = server.overworld().getWorldBorder();
        check(nether != null, "no border packet for the Nether");
        check(
                nether.getNewCenterX() == overworld.getCenterX() && nether.getNewSize() == overworld.getSize(),
                "the Nether's packet isn't the Overworld's border: " + nether.getNewCenterX() + ", "
                        + nether.getNewSize());
        helper.succeed();
    }

    /** The last border packet the channel holds, reading out everything sent. */
    private static ClientboundInitializeBorderPacket border(EmbeddedChannel channel) {
        ClientboundInitializeBorderPacket found = null;
        for (Object sent = channel.readOutbound(); sent != null; sent = channel.readOutbound()) {
            if (sent instanceof ClientboundInitializeBorderPacket packet) {
                found = packet;
            }
        }
        return found;
    }

    /**
     * A chunk reaching inside the cutoff has terrain, whichever side it is on; the first chunk wholly
     * past it, and one further out, are empty, keep the planet's biome and have no structure starts.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void chunks_past_the_cutoff_are_empty(GameTestHelper helper) {
        ServerLevel level = bounded(helper);
        // x: chunk 44 is 704-719 and 80 is 1280-1295, inside the cutoff at 712 and 1288; 43 and 81
        // are wholly past. z: chunk 18 is 288-303, inside 296; 19 is wholly past. A full chunk needs
        // chunks about 11 around it generated part-way, so chunk 83 is about as far out as a test can go
        // and still keep every chunk it makes in region files that reach inside the cutoff: the
        // validate test expects none wholly past it.
        int[][] terrain = {{44, 0}, {80, 0}, {50, 18}, {50, 0}};
        int[][] empty = {{43, 0}, {81, 0}, {83, 0}, {50, 19}};
        for (int[] at : terrain) {
            check(!allAir(level.getChunk(at[0], at[1])), "chunk " + at[0] + ", " + at[1] + " has no terrain");
        }
        Holder<Biome> biome = level.getChunk(50, 0).getNoiseBiome(0, 16, 0);
        for (int[] at : empty) {
            ChunkAccess chunk = level.getChunk(at[0], at[1]);
            String name = "chunk " + at[0] + ", " + at[1];
            check(allAir(chunk), name + " isn't empty");
            check(chunk.getAllStarts().isEmpty(), name + " has structure starts");
            check(chunk.getNoiseBiome(0, 16, 0).equals(biome), name + " lost its biome");
        }
        helper.succeed();
    }

    private static boolean allAir(ChunkAccess chunk) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (!section.hasOnlyAir()) {
                return false;
            }
        }
        return true;
    }

    /** A move to a spot past the planet's border is refused before anything is loaded; one inside isn't, for that. */
    @GameTest(template = "empty")
    public static void a_move_past_the_border_is_refused(GameTestHelper helper) {
        ServerLevel from = helper.getLevel();
        ServerLevel to = bounded(helper);
        BlockPos corner = helper.absolutePos(new BlockPos(1, 1, 1));
        BoundingBox box = BoundingBox.fromCorners(corner, corner.offset(1, 1, 1));

        // The box's far side would be at x 1256, the first block past the border.
        DryRun past = ShipMover.dryRun(new MoveRequest(from, box, to, new BlockPos(1255, 80, 8)));
        check(
                past.refusal().equals(Optional.of(MoveRefusal.OUTSIDE_BORDER)),
                "a move past the border: " + past.refusal() + ", " + past.obstacles());
        DryRun inside = ShipMover.dryRun(new MoveRequest(from, box, to, new BlockPos(1254, 80, 8)));
        check(
                inside.obstacles().stream().map(Obstacle::code).noneMatch(MoveRefusal.OUTSIDE_BORDER::equals),
                "a move just inside the border: " + inside.obstacles());
        check(
                ShipMover.dryRun(new MoveRequest(from, box, to, new BlockPos(1000, 80, -249)))
                        .refusal()
                        .equals(Optional.of(MoveRefusal.OUTSIDE_BORDER)),
                "a move past the border's low z side wasn't refused");
        helper.succeed();
    }
}

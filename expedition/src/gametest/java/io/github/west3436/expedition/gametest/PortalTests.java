package io.github.west3436.expedition.gametest;

import com.mojang.authlib.GameProfile;
import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.portal.PortalRules;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The server setting that turns Nether and End portals off (#57). A player stands in a portal, as
 * the game puts them when they walk into one, and ticks as the server ticks them: with the portal on
 * they pass through to the other dimension, and with it off they stay put and are told why on the
 * action bar. Leaving the Nether or the End by portal works with both off.
 *
 * <p>Each test sets the settings it needs and puts the server's back, so each is a batch of its own
 * and never runs beside another that wants other settings. Each player is a test player on a
 * connection to nowhere, outside the player list, removed from its level at the end.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PortalTests {
    /** Ticks a player stands in a portal before the test decides it did nothing. */
    private static final int STAND = 20;

    private static final int TIMEOUT = 600;

    private static final Portal NETHER_PORTAL = (Portal) Blocks.NETHER_PORTAL;
    private static final Portal END_PORTAL = (Portal) Blocks.END_PORTAL;

    /** Where the leaving tests stand in the Nether: far from everything, in its own chunks. */
    private static final BlockPos IN_THE_NETHER = new BlockPos(-2_504, 70, 3_704);

    private PortalTests() {}

    // ---- Going in --------------------------------------------------------------------------

    /** With Nether portals on, a player in one passes into the Nether. */
    @GameTest(template = "empty", batch = "portals_nether_on", timeoutTicks = TIMEOUT)
    public static void a_nether_portal_takes_a_player_in_when_on(GameTestHelper h) {
        passes(h, PortalRules.Settings.of(true, false), h.getLevel(), NETHER_PORTAL, Level.NETHER);
    }

    /** With End portals on, a player in one passes into the End. */
    @GameTest(template = "empty", batch = "portals_end_on", timeoutTicks = TIMEOUT)
    public static void an_end_portal_takes_a_player_in_when_on(GameTestHelper h) {
        passes(h, PortalRules.Settings.of(false, true), h.getLevel(), END_PORTAL, Level.END);
    }

    /**
     * With Nether portals off, a player in one stays in the Overworld and is told why, and a pig in
     * one gets no destination either.
     */
    @GameTest(template = "empty", batch = "portals_nether_off", timeoutTicks = TIMEOUT)
    public static void a_nether_portal_does_nothing_when_off(GameTestHelper h) {
        staysPut(h, PortalRules.Settings.of(false, true), NETHER_PORTAL, PortalRules.NETHER_OFF);
    }

    /** With End portals off, a player in one stays in the Overworld and is told why. */
    @GameTest(template = "empty", batch = "portals_end_off", timeoutTicks = TIMEOUT)
    public static void an_end_portal_does_nothing_when_off(GameTestHelper h) {
        staysPut(h, PortalRules.Settings.of(true, false), END_PORTAL, PortalRules.END_OFF);
    }

    // ---- Leaving ---------------------------------------------------------------------------

    /** With both off, a Nether portal in the Nether still takes a player back to the Overworld. */
    @GameTest(template = "empty", batch = "portals_leave_nether", timeoutTicks = TIMEOUT)
    public static void leaving_the_nether_by_portal_works_when_off(GameTestHelper h) {
        ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
        passes(h, PortalRules.Settings.of(false, false), nether, NETHER_PORTAL, Level.OVERWORLD);
    }

    /**
     * With both off, the End's exit portal still takes a player back to the Overworld. (A player who
     * hasn't seen the credits gets them first, and returns by respawning, which no portal setting
     * touches.)
     */
    @GameTest(template = "empty", batch = "portals_leave_end", timeoutTicks = TIMEOUT)
    public static void leaving_the_end_by_portal_works_when_off(GameTestHelper h) {
        ServerLevel end = h.getLevel().getServer().getLevel(Level.END);
        passes(h, PortalRules.Settings.of(false, false), end, END_PORTAL, Level.OVERWORLD);
    }

    // ---- The two outcomes ------------------------------------------------------------------

    /** A player standing in a portal in {@code from} arrives in {@code to}, and isn't told off. */
    private static void passes(
            GameTestHelper h, PortalRules.Settings settings, ServerLevel from, Portal portal, ResourceKey<Level> to) {
        PortalRules.Settings before = PortalRules.settings();
        PortalRules.setSettings(settings);
        Mock mock = mock(h);
        BlockPos at = from == h.getLevel() ? h.absolutePos(new BlockPos(1, 2, 1)) : IN_THE_NETHER;
        if (from.dimension() == Level.END) {
            at = new BlockPos(100, 50, 0);
        }
        BlockPos portalAt = at;
        mock.player.teleportTo(from, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        int[] ticks = {0};
        h.startSequence()
                .thenWaitUntil(() -> {
                    if (mock.player.level().dimension() != to) {
                        if (ticks[0]++ > 200) {
                            throw new GameTestAssertException(
                                    "the player never left " + from.dimension().location());
                        }
                        stand(mock.player, portal, portalAt);
                        throw new GameTestAssertException("the player is still in "
                                + mock.player.level().dimension().location());
                    }
                })
                .thenExecute(() -> {
                    h.assertTrue(
                            !toldOff(mock, PortalRules.NETHER_OFF) && !toldOff(mock, PortalRules.END_OFF),
                            "the player was told the portal is off");
                    mock.remove();
                    PortalRules.setSettings(before);
                })
                .thenSucceed();
    }

    /**
     * A player standing in a portal in the Overworld for {@value #STAND} ticks stays there, and is
     * told why on the action bar.
     */
    private static void staysPut(GameTestHelper h, PortalRules.Settings settings, Portal portal, String message) {
        PortalRules.Settings before = PortalRules.settings();
        PortalRules.setSettings(settings);
        Mock mock = mock(h);
        BlockPos at = h.absolutePos(new BlockPos(1, 2, 1));
        mock.player.teleportTo(h.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        int[] ticks = {0};
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertTrue(
                            mock.player.level() == h.getLevel(),
                            "the player went to "
                                    + mock.player.level().dimension().location());
                    if (ticks[0]++ < STAND) {
                        stand(mock.player, portal, at);
                        throw new GameTestAssertException("the player has stood in the portal for " + ticks[0]);
                    }
                })
                .thenExecute(() -> {
                    h.assertTrue(mock.player.level() == h.getLevel(), "the player left the Overworld");
                    h.assertTrue(toldOff(mock, message), "the player wasn't told " + message + " on the action bar");
                    if (portal == NETHER_PORTAL) {
                        Pig pig = EntityType.PIG.create(h.getLevel());
                        if (pig == null) {
                            throw new GameTestAssertException("no pig");
                        }
                        pig.moveTo(Vec3.atBottomCenterOf(at));
                        h.assertTrue(
                                portal.getPortalDestination(h.getLevel(), pig, at) == null,
                                "a pig would go through the portal");
                    }
                    mock.remove();
                    PortalRules.setSettings(before);
                })
                .thenSucceed();
    }

    // ---- The test player -------------------------------------------------------------------

    /** A creative test player and the connection its packets go to. */
    private static final class Mock {
        final ServerPlayer player;
        final EmbeddedChannel channel;
        final MinecraftServer server;

        /** The keys of every action-bar message the player has been sent. */
        final Set<String> told = new HashSet<>();

        Mock(ServerPlayer player, EmbeddedChannel channel, MinecraftServer server) {
            this.player = player;
            this.channel = channel;
            this.server = server;
        }

        void remove() {
            player.serverLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
    }

    /**
     * A test player on a connection to nowhere, kept so the test can read what it was sent, and
     * added to the test's level but not to the player list, so no other test sees it and nothing
     * negotiates the connection's channels. Creative, so a Nether portal takes it at once.
     */
    private static Mock mock(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        CommonListenerCookie cookie =
                CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "portal-test"), false);
        ServerPlayer player =
                new ServerPlayer(server, h.getLevel(), cookie.gameProfile(), ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(server, connection, player, cookie);
        player.setGameMode(GameType.CREATIVE);
        h.getLevel().addNewPlayer(player);
        return new Mock(player, channel, server);
    }

    /** One server tick of a player standing in a portal, as the game ticks one walking into it. */
    private static void stand(ServerPlayer player, Portal portal, BlockPos at) {
        player.setAsInsidePortal(portal, at);
        player.doTick();
    }

    /** Whether the player has been sent a message on the action bar, reading out everything sent. */
    private static boolean toldOff(Mock mock, String key) {
        for (Object sent = mock.channel.readOutbound(); sent != null; sent = mock.channel.readOutbound()) {
            if (sent instanceof ClientboundSystemChatPacket packet
                    && packet.overlay()
                    && packet.content().getContents() instanceof TranslatableContents t) {
                mock.told.add(t.getKey());
            }
        }
        return mock.told.contains(key);
    }
}

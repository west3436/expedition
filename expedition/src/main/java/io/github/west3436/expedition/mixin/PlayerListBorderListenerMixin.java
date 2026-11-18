package io.github.west3436.expedition.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.west3436.expedition.planet.PlanetBorders;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Overworld's border listener, the anonymous class in {@code PlayerList#addWorldborderListener}:
 * its changes go to every player in vanilla, and here only to those whose level still copies the
 * Overworld's border. A bounded planet's players get its own border's changes instead ({@link
 * PlanetBorders}).
 */
@Mixin(targets = "net.minecraft.server.players.PlayerList$1")
abstract class PlayerListBorderListenerMixin {
    @WrapOperation(
            method = {
                "onBorderSizeSet",
                "onBorderSizeLerping",
                "onBorderCenterSet",
                "onBorderSetWarningTime",
                "onBorderSetWarningBlocks"
            },
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;)V"))
    private void expedition$toOverworldFollowers(PlayerList players, Packet<?> packet, Operation<Void> original) {
        if (!PlanetBorders.anyDetached()) {
            original.call(players, packet);
            return;
        }
        for (ServerPlayer player : players.getPlayers()) {
            if (PlanetBorders.followsOverworld(player)) {
                player.connection.send(packet);
            }
        }
    }
}

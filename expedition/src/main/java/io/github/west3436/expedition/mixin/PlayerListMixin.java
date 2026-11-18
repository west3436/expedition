package io.github.west3436.expedition.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.west3436.expedition.planet.PlanetBorders;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sends a player their own level's border on login, respawn and dimension change, which all go
 * through {@code sendLevelInfo}. Vanilla always sends the Overworld's; a bounded planet's is its own
 * ({@link PlanetBorders#borderToSend}).
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @WrapOperation(
            method = "sendLevelInfo",
            at =
                    @At(
                            value = "NEW",
                            target =
                                    "(Lnet/minecraft/world/level/border/WorldBorder;)Lnet/minecraft/network/protocol/game/ClientboundInitializeBorderPacket;"))
    private ClientboundInitializeBorderPacket expedition$sendOwnBorder(
            WorldBorder overworld,
            Operation<ClientboundInitializeBorderPacket> original,
            ServerPlayer player,
            ServerLevel level) {
        return original.call(PlanetBorders.borderToSend(level));
    }
}

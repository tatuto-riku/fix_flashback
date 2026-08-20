package com.tatuto.fixflashback.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tatuto.fixflashback.FixFlashback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stops a listener exception thrown while NeoForge is firing the {@link OnDatapackSyncEvent} for a
 * Flashback replay player from aborting the spawn ("Failed to spawn player").
 *
 * <h2>The crash</h2>
 * When Flashback plays back a recording it places a fake {@link ServerPlayer} into the replay world
 * via {@code PlayerList.placeNewPlayer}. During that call NeoForge fires {@link OnDatapackSyncEvent}
 * (through {@code IEventBus.post}), and some mods (e.g. Accessories via {@code owo} networking) try
 * to send a custom payload (e.g. {@code accessories:main}) to the freshly placed player. The
 * replay's dummy connection has no registered payload channels, so NeoForge's
 * {@code NetworkRegistry.checkPacket} throws:
 * <pre>
 * java.lang.UnsupportedOperationException: Payload accessories:main may not be sent to the client!
 *   at net.neoforged.neoforge.network.registration.NetworkRegistry.checkPacket
 *   at ...EventBus.post
 *   at net.minecraft.server.players.PlayerList.placeNewPlayer
 *   at ...ReplayServer$1.placeNewPlayer
 *   at ...ReplayGamePacketHandler.spawnPlayer
 * </pre>
 * Flashback catches that and aborts the spawn with "Failed to spawn player", so the replay breaks.
 *
 * <p>This event is posted directly by {@code PlayerList.placeNewPlayer} (not through
 * {@code EventHooks.firePlayerLoggedIn}), so the narrower guard in
 * {@link MixinNeoForgePlayerLoggedIn} does not see it.</p>
 *
 * <h2>The fix</h2>
 * We wrap the {@code IEventBus.post} call inside {@link PlayerList#placeNewPlayer} (the
 * {@code OnDatapackSyncEvent} dispatch). If the event concerns a player whose server is Flashback's
 * {@code ReplayServer}, any exception thrown by a listener while firing is caught and swallowed
 * (logged) so the player placement continues and the replay is not aborted. For every normal
 * (non-replay) event the exception propagates unchanged, preserving vanilla/NeoForge behaviour.
 *
 * <p>Detection is by server class name ({@code com.moulberry.flashback.playback.ReplayServer}) so
 * the mod stays inert when Flashback is absent. {@code PlayerList} is a vanilla class, so this
 * mixin is robust against Connector's remapping of Flashback classes.</p>
 */
@Mixin(PlayerList.class)
public abstract class MixinNeoForgeEventBus {

    @WrapOperation(
            method = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;",
                    remap = false),
            require = 0,
            expect = 0)
    private Event fixFlashback$safeOnDatapackSync(
            IEventBus bus,
            Event event,
            Operation<Event> operation) {
        try {
            return operation.call(bus, event);
        } catch (Throwable t) {
            if (isReplayPlayerEvent(event)) {
                FixFlashback.LOGGER.error(
                        "Suppressed exception while firing {} for Flashback replay player", event, t);
                return event;
            }
            throw t;
        }
    }

    private static boolean isReplayPlayerEvent(Event event) {
        Player player = null;
        if (event instanceof OnDatapackSyncEvent onDatapackSyncEvent) {
            player = onDatapackSyncEvent.getPlayer();
        } else if (event instanceof PlayerEvent playerEvent) {
            player = playerEvent.getEntity();
        }
        if (player == null) {
            return false;
        }
        MinecraftServer server = player.getServer();
        return server != null
                && "com.moulberry.flashback.playback.ReplayServer"
                        .equals(server.getClass().getName());
    }
}

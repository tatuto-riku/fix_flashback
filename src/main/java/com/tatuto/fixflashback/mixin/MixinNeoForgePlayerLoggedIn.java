package com.tatuto.fixflashback.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tatuto.fixflashback.FixFlashback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Works around the "disconnected / 切断されました" screen that appears when entering a Flashback
 * replay/recording on NeoForge.
 *
 * <h2>The crash</h2>
 * When Flashback places its viewer player into the replay world it calls
 * {@code EventHooks.firePlayerLoggedIn(player)}. Inside that call NeoForge posts a
 * {@code PlayerLoggedInEvent}, and some listener (or the event setup itself) looks up the player's
 * {@code BannerPattern} registry entries by id. In a replay the viewer's inventory can reference a
 * {@code BannerPattern} (e.g. {@code minecraft:rhombus}) that is not present in the replay server's
 * registry, so NeoForge throws:
 * <pre>
 * java.lang.IllegalArgumentException: Can't find id for
 *   'Reference{ResourceKey[minecraft:banner_pattern / minecraft:rhombus]=...}' in map ...
 *   at net.neoforged.neoforge.event.EventHooks.firePlayerLoggedIn
 *   at ...ReplayServer$1.placeNewPlayer
 * </pre>
 * {@code placeNewPlayer} turns that exception into "Couldn't place player in world", which the
 * client sees as "切断されました" (disconnected).
 *
 * <h2>The fix</h2>
 * We wrap the {@code IEventBus.post(...)} call inside {@link EventHooks#firePlayerLoggedIn}. If the
 * posted event is a {@link PlayerEvent} for a player whose server is Flashback's
 * {@code ReplayServer}, any exception thrown while firing the event is caught and swallowed (logged
 * at error level) so the player placement continues and the replay is not aborted. For every normal
 * (non-replay) login the exception propagates unchanged, preserving vanilla/NeoForge behaviour.
 *
 * <p>Detection is by server class name ({@code com.moulberry.flashback.playback.ReplayServer}) so
 * the mod stays inert when Flashback is absent.</p>
 */
@Mixin(EventHooks.class)
public abstract class MixinNeoForgePlayerLoggedIn {

    @WrapOperation(
            method = "firePlayerLoggedIn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"),
            require = 0,
            expect = 0)
    private static Event fixFlashback$safeFirePlayerLoggedIn(
            IEventBus instance,
            Event event,
            Operation<Event> operation) {
        try {
            return operation.call(instance, event);
        } catch (Throwable t) {
            if (event instanceof PlayerEvent playerEvent) {
                Player player = playerEvent.getEntity();
                MinecraftServer server = player != null ? player.getServer() : null;
                if (server != null
                        && "com.moulberry.flashback.playback.ReplayServer"
                                .equals(server.getClass().getName())) {
                    FixFlashback.LOGGER.error(
                            "Suppressed exception while firing PlayerLoggedInEvent for Flashback replay", t);
                    return event;
                }
            }
            throw t;
        }
    }
}

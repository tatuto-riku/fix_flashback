package com.tatuto.fixflashback.mixin;

import com.tatuto.fixflashback.FixFlashback;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Debug helper: counts how often Flashback's per-tick teleport packets actually arrive on the client
 * during a replay, and forces the client-side interpolation step count back to the vanilla default so
 * entities don't slide in straight lines between recorded positions.
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinTeleportPacketDebug {

    private static long fixFlashback$lastTeleportLog = 0;
    private static int fixFlashback$teleportCount = 0;
    private static boolean fixFlashback$smoothDisabled = false;

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"))
    private void fixFlashback$countTeleport(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        if (!FixFlashback.isReplayPlaying()) {
            return;
        }
        if (!fixFlashback$smoothDisabled) {
            FixFlashback.disableSmoothMovementIfReplaying();
            fixFlashback$smoothDisabled = true;
        }
        // Force the entity's interpolation step count back to vanilla default.
        Entity entity = Minecraft.getInstance().level.getEntity(packet.getId());
        FixFlashback.clampEntityLerpSteps(entity);

        long now = System.currentTimeMillis();
        fixFlashback$teleportCount++;
        if (now - fixFlashback$lastTeleportLog > 3000) {
            long elapsed = now - fixFlashback$lastTeleportLog;
            if (fixFlashback$lastTeleportLog != 0 && elapsed > 0) {
                double rate = (fixFlashback$teleportCount * 1000.0) / elapsed;
                FixFlashback.LOGGER.info("[Fix Flashback] Teleport packets: {} in {}ms (~{}/s, expected ~20/s)",
                        fixFlashback$teleportCount, elapsed, String.format("%.1f", rate));
            }
            fixFlashback$lastTeleportLog = now;
            fixFlashback$teleportCount = 0;
        }
    }
}

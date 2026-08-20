package com.tatuto.fixflashback.mixin;

import com.tatuto.fixflashback.FixFlashback;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

/** Drops mod payloads that NeoForge cannot encode for Flashback's fake player. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class MixinReplayPlayerPayload {

    @WrapMethod(method = "send")
    private void fixFlashback$skipUnsupportedReplayPayload(
            Packet<?> packet,
            Operation<Void> operation) {
        try {
            operation.call(packet);
        } catch (UnsupportedOperationException exception) {
            if (FixFlashback.isUntrackedHandler(this)) {
                FixFlashback.LOGGER.debug(
                        "Skipped unsupported payload sent to Flashback fake player: {}", packet);
                return;
            }
            throw exception;
        }
    }
}
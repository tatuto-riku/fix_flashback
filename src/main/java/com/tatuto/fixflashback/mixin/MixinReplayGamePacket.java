package com.tatuto.fixflashback.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.action.ActionGamePacket;
import com.moulberry.flashback.playback.ReplayServer;
import com.tatuto.fixflashback.FixFlashback;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Works around a crash that happens while a Flashback replay is playing back on NeoForge.
 *
 * <h2>The crash</h2>
 * Flashback decodes recorded game packets through {@link ReplayServer#handleGamePacket}, which is
 * invoked from {@link ActionGamePacket#handle}. Flashback's own try/catch only swallows
 * {@code DecoderException}, but some mods (e.g. EasyNPC's {@code DisplayAttributeDataSet.decode})
 * throw non-{@code DecoderException} errors such as {@link java.lang.ArrayIndexOutOfBoundsException}
 * while decoding, or an {@link java.lang.IllegalStateException} ("Invalid entity data item type")
 * while <em>applying</em> the decoded packet (e.g. to an EasyNPC entity). Those escape Flashback's
 * catch, propagate out of the server tick loop and crash the whole replay server.
 *
 * <h2>The fix</h2>
 * We wrap the {@link ActionGamePacket#handle} invocation that calls into
 * {@link ReplayServer#handleGamePacket}. On any throwable we advance the buffer's reader index to
 * the end (mirroring what Flashback does for a decode failure) so the next recorded packet starts at
 * a sane position, then log the error and swallow it. Playback continues past the broken packet
 * instead of crashing the server thread.
 *
 * <p>Declared {@link Pseudo} with {@code require = 0} so it silently does nothing when Flashback is
 * absent or its internals change.</p>
 */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.action.ActionGamePacket", remap = false)
public abstract class MixinReplayGamePacket {

    @WrapOperation(
            method = "handle",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/moulberry/flashback/playback/ReplayServer;handleGamePacket(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V",
                    remap = false),
            require = 0,
            expect = 0)
    private void fixFlashback$safeHandleGamePacket(
            ReplayServer replayServer,
            RegistryFriendlyByteBuf friendlyByteBuf,
            Operation<Void> operation) {
        try {
            operation.call((Object) replayServer, (Object) friendlyByteBuf);
        } catch (Throwable t) {
            try {
                // Mirror Flashback's decode-failure recovery so following packets stay aligned
                friendlyByteBuf.readerIndex(friendlyByteBuf.writerIndex());
            } catch (Throwable ignored) {
            }
            FixFlashback.LOGGER.error(
                    "Suppressed error while handling a Flashback replay game packet; keeping playback alive", t);
        }
    }
}

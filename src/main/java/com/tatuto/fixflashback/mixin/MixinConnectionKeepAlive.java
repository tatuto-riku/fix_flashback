package com.tatuto.fixflashback.mixin;

import com.tatuto.fixflashback.FixFlashback;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the connection alive while a Flashback replay is playing back on NeoForge.
 *
 * <h2>The problem</h2>
 * While replaying, Flashback resends recorded {@code clientbound/minecraft:custom_payload} packets.
 * On this modpack some of those payloads trigger a packet-id collision between
 * <em>Ars Nouveau</em> and <em>Create Big Cannons</em>: an {@code ars_nouveau} particle payload ends
 * up being decoded/cast as a {@code createbigcannons} particle type, throwing:
 * <pre>
 * io.netty.handler.codec.EncoderException: Failed to encode packet
 *   'clientbound/minecraft:custom_payload'
 *   Caused by: ClassCastException:
 *     com.hollberry.arsnouveau.api.particle.PropertyParticleOptions
 *     cannot be cast to rbasamoyai.createbigcannons...SplashSprayParticleData
 * </pre>
 * Netty's default {@code exceptionCaught} then closes the channel, so the client sees
 * "接続を維持できません" (connection lost) and playback stops.
 *
 * <h2>The fix</h2>
 * We hook {@link Connection#exceptionCaught(ChannelHandlerContext, Throwable)}. When the caught
 * exception (or its cause) is an {@link EncoderException} - i.e. a packet failed to encode, which is
 * harmless to skip for a replay - we swallow it and cancel the default handler so the channel is NOT
 * closed. The single broken payload is simply dropped; every other packet (and the connection
 * itself) keeps working, so playback continues.
 *
 * <p>Non-encode errors still propagate normally, so real connection problems are not hidden.</p>
 */
@Mixin(Connection.class)
public abstract class MixinConnectionKeepAlive {

    @Inject(
            method = "exceptionCaught",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            expect = 0)
    private void fixFlashback$keepAliveOnEncodeError(
            ChannelHandlerContext context,
            Throwable throwable,
            CallbackInfo ci) {
        if (throwable instanceof EncoderException
                || (throwable.getCause() != null && throwable.getCause() instanceof EncoderException)) {
            FixFlashback.LOGGER.warn(
                    "Suppressed encoder exception to keep Flashback replay connection alive", throwable);
            ci.cancel();
        }
    }
}

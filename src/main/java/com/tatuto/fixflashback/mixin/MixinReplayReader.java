package com.tatuto.fixflashback.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.playback.ReplayServer;
import com.tatuto.fixflashback.FixFlashback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Works around a crash that happens while a Flashback replay is playing back on NeoForge.
 *
 * <h2>The crash</h2>
 * During playback the replay server ticks through recorded actions. One action type,
 * {@code flashback:action/game_packet}, replays raw game packets. On NeoForge some of those packets
 * (e.g. custom payloads whose codecs differ from the Fabric origin, or particle data that fails to
 * decode) cannot be fully read, and Flashback's reader throws:
 * <pre>
 * java.lang.RuntimeException: Action flashback:action/game_packet failed to fully read.
 *   Had 168 bytes available, only read 115
 *   at com.moulberry.flashback.io.ReplayReader.handleNextAction(ReplayReader.java:133)
 *   at ...ReplayServer.handleActions -> runUpdates -> tickServer
 * </pre>
 * Because this happens inside the server tick loop, the whole server thread dies and the client is
 * disconnected.
 *
 * <h2>The fix</h2>
 * We wrap the {@code handleActions()} invocation inside {@link ReplayServer#runUpdates}. If reading
 * or applying the queued actions throws, we log the failure and swallow it so the tick loop keeps
 * going instead of crashing the server thread (which would disconnect the client). The next tick
 * simply tries the following actions again, so playback continues past the corrupted action.
 *
 * <p>Note: {@code @WrapOperation} can only target an INVOKE instruction, so we wrap the
 * {@code handleActions()} <em>call</em> (an INVOKE inside {@code runUpdates}) rather than the
 * {@code handleNextAction} method body itself (whose HEAD is not an INVOKE and therefore rejected by
 * MixinExtras).</p>
 *
 * <p>Declared {@link Pseudo} with {@code require = 0} so it silently does nothing when Flashback is
 * absent or its internals change.</p>
 */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.playback.ReplayServer", remap = false)
public abstract class MixinReplayReader {

    @WrapOperation(
            method = "runUpdates",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/moulberry/flashback/playback/ReplayServer;handleActions()V"),
            require = 0,
            expect = 0)
    private void fixFlashback$safeHandleActions(
            ReplayServer instance,
            Operation<Void> operation) {
        try {
            operation.call((Object) instance);
        } catch (Throwable t) {
            FixFlashback.LOGGER.error(
                    "Suppressed error while handling Flashback replay actions; keeping playback alive", t);
        }
    }
}

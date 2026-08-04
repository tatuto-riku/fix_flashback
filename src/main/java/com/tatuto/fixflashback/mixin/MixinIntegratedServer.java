package com.tatuto.fixflashback.mixin;

import com.tatuto.fixflashback.FixFlashback;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostics for the exact place the reported crash happened.
 *
 * <p>From the log:</p>
 * <pre>
 * Description: Starting integrated server
 * java.lang.NoClassDefFoundError: net/fabricmc/fabric/impl/networking/UntrackedNetworkHandler
 *   at java.lang.ClassLoader.defineClass1(Native Method)
 *   at net.minecraft.client.server.IntegratedServer.&lt;init&gt;(IntegratedServer.java:65)
 *   at net.minecraft.client.Minecraft.lambda$doWorldLoad$40(Minecraft.java:2066)
 * </pre>
 *
 * <p>{@code IntegratedServer}'s constructor builds the player list; Flashback mixes into that path,
 * which drags {@code FlashbackFakePlayerPacketListener} - and its unresolvable superinterface -
 * into class loading. Because that happens inside a constructor on the world-load path, the error
 * is fatal and every world load dies, not just replays.</p>
 *
 * <p>The real fix is shipping the missing interface, which happens before this point is ever
 * reached. This mixin only verifies at runtime that the interface is genuinely resolvable as the
 * integrated server starts, and logs a loud, actionable message if it somehow is not - turning a
 * bare {@code NoClassDefFoundError} into something diagnosable.</p>
 *
 * <p>It deliberately does not swallow anything: suppressing a {@link NoClassDefFoundError} thrown
 * from a constructor would leave a half-initialised server behind and cause stranger failures
 * later. Failing fast with a clear explanation is the correct behaviour here.</p>
 */
@Mixin(IntegratedServer.class)
public abstract class MixinIntegratedServer {

    @Inject(method = "<init>", at = @At("TAIL"), require = 0, expect = 0)
    private void fixFlashback$verifyNetworkingClasses(CallbackInfo ci) {
        if (!FixFlashback.isFlashbackLoaded()) {
            return;
        }

        if (FixFlashback.classExists(FixFlashback.UNTRACKED_HANDLER)) {
            // The interface resolves, so Flashback links normally and no patch was needed.
            FixFlashback.LOGGER.debug("Integrated server starting; {} resolved normally.",
                    FixFlashback.UNTRACKED_HANDLER);
            return;
        }

        if (FixFlashback.isPatched()) {
            FixFlashback.LOGGER.debug(
                    "Integrated server starting; Flashback's fake player listener was patched.");
            return;
        }

        FixFlashback.LOGGER.error(
                "{} is missing and Flashback's fake player listener was NOT patched. World loading "
                        + "is about to crash with NoClassDefFoundError. Ensure fix_flashback loads "
                        + "before Flashback and that its mixin config is active.",
                FixFlashback.UNTRACKED_HANDLER);
    }
}

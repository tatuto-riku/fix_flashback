package com.tatuto.fixflashback.mixin;

import com.tatuto.fixflashback.FixFlashback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restores the behaviour that Fabric API's {@code UntrackedNetworkHandler} marker is supposed to
 * provide, which Forgified Fabric API dropped.
 *
 * <h2>Background</h2>
 * Upstream Fabric API guards the play-networking init hook with the marker:
 * <pre>
 * // ServerPlayNetworkHandlerMixin (upstream Fabric API)
 * this.addon = new ServerPlayNetworkAddon(...);
 *
 * if (!(this instanceof UntrackedNetworkHandler)) {
 *     this.addon.lateInit();
 * }
 * </pre>
 * Flashback's fake replay player carries that marker precisely so networking never treats it as a
 * real connection.
 *
 * <p>Forgified Fabric API's rewrite of the same mixin dropped the check entirely - its
 * {@code initAddon} unconditionally does:</p>
 * <pre>
 * ServerPlayConnectionEvents.INIT.invoker().onPlayInit((ServerGamePacketListenerImpl) this, this.server);
 * </pre>
 *
 * <p>So on NeoForge every mod listening to {@code ServerPlayConnectionEvents.INIT} is handed
 * Flashback's fake player, which has no real {@link net.minecraft.network.Connection} behind it.
 * With a large modpack the odds that some listener dereferences that connection are high, which is
 * the second failure mode of this crash - the one that only shows up "with many mods installed".</p>
 *
 * <h2>What this mixin does</h2>
 * Cancels {@code initAddon} for Flashback's fake player, reinstating upstream semantics.
 *
 * <p>The check is by class name rather than {@code instanceof UntrackedNetworkHandler}, because
 * {@link FixFlashbackMixinPlugin} has removed that interface - it does not exist on this platform,
 * which is the whole reason the crash happened. Class name is the only identifier left.</p>
 *
 * <p>Declared {@link Pseudo} and with {@code require = 0} so it silently does nothing when
 * Forgified Fabric API is absent or its internals change.</p>
 */
@Pseudo
@Mixin(targets = "net.minecraft.server.network.ServerCommonPacketListenerImpl", remap = false)
public abstract class MixinFabricNetworkAddon {

    @Inject(method = "initAddon", at = @At("HEAD"), cancellable = true, require = 0, expect = 0, remap = false)
    private void fixFlashback$skipUntrackedHandler(CallbackInfo ci) {
        if (!FixFlashback.isUntrackedHandler(this)) {
            return;
        }

        ci.cancel();

        FixFlashback.LOGGER.debug(
                "Skipped ServerPlayConnectionEvents.INIT for an untracked (fake player) handler: {}",
                this.getClass().getName());
    }
}

package com.tatuto.fixflashback.mixin;

import com.tatuto.fixflashback.FixFlashback;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Pseudo
@Mixin(targets = "com.smoothmovement.time.ServerTime", remap = false)
public class MixinSmoothMovementServerTime {

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onTickHead(MinecraftServer server, long[] tickTimes, int index, CallbackInfo ci) {
        if (FixFlashback.isReplayPlaying()) {
            ci.cancel();
        }
    }
}

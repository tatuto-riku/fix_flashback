package com.tatuto.fixflashback.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Attaches this mod to Flashback's fake replay-player packet listener.
 *
 * <p>Deliberately empty. Its only job is to make
 * {@code com.moulberry.flashback.playback.FlashbackFakePlayerPacketListener} a mixin target, which
 * is what causes {@link FixFlashbackMixinPlugin#preApply} to be invoked for it. The plugin then
 * strips the unresolvable {@code UntrackedNetworkHandler} superinterface from the class node - the
 * actual fix for the {@link NoClassDefFoundError} crash on world load.</p>
 *
 * <p>{@link Pseudo} means Mixin quietly skips this when Flashback is not installed, so the mod is
 * inert without it.</p>
 */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.playback.FlashbackFakePlayerPacketListener", remap = false)
public abstract class MixinFlashbackFakePlayer {
}

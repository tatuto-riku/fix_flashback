package com.tatuto.fixflashback.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tatuto.fixflashback.FixFlashback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Works around a crash that happens when a Flashback replay/recording is opened on NeoForge.
 *
 * <h2>The crash</h2>
 * ModernFix's {@code cache_strongholds} optimization hooks {@code ServerLevel.<init>} and, right
 * before {@code ChunkGeneratorStructureState#ensureStructuresGenerated()} is called, downcasts the
 * level's chunk generator to its {@code IChunkGenerator} duck interface and calls
 * {@code mfix$setStrongholdCachePath(...)} on it. In Flashback's playback server the chunk generator
 * is not set up the same way and {@code ChunkGeneratorStructureState#getChunkGenerator()} returns
 * {@code null}, so the downcast yields a {@code null} and ModernFix throws:
 * <pre>
 * java.lang.NullPointerException: Cannot invoke "...IChunkGenerator.mfix$setStrongholdCachePath(
 *     Path, MinecraftServer)" because "instance" is null
 *   at ServerLevel.wrapOperation$zgj000$modernfix$setCachePath
 *   at ServerLevel.&lt;init&gt;
 *   at ...ReplayServer.loadLevel
 * </pre>
 *
 * <h2>The fix</h2>
 * We register a {@code @WrapOperation} on the very same injection point (the
 * {@code ensureStructuresGenerated()} call inside {@code ServerLevel.<init>}), but our mixin config
 * has a higher priority than ModernFix (see {@code fix_flashback.mixins.json}), so our wrapper runs
 * outermost. When the server being initialized is Flashback's {@code ReplayServer}, we skip the
 * wrapped call entirely, which prevents ModernFix's downcast from ever executing and avoids the NPE.
 * For every normal world we delegate to {@code operation.call(...)}, so ModernFix's stronghold cache
 * optimization keeps working exactly as designed.
 *
 * <p>Skipping the stronghold cache path setup for replay levels is safe: stronghold position caching
 * is purely a ModernFix performance optimization, so replay levels simply recompute strongholds
 * instead of loading them from a cache file - invisible to gameplay.</p>
 *
 * <p>Replay-server detection is by class name ({@code com.moulberry.flashback.playback.ReplayServer})
 * so the mod stays inert when Flashback is absent.</p>
 */
@Mixin(ServerLevel.class)
public abstract class MixinModernFixStrongholdCache {

    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;ensureStructuresGenerated()V"),
            require = 0,
            expect = 0)
    private void fixFlashback$skipStrongholdCacheForReplay(
            ChunkGeneratorStructureState instance,
            Operation<Void> operation) {
        MinecraftServer server = ((ServerLevel) (Object) this).getServer();
        if (server != null
                && "com.moulberry.flashback.playback.ReplayServer".equals(server.getClass().getName())) {
            FixFlashback.LOGGER.debug(
                    "Skipping ModernFix stronghold cache setup for Flashback replay level (generator unavailable)");
            return;
        }
        operation.call(instance);
    }
}

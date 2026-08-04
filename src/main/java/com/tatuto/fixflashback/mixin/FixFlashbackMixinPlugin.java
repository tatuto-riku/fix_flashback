package com.tatuto.fixflashback.mixin;

import com.tatuto.fixflashback.FixFlashback;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * The actual fix: removes the unresolvable superinterface from Flashback's fake-player packet
 * listener while the class is being transformed, before the JVM ever tries to link it.
 *
 * <h2>The crash</h2>
 * <pre>
 * Description: Starting integrated server
 * java.lang.NoClassDefFoundError: net/fabricmc/fabric/impl/networking/UntrackedNetworkHandler
 *   at java.lang.ClassLoader.defineClass1(Native Method)
 *   at net.minecraft.client.server.IntegratedServer.&lt;init&gt;(IntegratedServer.java:65)
 * </pre>
 * Flashback declares
 * {@code FlashbackFakePlayerPacketListener implements UntrackedNetworkHandler}. That interface is a
 * Fabric API <em>internal</em> that exists only upstream; Forgified Fabric API never shipped it.
 * The JVM cannot define a class whose superinterface is missing, so every world load dies.
 *
 * <h2>Why we cannot simply supply the interface</h2>
 * The obvious fix - shipping {@code net.fabricmc.fabric.impl.networking.UntrackedNetworkHandler}
 * inside this mod - does not work on NeoForge. Each mod jar becomes a JPMS module, and Forgified
 * Fabric API's {@code fabric_networking_api_v1} module already exports that package. Two modules
 * exporting the same package is a split package, which the module system rejects outright:
 * <pre>
 * java.lang.module.ResolutionException: Modules fabric_networking_api_v1 and fix_flashback
 *     export package net.fabricmc.fabric.impl.networking to module ...
 *   at cpw.mods.modlauncher.ModuleLayerHandler.buildLayer(ModuleLayerHandler.java:81)
 * </pre>
 * That fails during boot, even earlier than the original crash. The package belongs to FFAPI and
 * nothing else may contribute classes to it.
 *
 * <h2>The approach that works</h2>
 * Instead of adding the missing interface, remove the dependency on it. The interface is an empty
 * marker - it declares no methods - so deleting it from the {@code implements} list changes no
 * behaviour and leaves no abstract methods unimplemented. The class then links cleanly.
 *
 * <p>This is done from a Mixin config plugin because {@link #preApply} receives the raw
 * {@link ClassNode} during transformation, which is the only point early enough: by the time normal
 * code runs, the class has already failed to load.</p>
 *
 * <p>The marker's one real purpose - telling Fabric networking to skip this handler - is preserved
 * separately by {@link MixinFabricNetworkAddon}, which identifies the fake player by class name
 * rather than by the interface.</p>
 */
public class FixFlashbackMixinPlugin implements IMixinConfigPlugin {

    /** Internal name (slash-separated) of the interface that cannot be resolved. */
    private static final String UNTRACKED_INTERNAL = "net/fabricmc/fabric/impl/networking/UntrackedNetworkHandler";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    /**
     * Strips the unresolvable marker interface from the target class.
     *
     * <p>Runs for every class this config mixes into. Only classes that actually declare the
     * missing interface are touched, so it is a no-op on a correctly working setup - including on
     * real Fabric, where the interface resolves fine and Flashback is left untouched.</p>
     */
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (targetClass.interfaces == null || !targetClass.interfaces.contains(UNTRACKED_INTERNAL)) {
            return;
        }

        // Only strip it when it genuinely cannot be resolved. On a setup where the interface is
        // present (real Fabric, or a future FFAPI that adds it) we must leave the class alone so
        // Fabric's own instanceof checks keep working.
        if (FixFlashback.classExists(FixFlashback.UNTRACKED_HANDLER)) {
            FixFlashback.LOGGER.info("{} is present; leaving {} untouched.",
                    FixFlashback.UNTRACKED_HANDLER, targetClassName);
            return;
        }

        targetClass.interfaces.remove(UNTRACKED_INTERNAL);
        FixFlashback.markPatched();

        FixFlashback.LOGGER.info(
                "Removed unresolvable interface {} from {} - this is the fix for the "
                        + "NoClassDefFoundError crash on world load.",
                FixFlashback.UNTRACKED_HANDLER, targetClassName);
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}

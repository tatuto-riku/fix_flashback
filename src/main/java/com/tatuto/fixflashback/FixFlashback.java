package com.tatuto.fixflashback;

import com.tatuto.fixflashback.mixin.FixFlashbackMixinPlugin;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fix Flashback - stops the crash that happens when the Fabric mod
 * <a href="https://modrinth.com/mod/flashback">Flashback</a> is run on NeoForge through
 * Sinytra Connector.
 *
 * <h2>The crash</h2>
 * <pre>
 * Description: Starting integrated server
 * java.lang.NoClassDefFoundError: net/fabricmc/fabric/impl/networking/UntrackedNetworkHandler
 *   at java.lang.ClassLoader.defineClass1(Native Method)
 *   at net.minecraft.client.server.IntegratedServer.&lt;init&gt;(IntegratedServer.java:65)
 *   at net.minecraft.client.Minecraft.lambda$doWorldLoad$40(Minecraft.java:2066)
 * </pre>
 *
 * <h2>Why it happens</h2>
 * Flashback's fake replay player implements Fabric API's <em>internal</em> marker interface:
 * <pre>
 * public class FlashbackFakePlayerPacketListener extends ServerGamePacketListenerImpl
 *         implements UntrackedNetworkHandler { }
 * </pre>
 * That interface exists only in upstream Fabric API. Forgified Fabric API - the reimplementation
 * Connector runs Fabric mods against - never shipped it. A class whose superinterface cannot be
 * resolved cannot be defined, so the JVM throws {@link NoClassDefFoundError} the instant an
 * integrated server is created. That kills <em>every</em> world load, not just replays.
 * <p>
 * This is <a href="https://github.com/Sinytra/Connector/issues/2287">Sinytra/Connector#2287</a>.
 *
 * <h2>Why the obvious fix does not work</h2>
 * Shipping the missing interface inside this mod fails on NeoForge. Every mod jar is a JPMS module
 * and FFAPI's {@code fabric_networking_api_v1} already exports that package; a second module
 * exporting it is a split package, rejected at boot:
 * <pre>
 * java.lang.module.ResolutionException: Modules fabric_networking_api_v1 and fix_flashback
 *     export package net.fabricmc.fabric.impl.networking to module ...
 * </pre>
 *
 * <h2>The fix</h2>
 * <ol>
 *   <li><b>Remove the broken link.</b> {@link FixFlashbackMixinPlugin} deletes the unresolvable
 *       interface from Flashback's class during transformation, before the JVM links it. The
 *       interface is an empty marker, so nothing is lost and the class loads cleanly.</li>
 *   <li><b>Keep the marker's meaning.</b> {@code MixinFabricNetworkAddon} makes Fabric's networking
 *       layer skip the fake player, which is what the marker was for. FFAPI dropped that check, so
 *       without this every {@code ServerPlayConnectionEvents.INIT} listener in the pack is handed a
 *       player with no real connection.</li>
 *   <li><b>Report clearly.</b> {@code MixinIntegratedServer} logs the state at world load so any
 *       future regression is diagnosable instead of appearing as a bare linkage error.</li>
 * </ol>
 *
 * <p>Pure add-on: contains no Flashback code, attaches only through {@code @Pseudo} mixins, and
 * does nothing when Flashback is absent.</p>
 */
@Mod(FixFlashback.MOD_ID)
public class FixFlashback {

    public static final String MOD_ID = "fix_flashback";
    public static final Logger LOGGER = LoggerFactory.getLogger("Fix Flashback");

    /** The Fabric internal class that Forgified Fabric API does not provide. */
    public static final String UNTRACKED_HANDLER =
            "net.fabricmc.fabric.impl.networking.UntrackedNetworkHandler";

    /**
     * Flashback's fake replay-player packet listener. Identified by name because the marker
     * interface that would normally identify it does not exist on this platform.
     */
    public static final String FAKE_PLAYER_LISTENER =
            "com.moulberry.flashback.playback.FlashbackFakePlayerPacketListener";

    private static volatile boolean patched;
    private static Boolean flashbackLoaded;

    public FixFlashback() {
        LOGGER.info("Fix Flashback loaded (Flashback present: {}, patch applied: {})",
                isFlashbackLoaded(), patched);
    }

    /** Called by the mixin plugin once it has stripped the unresolvable interface. */
    public static void markPatched() {
        patched = true;
    }

    /** Whether the fake-player class was actually patched this run. */
    public static boolean isPatched() {
        return patched;
    }

    /** Whether the Flashback mod is installed. */
    public static boolean isFlashbackLoaded() {
        if (flashbackLoaded == null) {
            try {
                flashbackLoaded = FMLLoader.getLoadingModList().getModFileById("flashback") != null;
            } catch (Throwable t) {
                // The mod list isn't always available this early; fall back to a class lookup.
                flashbackLoaded = classExists("com.moulberry.flashback.Flashback");
            }
        }
        return flashbackLoaded;
    }

    /**
     * Whether the given object is Flashback's untracked fake-player network handler.
     *
     * <p>Matched by class name: the {@code UntrackedNetworkHandler} interface that would normally
     * mark it has been removed, precisely because it cannot be resolved here.</p>
     */
    public static boolean isUntrackedHandler(Object handler) {
        return handler != null && FAKE_PLAYER_LISTENER.equals(handler.getClass().getName());
    }

    /** Whether a class can be resolved on the current classpath. */
    public static boolean classExists(String name) {
        try {
            Class.forName(name, false, FixFlashback.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

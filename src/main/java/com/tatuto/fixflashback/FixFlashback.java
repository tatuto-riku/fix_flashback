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
        logFlashbackMixinConflicts();
    }

    /**
     * Scans every mixin config on the classpath and reports which OTHER mods target the same classes
     * Flashback rewrites for its replay features (camera/spectator, night-vision, time override,
     * client level). This makes mod conflicts visible in the log without external tooling.
     */
    private static void logFlashbackMixinConflicts() {
        if (!isFlashbackLoaded()) {
            return;
        }
        // Classes Flashback's replay features depend on.
        String[] flashbackTargets = {
                "GameRenderer", "LightTexture", "ClientLevelData", "Camera",
                "Minecraft", "LevelRenderer", "Gui", "Options", "MouseHandler"
        };
        try {
            ClassLoader cl = FixFlashback.class.getClassLoader();
            java.util.Enumeration<java.net.URL> resources = cl.getResources("");
            java.util.Set<String> scanned = new java.util.HashSet<>();
            java.util.Map<String, java.util.Set<String>> hits = new java.util.LinkedHashMap<>();
            for (java.net.URL url : java.util.Collections.list(
                    cl.getResources("META-INF"))) {
                // Walk the jar / dir for *.mixins.json
                scanMixins(url, flashbackTargets, hits, scanned);
            }
            if (!hits.isEmpty()) {
                LOGGER.info("[Fix Flashback] Potential replay-feature mixin conflicts:");
                for (java.util.Map.Entry<String, java.util.Set<String>> e : hits.entrySet()) {
                    LOGGER.info("  {} targeted by: {}", e.getKey(), e.getValue());
                }
            } else {
                LOGGER.info("[Fix Flashback] No obvious replay-feature mixin conflicts found.");
            }
        } catch (Throwable t) {
            LOGGER.debug("[Fix Flashback] Conflict scan failed", t);
        }
    }

    private static void scanMixins(java.net.URL metaUrl, String[] targets,
                                   java.util.Map<String, java.util.Set<String>> hits,
                                   java.util.Set<String> scanned) {
        try {
            java.nio.file.Path base;
            String str = metaUrl.toString();
            if (str.startsWith("jar:")) {
                String jarPath = str.substring("jar:".length(), str.lastIndexOf("!/"));
                base = java.nio.file.Paths.get(java.net.URI.create(jarPath));
                try (java.util.stream.Stream<java.nio.file.Path> walk =
                             java.nio.file.Files.walk(base)) {
                    walk.filter(p -> p.getFileName().toString().endsWith(".mixins.json"))
                            .forEach(p -> readMixinJson(p, targets, hits, scanned));
                }
            } else if (str.startsWith("file:")) {
                base = java.nio.file.Paths.get(java.net.URI.create(str));
                try (java.util.stream.Stream<java.nio.file.Path> walk =
                             java.nio.file.Files.walk(base)) {
                    walk.filter(p -> p.getFileName().toString().endsWith(".mixins.json"))
                            .forEach(p -> readMixinJson(p, targets, hits, scanned));
                }
            }
        } catch (Throwable ignored) {
            // Best-effort only.
        }
    }

    private static void readMixinJson(java.nio.file.Path jsonPath, String[] targets,
                                      java.util.Map<String, java.util.Set<String>> hits,
                                      java.util.Set<String> scanned) {
        try {
            String abs = jsonPath.toAbsolutePath().toString();
            if (!scanned.add(abs)) {
                return;
            }
            String text = java.nio.file.Files.readString(jsonPath);
            String jarName = jsonPath.getRoot() != null ? jsonPath.getRoot().toString() : abs;
            for (String t : targets) {
                if (text.contains(t)) {
                    hits.computeIfAbsent(t, k -> new java.util.LinkedHashSet<>()).add(jarName);
                }
            }
        } catch (Throwable ignored) {
            // Best-effort only.
        }
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

    /**
     * Whether a Flashback replay is currently being played back.
     *
     * <p>Used to disable client-side interpolation tweaks (e.g. from Smooth Movement) that turn
     * Flashback's per-tick position updates into long, slow linear slides between recorded
     * positions.</p>
     */
    public static boolean isReplayPlaying() {
        if (!isFlashbackLoaded()) {
            return false;
        }
        try {
            Class<?> flashbackClass = Class.forName("com.moulberry.flashback.Flashback",
                    false, FixFlashback.class.getClassLoader());
            Object replayServer = flashbackClass.getMethod("getReplayServer").invoke(null);
            return replayServer != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean smoothMovementDisabled = false;

    /**
     * Smooth Movement eases entity position packets over many frames via large lerpSteps. During a
     * Flashback replay (which already sends a position every tick) this produces the "entities slide
     * in straight lines between recorded positions" issue. We disable its smoothing config flag while
     * a replay is playing so Flashback's own interpolation is restored.
     */
    public static void disableSmoothMovementIfReplaying() {
        if (smoothMovementDisabled || !isReplayPlaying()) {
            return;
        }
        try {
            Class<?> configClass = Class.forName("com.smoothmovement.config.CommonConfiguration",
                    false, FixFlashback.class.getClassLoader());
            java.lang.reflect.Field configField = configClass.getDeclaredField("config");
            configField.setAccessible(true);
            Object cupboardConfig = configField.get(null);
            if (cupboardConfig == null) {
                return;
            }
            // CupboardConfig<T> has getCommonConfig() returning the config instance.
            Object configInstance = cupboardConfig.getClass()
                    .getMethod("getCommonConfig").invoke(cupboardConfig);
            if (configInstance == null) {
                return;
            }
            java.lang.reflect.Field flagField =
                    configInstance.getClass().getField("enableLivingEntitySmoothing");
            flagField.setBoolean(configInstance, false);
            smoothMovementDisabled = true;
            LOGGER.info("[Fix Flashback] Smooth Movement entity smoothing disabled during replay");
        } catch (Throwable t) {
            LOGGER.info("[Fix Flashback] Could not disable Smooth Movement: {}", t.getMessage());
        }
    }

    /**
     * Forces an entity's client-side interpolation step count back to the vanilla default (3) right
     * after a teleport packet is applied. Smooth Movement raises this value, causing the slow linear
     * slide between recorded positions. Reflection is used because the field name cannot be remapped
     * without a refMap under NeoForge.
     */
    public static void clampEntityLerpSteps(net.minecraft.world.entity.Entity entity) {
        if (!isReplayPlaying() || entity == null) {
            return;
        }
        try {
            java.lang.reflect.Field f = entity.getClass().getField("lerpSteps");
            if (f.getInt(entity) != 3) {
                f.setInt(entity, 3);
            }
        } catch (Throwable ignored) {
            // Field not accessible / not present; ignore.
        }
    }

}

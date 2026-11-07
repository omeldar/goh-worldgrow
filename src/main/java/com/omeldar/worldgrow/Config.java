package com.omeldar.worldgrow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings, at {@code config/worldgrow.json}.
 *
 * <p>The defaults are chosen so that turning this on and forgetting about it cannot ruin
 * anybody's evening. One chunk at a time, nothing while anyone is online, and a hand on the
 * brake the moment the server starts falling behind. It will take days to pregenerate a large
 * border that way, which is the right trade: nobody is waiting on it, and everybody notices a
 * stuttering server.
 */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    /** Chunks to ask for per tick while running. Raising this is the main speed dial. */
    public int chunksPerTick = 1;
    /** Never have more than this many generations in flight at once. */
    public int maxInFlight = 12;
    /**
     * Stop asking for chunks when the server's average tick time is above this, in ms. A tick
     * has 50 ms; backing off at 25 leaves half the budget for the game itself.
     */
    public double maxMspt = 25.0;

    /** Whether a job on this server should wait for it to empty. */
    public static boolean waitsForPlayers(net.minecraft.server.MinecraftServer server) {
        return get().pauseWhenPlayersOnline && server.isDedicated();
    }
    /**
     * Do nothing at all while anyone is on the server. Dedicated servers only: in single
     * player somebody is always online, so honouring it there would mean never running.
     */
    public boolean pauseWhenPlayersOnline = true;
    /**
     * Refuse any job with a larger radius than this, in blocks. 0 for no cap. The default
     * matches hosts that limit pregeneration (Shockbyte caps it at a 10,000 block radius and
     * blocks pregenerators that cannot be capped).
     */
    public int maxRadiusBlocks = 10_000;
    /**
     * Pause the job once the world folder reaches this size, in GB. 0 for no limit. The
     * default matches Shockbyte, whose terms do not allow pregenerated chunks in a world over
     * 5 GB.
     */
    public double stopAtWorldGigabytes = 5.0;
    /** After this many chunks, flush to disk so the generated ones can be unloaded again. */
    public int saveEveryChunks = 256;
    /** Report progress to the log this often, in seconds. 0 to stay quiet. */
    public int logEverySeconds = 300;
    /**
     * Resume an unfinished job when the server starts. On by default, because the whole
     * point is that this finishes eventually without anybody tending it.
     */
    public boolean resumeOnStart = true;

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("worldgrow.json");
    }

    public static Path progressFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("worldgrow-progress.json");
    }

    public static synchronized Config get() {
        if (instance == null) load();
        return instance;
    }

    public static synchronized void load() {
        Config cfg = null;
        try {
            if (Files.exists(file())) {
                cfg = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Config.class);
            }
        } catch (Exception e) {
            WorldgrowServer.LOG.warn("worldgrow.json unreadable, falling back to defaults", e);
        }
        instance = cfg == null ? new Config() : cfg;
        save();
    }

    public static synchronized void save() {
        if (instance == null) return;
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (Exception e) {
            WorldgrowServer.LOG.warn("could not write worldgrow.json", e);
        }
    }
}

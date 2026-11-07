package com.omeldar.worldgrow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * One pregeneration job: a square of chunks around a centre, generated on a budget.
 *
 * <p>Why this exists at all: when a world border grows, nothing happens until somebody walks
 * into the new ground, and then the server generates every chunk in their view distance on the
 * spot - terrain, caves, structures, lighting - while also running the game for everyone else.
 * That is the stutter people blame on the new area. Generating it ahead of time, slowly, while
 * nobody is on, turns that into an ordinary chunk load.
 *
 * <p>The budget is the design. Every tick it asks three questions before doing anything: is
 * anybody on, is the server keeping up, and are enough generations already in flight. Any no
 * and it waits. It never tries to catch up on time it lost waiting.
 */
public final class Job {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Persisted state. Plain fields so the progress file is readable by a human.
    public String dimension = "minecraft:overworld";
    /**
     * The save folder this job belongs to. A client keeps one config folder for every single
     * player world, so without this a job started in one save would resume in the next.
     * Blank in progress files written before it existed, which then resume as they always did.
     */
    public String save = "";
    public int centerChunkX;
    public int centerChunkZ;
    public int radiusChunks;
    public long index;
    public long generated;
    public boolean paused;
    /** Why the job paused itself, when it did. Blank when a person paused it. */
    public String pausedFor = "";
    public long startedAtMillis;
    public long activeMillis;

    private transient Spiral spiral;
    private transient int inFlight;
    private transient long sinceSave;
    private transient long lastLogAt;
    private transient long lastTickMillis;
    private transient String waitingFor = "";

    public long total() {
        return Spiral.cells(radiusChunks);
    }

    public boolean done() {
        return index >= total() && inFlight == 0;
    }

    public double percent() {
        long t = total();
        return t == 0 ? 100.0 : 100.0 * Math.min(index, t) / t;
    }

    public String waitingFor() {
        return waitingFor;
    }

    public int inFlight() {
        return inFlight;
    }

    private transient long lastSizeCheckAt;

    /**
     * Null while the world folder is under {@code stopAtWorldGigabytes}, otherwise why it is
     * not. Walks the save folder, which is a few thousand files at most at these sizes, so it
     * is run once a minute at the most and never per tick.
     */
    public static String worldTooBig(MinecraftServer server) {
        double limit = Config.get().stopAtWorldGigabytes;
        if (limit <= 0) return null;
        java.nio.file.Path root = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT);
        long bytes = 0;
        try (var files = Files.walk(root)) {
            bytes = files.filter(Files::isRegularFile).mapToLong(f -> {
                try {
                    return Files.size(f);
                } catch (Exception e) {
                    return 0L;
                }
            }).sum();
        } catch (Exception e) {
            WorldgrowServer.LOG.warn("could not measure the world folder", e);
            return null;
        }
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb < limit) return null;
        return String.format(Locale.ROOT, "the world folder is %.1f GB, at the %.1f GB limit "
                + "(stopAtWorldGigabytes in worldgrow.json)", gb, limit);
    }

    public static String saveId(MinecraftServer server) {
        return server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                .toAbsolutePath().normalize().toString();
    }

    public boolean belongsTo(MinecraftServer server) {
        return save == null || save.isBlank() || save.equals(saveId(server));
    }

    public ServerWorld world(MinecraftServer server) {
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dimension));
        return server.getWorld(key);
    }

    /** Called every server tick while a job exists. */
    public void tick(MinecraftServer server) {
        if (spiral == null) {
            spiral = new Spiral();
            spiral.skipTo(index);
        }
        long now = System.currentTimeMillis();
        if (paused) {
            waitingFor = pausedFor == null || pausedFor.isBlank() ? "paused" : "paused: " + pausedFor;
            lastTickMillis = 0;
            return;
        }

        Config cfg = Config.get();

        if (Config.waitsForPlayers(server) && server.getPlayerManager().getCurrentPlayerCount() > 0) {
            waitingFor = "players online";
            lastTickMillis = 0;
            return;
        }

        double mspt = server.getAverageTickTime();
        if (cfg.maxMspt > 0 && mspt > cfg.maxMspt) {
            waitingFor = String.format(Locale.ROOT, "server busy (%.1f ms/tick)", mspt);
            return;
        }

        if (inFlight >= Math.max(1, cfg.maxInFlight)) {
            waitingFor = "generations in flight";
            return;
        }

        ServerWorld world = world(server);
        if (world == null) {
            waitingFor = "dimension " + dimension + " is not loaded";
            paused = true;
            return;
        }

        waitingFor = "";
        if (lastTickMillis > 0) activeMillis += now - lastTickMillis;
        lastTickMillis = now;

        int budget = Math.max(1, cfg.chunksPerTick);
        long total = total();
        while (budget-- > 0 && index < total && inFlight < cfg.maxInFlight) {
            int cx = centerChunkX + spiral.x();
            int cz = centerChunkZ + spiral.z();
            spiral.advance();
            index = spiral.index();
            request(server, world, cx, cz);
        }

        if (++sinceSave >= Math.max(16, cfg.saveEveryChunks) / Math.max(1, cfg.chunksPerTick)) {
            sinceSave = 0;
            // Flush so the chunks just generated can be unloaded again. Without this the
            // job holds a growing pile of chunks in memory until the next autosave.
            server.saveAll(true, false, false);
            save();
            if (now - lastSizeCheckAt > 60_000) {
                lastSizeCheckAt = now;
                String full = worldTooBig(server);
                if (full != null) {
                    paused = true;
                    pausedFor = full;
                    waitingFor = "paused: " + full;
                    save();
                    WorldgrowServer.LOG.warn("worldgrow paused: {}", full);
                    return;
                }
            }
        }

        if (cfg.logEverySeconds > 0 && now - lastLogAt > cfg.logEverySeconds * 1000L) {
            lastLogAt = now;
            WorldgrowServer.LOG.info("worldgrow: {}", summary());
        }
    }

    private void request(MinecraftServer server, ServerWorld world, int cx, int cz) {
        inFlight++;
        world.getChunkManager()
                .getChunkFutureSyncOnMainThread(cx, cz, ChunkStatus.FULL, true)
                .whenComplete((chunk, error) -> server.execute(() -> {
                    inFlight--;
                    if (error == null) {
                        generated++;
                    } else {
                        WorldgrowServer.LOG.warn("chunk {} {} failed: {}", cx, cz, error.toString());
                    }
                }));
    }

    public String summary() {
        String eta = "";
        long chunksPerSecond = activeMillis > 10_000 ? generated * 1000 / Math.max(1, activeMillis) : 0;
        if (chunksPerSecond > 0) {
            long left = Math.max(0, total() - index) / chunksPerSecond;
            eta = String.format(Locale.ROOT, ", ~%s of running time left", duration(left));
        }
        return String.format(Locale.ROOT,
                "%s: %,d of %,d chunks (%.1f%%), radius %d blocks around chunk %d %d%s%s",
                dimension, Math.min(index, total()), total(), percent(),
                radiusChunks * 16, centerChunkX, centerChunkZ, eta,
                waitingFor.isEmpty() ? "" : " - waiting: " + waitingFor);
    }

    public static String duration(long seconds) {
        if (seconds < 120) return seconds + "s";
        if (seconds < 7200) return (seconds / 60) + " min";
        if (seconds < 172_800) return String.format(Locale.ROOT, "%.1f h", seconds / 3600.0);
        return String.format(Locale.ROOT, "%.1f days", seconds / 86400.0);
    }

    // ---------------------------------------------------------------- persistence

    public void save() {
        try {
            Files.writeString(Config.progressFile(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            WorldgrowServer.LOG.warn("could not save progress", e);
        }
    }

    public static Job load() {
        try {
            if (!Files.exists(Config.progressFile())) return null;
            Job job = GSON.fromJson(
                    Files.readString(Config.progressFile(), StandardCharsets.UTF_8), Job.class);
            if (job == null || job.radiusChunks <= 0) return null;
            return job;
        } catch (Exception e) {
            WorldgrowServer.LOG.warn("progress file unreadable - not resuming", e);
            return null;
        }
    }

    public static void clear() {
        try {
            Files.deleteIfExists(Config.progressFile());
        } catch (Exception ignored) {
            // A leftover progress file is harmless: the next start overwrites it.
        }
    }
}

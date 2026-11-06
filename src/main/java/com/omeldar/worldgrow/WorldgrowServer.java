package com.omeldar.worldgrow;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worldgrow: make the map bigger without the stutter.
 *
 * <p>Growing a world border is one vanilla command. What makes it painful is that the new
 * ground does not exist yet, so the first people to walk into it pay for generating it, live,
 * on the same thread that runs everyone's game. This mod generates it beforehand - slowly, while
 * nobody is on, and never while the server is behind - and remembers where it got to across
 * restarts so a big border just finishes eventually.
 *
 * <p>It generates terrain exactly as vanilla would have; it only changes <em>when</em>. The
 * chunks it writes are byte-for-byte what a player walking there would have caused.
 */
public final class WorldgrowServer implements DedicatedServerModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("worldgrow");

    private static Job job;

    public static synchronized Job job() {
        return job;
    }

    public static synchronized void setJob(Job next) {
        if (job != null) job.save();
        job = next;
        if (job != null) job.save();
    }

    public static synchronized void clearJob() {
        job = null;
        Job.clear();
    }

    @Override
    public void onInitializeServer() {
        Config.load();
        GrowCommand.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!Config.get().resumeOnStart) return;
            Job saved = Job.load();
            if (saved != null && !saved.done()) {
                job = saved;
                LOG.info("resuming pregeneration - {}", saved.summary());
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Job j = job();
            if (j != null) j.save();
        });

        ServerTickEvents.END_SERVER_TICK.register(WorldgrowServer::tick);
        LOG.info("worldgrow ready - /worldgrow");
    }

    /**
     * Stop an empty server from pausing while there is generation to do.
     *
     * <p>Since 1.21.2 a dedicated server stops ticking once it has been empty for
     * {@code pause-when-empty-seconds} (60 by default). That is a good default and a direct
     * conflict with this mod: generation is driven from the tick, and the polite setting is
     * to run only while nobody is on - exactly when the server is not ticking. Left alone,
     * the two would agree to never run at all. The first smoke test generated zero chunks in
     * a hundred seconds for precisely this reason.
     *
     * <p>So while a job is running and not paused, the idle counter is held at zero. The cost
     * is that the empty server keeps ticking until the job finishes or is paused, which is the
     * work that was asked for; afterwards the vanilla pause applies again untouched.
     */
    static void keepAwake(MinecraftServer server) {
        server.idleTickCount = 0;
    }

    private static void tick(MinecraftServer server) {
        Job j = job;
        if (j == null) return;
        if (!j.paused) keepAwake(server);
        j.tick(server);
        if (j.done()) {
            LOG.info("pregeneration finished - {}", j.summary());
            server.saveAll(true, false, false);
            GrowCommand.announce(server, "pregeneration finished: " + j.generated
                    + " chunks in " + j.dimension);
            clearJob();
        }
    }
}

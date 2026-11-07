package com.omeldar.worldgrow;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.border.WorldBorder;

import java.util.Locale;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * {@code /worldgrow}. Status is readable by anyone; everything that starts work needs an admin.
 *
 * <p>The usual way to grow the map is two commands:
 * <pre>
 *   /worldborder set 20000
 *   /worldgrow start border
 * </pre>
 * and then nothing, for however long it takes.
 */
public final class GrowCommand {
    /** Rough bytes per generated chunk on disk, for the estimate. Varies a lot by terrain. */
    private static final double KB_PER_CHUNK_LOW = 4;
    private static final double KB_PER_CHUNK_HIGH = 10;

    private GrowCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
                dispatcher.register(literal("worldgrow")
                        .executes(ctx -> status(ctx.getSource()))
                        .then(literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(literal("plan")
                                .requires(GrowCommand::isAdmin)
                                .then(literal("border").executes(ctx -> planBorder(ctx.getSource())))
                                .then(argument("radius", IntegerArgumentType.integer(16, 2_000_000))
                                        .executes(ctx -> plan(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radius")))))
                        .then(literal("start")
                                .requires(GrowCommand::isAdmin)
                                .then(literal("border").executes(ctx -> startBorder(ctx.getSource())))
                                .then(argument("radius", IntegerArgumentType.integer(16, 2_000_000))
                                        .executes(ctx -> startRadius(ctx,
                                                IntegerArgumentType.getInteger(ctx, "radius")))))
                        .then(literal("pause").requires(GrowCommand::isAdmin)
                                .executes(ctx -> setPaused(ctx.getSource(), true)))
                        .then(literal("resume").requires(GrowCommand::isAdmin)
                                .executes(ctx -> setPaused(ctx.getSource(), false)))
                        .then(literal("stop").requires(GrowCommand::isAdmin)
                                .executes(ctx -> stop(ctx.getSource())))
                        .then(literal("reload").requires(GrowCommand::isAdmin)
                                .executes(ctx -> {
                                    Config.load();
                                    reply(ctx.getSource(), "config reloaded", Formatting.GREEN);
                                    return 1;
                                }))));
    }

    private static boolean isAdmin(ServerCommandSource source) {
        return source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.ADMINS));
    }

    // ------------------------------------------------------------------ actions

    private static int status(ServerCommandSource source) {
        Job job = WorldgrowServer.job();
        if (job == null) {
            reply(source, "nothing generating. /worldgrow plan border to see what the current "
                    + "world border would take", Formatting.GRAY);
            return 1;
        }
        reply(source, job.summary(), Formatting.AQUA);
        reply(source, String.format(Locale.ROOT, "  %,d chunks generated this job, %d in flight",
                job.generated, job.inFlight()), Formatting.GRAY);
        Config cfg = Config.get();
        reply(source, String.format(Locale.ROOT,
                "  budget: %d/tick, backs off above %.0f ms/tick%s",
                cfg.chunksPerTick, cfg.maxMspt,
                Config.waitsForPlayers(source.getServer()) ? ", waits while anyone is online" : ""),
                Formatting.DARK_GRAY);
        return 1;
    }

    private static int planBorder(ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        WorldBorder border = world.getWorldBorder();
        int radius = (int) Math.ceil(border.getSize() / 2.0);
        if (radius > 2_000_000) {
            reply(source, "the border here is effectively unlimited (" + (long) border.getSize()
                    + " blocks across). Set one with /worldborder set first.", Formatting.YELLOW);
            return 1;
        }
        reply(source, "the world border in " + world.getRegistryKey().getValue()
                + " is " + (int) border.getSize() + " blocks across, centred on "
                + (int) border.getCenterX() + " " + (int) border.getCenterZ(), Formatting.GRAY);
        return plan(source, radius);
    }

    private static int plan(ServerCommandSource source, int radiusBlocks) {
        int radiusChunks = (radiusBlocks + 15) / 16;
        long cells = Spiral.cells(radiusChunks);
        Config cfg = Config.get();
        double perSecond = Math.max(1, cfg.chunksPerTick) * 20.0;

        int cap = Config.get().maxRadiusBlocks;
        if (cap > 0 && radiusBlocks > cap) {
            reply(source, "note: that is over the " + cap + " block cap, so start would refuse it",
                    Formatting.YELLOW);
        }
        reply(source, String.format(Locale.ROOT, "radius %d blocks = %,d chunks", radiusBlocks, cells),
                Formatting.AQUA);
        reply(source, String.format(Locale.ROOT,
                "  disk: very roughly %.0f-%.0f MB once generated (terrain varies a lot)",
                cells * KB_PER_CHUNK_LOW / 1024, cells * KB_PER_CHUNK_HIGH / 1024),
                Formatting.GRAY);
        reply(source, String.format(Locale.ROOT,
                "  time: at least %s of running time at %d/tick - in practice several times that,"
              + " since generation is usually slower than the request rate and it waits whenever "
              + "the server is busy%s",
                Job.duration((long) (cells / perSecond)), cfg.chunksPerTick,
                Config.waitsForPlayers(source.getServer()) ? " or anyone is online" : ""),
                Formatting.GRAY);
        reply(source, "  chunks already generated are loaded rather than regenerated, so "
                + "re-running over an existing area is cheap", Formatting.DARK_GRAY);
        return 1;
    }

    private static int startBorder(ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        WorldBorder border = world.getWorldBorder();
        if (border.getSize() / 2.0 > 2_000_000) {
            reply(source, "the border here is effectively unlimited. Set one with "
                    + "/worldborder set <size> first, then run this again.", Formatting.YELLOW);
            return 1;
        }
        int radius = (int) Math.ceil(border.getSize() / 2.0);
        BlockPos centre = BlockPos.ofFloored(border.getCenterX(), 0, border.getCenterZ());
        return start(source, world, centre, radius);
    }

    private static int startRadius(CommandContext<ServerCommandSource> ctx, int radius) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        BlockPos centre = world.getSpawnPoint().getPos();
        return start(source, world, centre, radius);
    }

    private static int start(ServerCommandSource source, ServerWorld world, BlockPos centre,
                             int radiusBlocks) {
        Job existing = WorldgrowServer.job();
        if (existing != null) {
            reply(source, "a job is already running (" + existing.summary() + "). "
                    + "/worldgrow stop first.", Formatting.YELLOW);
            return 1;
        }
        int cap = Config.get().maxRadiusBlocks;
        if (cap > 0 && radiusBlocks > cap) {
            reply(source, "refused: a radius of " + radiusBlocks + " blocks is over the "
                    + cap + " block cap (maxRadiusBlocks in worldgrow.json). Some hosts, "
                    + "Shockbyte among them, do not allow pregeneration beyond 10,000.",
                    Formatting.RED);
            return 0;
        }
        String full = Job.worldTooBig(source.getServer());
        if (full != null) {
            reply(source, "refused: " + full, Formatting.RED);
            return 0;
        }
        Job job = new Job();
        job.dimension = world.getRegistryKey().getValue().toString();
        job.save = Job.saveId(source.getServer());
        job.centerChunkX = centre.getX() >> 4;
        job.centerChunkZ = centre.getZ() >> 4;
        job.radiusChunks = (radiusBlocks + 15) / 16;
        job.startedAtMillis = System.currentTimeMillis();
        WorldgrowServer.setJob(job);
        // An empty server may already be paused, and then the tick hook that keeps it awake
        // never runs. Wake it from here; the hook takes over from the next tick.
        WorldgrowServer.keepAwake(source.getServer());
        reply(source, "started: " + job.summary(), Formatting.GREEN);
        if (Config.waitsForPlayers(source.getServer())
                && source.getServer().getPlayerManager().getCurrentPlayerCount() > 0) {
            reply(source, "  it will wait until everyone has logged off, as configured",
                    Formatting.GRAY);
        }
        return 1;
    }

    private static int setPaused(ServerCommandSource source, boolean paused) {
        Job job = WorldgrowServer.job();
        if (job == null) {
            reply(source, "nothing to " + (paused ? "pause" : "resume"), Formatting.GRAY);
            return 1;
        }
        if (!paused) {
            String full = Job.worldTooBig(source.getServer());
            if (full != null) {
                reply(source, "still refusing: " + full, Formatting.RED);
                return 0;
            }
        }
        job.paused = paused;
        job.pausedFor = "";
        job.save();
        if (!paused) WorldgrowServer.keepAwake(source.getServer());
        reply(source, paused ? "paused" : "resumed", Formatting.GREEN);
        return 1;
    }

    private static int stop(ServerCommandSource source) {
        Job job = WorldgrowServer.job();
        if (job == null) {
            reply(source, "nothing running", Formatting.GRAY);
            return 1;
        }
        String was = job.summary();
        WorldgrowServer.clearJob();
        reply(source, "stopped and forgotten: " + was, Formatting.GREEN);
        reply(source, "  what was generated stays generated", Formatting.DARK_GRAY);
        return 1;
    }

    // ---------------------------------------------------------------- plumbing

    static void announce(MinecraftServer server, String message) {
        server.getPlayerManager().broadcast(
                Text.literal("[worldgrow] " + message).formatted(Formatting.DARK_GRAY), false);
    }

    private static void reply(ServerCommandSource source, String message, Formatting colour) {
        source.sendFeedback(() -> Text.literal(message).formatted(colour), false);
    }
}

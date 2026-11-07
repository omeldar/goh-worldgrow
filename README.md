# Worldgrow

Make the map bigger without the stutter.

Growing a world border is one vanilla command. What makes it painful is that the new ground does
not exist yet, so the first people to walk into it pay for generating it - terrain, caves,
structures, lighting - live, on the same thread that runs everybody else's game. That is the lag
people blame on the new area.

Worldgrow generates it beforehand: slowly, while nobody is on, never while the server is behind,
and it remembers where it got to across restarts, so a big border just finishes eventually.

**Minecraft 1.21.11 / Fabric, server side.** Nothing on the client. It also runs in single player,
inside the game's built-in server, which is the easy way to try it first.

---

## It changes when, not what

Terrain comes from the vanilla generator with the world's own seed, exactly as it would have.
The chunks written are the ones a player walking there would have caused. Nothing about the world
is different afterwards except that the next person to fly out there gets an ordinary chunk load
instead of a generation stall.

## Growing the map

```
/worldborder set 20000
/worldgrow plan border
/worldgrow start border
```

and then nothing, for however long it takes. `plan` tells you first what you are signing up for:

```
the world border in minecraft:overworld is 20000 blocks across, centred on 0 0
radius 10000 blocks = 1,565,001 chunks
  disk: very roughly 6113-15283 MB once generated (terrain varies a lot)
  time: at least 21.7 h of running time at 1/tick - in practice several times that, ...
```

That estimate is deliberately pessimistic and deliberately loose. Generation speed depends on the
CPU, the terrain and the dimension far more than on any setting, so the honest answer is a floor
and a warning rather than a confident number.

## The budget

Every tick, before asking for a single chunk, it asks three questions:

1. **Is anybody on?** By default it waits until the server is empty.
2. **Is the server keeping up?** Above `maxMspt` milliseconds per tick (default 25 - half the
   50 ms budget) it waits.
3. **Is enough already in flight?** It never has more than `maxInFlight` generations queued.

Any no, and it waits. It never tries to catch up on time it lost waiting, which is the difference
between a background job and a scheduled outage.

It also flushes to disk every few hundred chunks, so that what it just generated can be unloaded
again rather than piling up in memory until the next autosave.

The defaults are chosen so that turning it on and forgetting about it cannot ruin anybody's
evening. They will take days over a large border. That is the right trade: nobody is waiting on
the pregeneration, and everybody notices a stuttering server. If you want it faster and you know
the machine has headroom, raise `chunksPerTick` and `maxInFlight`, or turn off
`pauseWhenPlayersOnline`.

In single player that setting does nothing, because you are always online and it would never
run. The tick budget still applies, so you can play while it works.

## It keeps an empty server awake, and only then

Since 1.21.2 a dedicated server stops ticking once it has been empty for
`pause-when-empty-seconds` (60 by default). That is a good default, and a direct conflict here:
generation is driven from the tick, and the polite time to generate is while nobody is on -
exactly when the server is not ticking. Left alone, the two agree to never run at all. The first
test of this mod generated zero chunks in a hundred seconds for precisely that reason.

So while a job is running and not paused, Worldgrow holds the server's idle counter at zero
(one field, through an access widener; no mixin). The empty server keeps ticking until the job
finishes or you `/worldgrow pause` it, and from then on the vanilla pause applies again,
untouched. If you are paying for CPU by the hour, that is the cost of the work you asked for -
pause it if you would rather it waited.

## Outward, not row by row

It walks a square spiral outward from the centre. If it is stopped half way, the generated part is
a usable disc around spawn rather than a strip along one edge of the map, so a job that is 40% done
is 40% of the useful work.

## Commands

```
/worldgrow                    status, readable by anyone
/worldgrow plan border        what the current border would cost       (admin)
/worldgrow plan <radius>      same, for a radius in blocks around spawn (admin)
/worldgrow start border       generate everything inside the border     (admin)
/worldgrow start <radius>     generate a radius around world spawn      (admin)
/worldgrow pause | resume     (admin)
/worldgrow stop               forget the job; generated chunks stay     (admin)
/worldgrow reload             re-read worldgrow.json                    (admin)
```

Commands act on the dimension you run them in. From the server console that is the overworld.

## Config

`config/worldgrow.json`:

| Key | Default | |
| --- | --- | --- |
| `chunksPerTick` | 1 | The main speed dial |
| `maxInFlight` | 12 | Cap on queued generations |
| `maxMspt` | 25 | Back off above this average tick time |
| `pauseWhenPlayersOnline` | true | |
| `saveEveryChunks` | 256 | Flush so generated chunks can unload |
| `logEverySeconds` | 300 | Progress line in the server log |
| `resumeOnStart` | true | Pick an unfinished job back up after a restart |

Progress lives in `config/worldgrow-progress.json`, which is plain JSON and safe to delete.

## After it finishes

If you render a web map of the world (the GoH site does), the new area will need a re-render to
show up - Worldgrow writes region files, it does not know about your map renderer.

## Install

Fabric loader for 1.21.11 plus [Fabric API](https://modrinth.com/mod/fabric-api) on the server,
then the jar in `mods/`. `./gradlew build` to build it yourself.

Works alongside [Keeper](https://github.com/omeldar/goh-keeper), whose `/keeper status` will show
you what the pregeneration is costing while it runs.

## Licence

MIT. Not made or endorsed by Mojang or Microsoft.

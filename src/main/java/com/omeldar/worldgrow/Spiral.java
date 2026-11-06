package com.omeldar.worldgrow;

/**
 * Walks a square outward from the centre, one chunk at a time.
 *
 * <p>Outward rather than row by row, so that if the job is stopped half way the generated
 * part is a usable disc around spawn instead of a strip along one edge of the map. A spiral
 * that is 40% done is 40% of the useful work.
 *
 * <p>The state is five integers, so a job can be resumed by replaying the walk up to its saved
 * index. That replay is a few million integer additions at worst and happens once per start.
 */
public final class Spiral {
    private int x, z;
    private int dx = 1, dz = 0;
    private int segmentLength = 1;
    private int segmentPassed;
    private int turns;
    private long index;

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public long index() {
        return index;
    }

    /** Move to the next cell. The current cell is read before calling this. */
    public void advance() {
        x += dx;
        z += dz;
        index++;
        if (++segmentPassed == segmentLength) {
            segmentPassed = 0;
            int turn = dx;
            dx = -dz;
            dz = turn;
            if (++turns % 2 == 0) segmentLength++;
        }
    }

    public void skipTo(long target) {
        while (index < target) advance();
    }

    /** How many cells a square of this radius holds. */
    public static long cells(int radiusChunks) {
        long side = 2L * radiusChunks + 1;
        return side * side;
    }
}

package dev.servereer.zpeer.backend.net;

import java.util.concurrent.atomic.AtomicInteger;

// Tracks the count of "pool sockets currently dialing OR sitting idle on the
// proxy." When the count drops below target, the maintainer triggers more
// dials. Counters are adjusted by the lifecycle handlers as sockets move
// between states.
public final class PoolMaintainer {

    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile int target;

    public void setTarget(int t) { this.target = t; }
    public int  target()         { return target; }
    public int  inFlight()       { return inFlight.get(); }

    // Try to reserve a slot. Returns true if we should dial; false if already
    // at or above target.
    public boolean tryReserve() {
        while (true) {
            int cur = inFlight.get();
            if (cur >= target) return false;
            if (inFlight.compareAndSet(cur, cur + 1)) return true;
        }
    }

    // Release a slot — call this when a pool socket leaves the idle pool
    // (attached to a player) or dies before reaching idle.
    public void release() { inFlight.decrementAndGet(); }

    public void reset() { inFlight.set(0); }
}

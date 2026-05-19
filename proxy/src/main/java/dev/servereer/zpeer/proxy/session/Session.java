package dev.servereer.zpeer.proxy.session;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.netty.channel.Channel;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

// One Session per connected backend. Holds:
//   - the control channel (long-lived)
//   - the idle pool socket queue (channels in idle/framed mode)
//   - the pending-attach waiter queue (loopback channels waiting for a pool socket)
//   - the loopback listener channel
//   - the Velocity RegisteredServer handle
public final class Session {

    public final String   token;
    public final String   serverName;
    public final Channel  controlChannel;
    public final int      poolTarget;

    public volatile RegisteredServer registeredServer;
    public volatile Channel loopbackListenChannel;
    public volatile int     loopbackPort;

    private final Deque<Channel>           idlePool = new ArrayDeque<>();
    private final Deque<Consumer<Channel>> waiters  = new ArrayDeque<>();
    private final ReentrantLock            lock     = new ReentrantLock();

    public Session(String token, String serverName, Channel controlChannel, int poolTarget) {
        this.token          = token;
        this.serverName     = serverName;
        this.controlChannel = controlChannel;
        this.poolTarget     = poolTarget;
    }

    // Called when a pool socket finishes its handshake. If a player is already
    // waiting, hand it off immediately; otherwise stash it as idle.
    public void offerIdle(Channel poolSocket, Logger log) {
        Consumer<Channel> waiter = null;
        lock.lock();
        try {
            waiter = waiters.pollFirst();
            if (waiter == null) {
                idlePool.addLast(poolSocket);
            }
        } finally {
            lock.unlock();
        }
        if (waiter != null) {
            try {
                waiter.accept(poolSocket);
            } catch (RuntimeException ex) {
                log.warn("attach handoff threw", ex);
                poolSocket.close();
            }
        }
    }

    // Try to take an idle pool socket immediately. If none, register the
    // waiter; caller is responsible for timing it out.
    public Channel takeOrWait(Consumer<Channel> waiter) {
        lock.lock();
        try {
            Channel c = idlePool.pollFirst();
            if (c != null) return c;
            waiters.addLast(waiter);
            return null;
        } finally {
            lock.unlock();
        }
    }

    public boolean removeWaiter(Consumer<Channel> waiter) {
        lock.lock();
        try {
            return waiters.remove(waiter);
        } finally {
            lock.unlock();
        }
    }

    public int idlePoolSize() {
        lock.lock();
        try { return idlePool.size(); } finally { lock.unlock(); }
    }

    public void shutdown() {
        lock.lock();
        try {
            for (Channel c : idlePool) c.close();
            idlePool.clear();
            for (Consumer<Channel> w : waiters) {
                try { w.accept(null); } catch (RuntimeException ignored) {}
            }
            waiters.clear();
        } finally {
            lock.unlock();
        }
        if (loopbackListenChannel != null && loopbackListenChannel.isActive()) {
            loopbackListenChannel.close();
        }
        if (controlChannel.isActive()) controlChannel.close();
    }
}

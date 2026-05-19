package dev.servereer.zpeer.proxy.session;

import java.util.concurrent.ConcurrentHashMap;

// Indexed by serverName. v1 enforces one active backend per server name.
public final class SessionRegistry {

    private final ConcurrentHashMap<String, Session> byServer = new ConcurrentHashMap<>();

    public boolean tryRegister(Session s) {
        return byServer.putIfAbsent(s.serverName, s) == null;
    }

    public Session getByServer(String serverName) {
        return byServer.get(serverName);
    }

    public Session unregister(String serverName) {
        return byServer.remove(serverName);
    }

    public Iterable<Session> all() {
        return byServer.values();
    }
}

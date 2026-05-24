package dev.servereer.zpeer.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.servereer.zpeer.common.tls.TlsKeyMaterial;
import dev.servereer.zpeer.proxy.cmd.ZPeerCommand;
import dev.servereer.zpeer.proxy.config.ProxyConfig;
import dev.servereer.zpeer.proxy.loopback.LoopbackListener;
import dev.servereer.zpeer.proxy.net.ControlListener;
import dev.servereer.zpeer.proxy.session.Session;
import dev.servereer.zpeer.proxy.session.SessionRegistry;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Set;

@Plugin(
        id           = "zpeer",
        name         = "ZPeer",
        version      = "0.1.0",
        description  = "Reverse-tunnel proxy plugin",
        authors      = {"servereer"}
)
public final class ZPeerProxy {

    private final ProxyServer proxy;
    private final Logger      logger;
    private final Path        dataDir;

    private volatile ProxyConfig config;
    private SessionRegistry sessions;
    private EventLoopGroup  bossGroup;
    private EventLoopGroup  workerGroup;
    private volatile ControlListener controlListener;
    private SslContext      tlsContext;

    @Inject
    public ZPeerProxy(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy   = proxy;
        this.logger  = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        try {
            this.config   = ProxyConfig.loadOrCreate(dataDir);
            this.sessions = new SessionRegistry();

            // Load or generate TLS material. Cert+key persist across restarts; the
            // SHA-256 fingerprint must be pinned in each backend's config.
            TlsKeyMaterial keys = TlsKeyMaterial.loadOrGenerate(
                    dataDir.resolve(config.tlsCertFile),
                    dataDir.resolve(config.tlsKeyFile));
            this.tlsContext = keys.serverContext();
            logger.info("[zpeer] TLS cert ready. fingerprint = {}", keys.fingerprintLabel());
            logger.info("[zpeer]   ^ paste this into each backend's `proxy.cert_fingerprint`");

            this.bossGroup   = new NioEventLoopGroup(1);
            this.workerGroup = new NioEventLoopGroup();

            this.controlListener = new ControlListener(this, bossGroup, workerGroup);
            this.controlListener.bind(config.listenHost, config.listenPort);

            // /zpeer command (reload + status)
            CommandManager cm = proxy.getCommandManager();
            CommandMeta meta = cm.metaBuilder("zpeer").plugin(this).build();
            cm.register(meta, new ZPeerCommand(this));

            logger.info("[zpeer] ready. {} token(s) configured. control={}:{} pool-target={} tls=TLSv1.3",
                    config.tokenToServer.size(), config.listenHost, config.listenPort,
                    config.poolTarget);
        } catch (Exception ex) {
            logger.error("[zpeer] failed to start", ex);
        }
    }

    public SslContext tlsContext() { return tlsContext; }

    // Intercept attempts to connect to a zpeer-managed server when no backend
    // is currently dialed in. Without this, Velocity would try to dial the
    // placeholder 127.0.0.1:1 address, fail with Connection-Refused, and dump
    // a Netty stack trace to console while showing the player a generic error.
    @Subscribe(order = PostOrder.EARLY)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (config == null) return;
        String name = event.getOriginalServer().getServerInfo().getName();
        Set<String> managed = Set.copyOf(config.tokenToServer.values());
        if (!managed.contains(name)) return; // not our server, leave alone

        Session session = sessions.getByServer(name);
        if (session != null && session.registeredServer != null) return; // live, allow

        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        event.getPlayer().sendMessage(Component.text(
                "[zpeer] '" + name + "' is offline. The backend hasn't dialed in.",
                NamedTextColor.RED));
        logger.info("[zpeer] denied connect to '{}' for {} (no active backend)",
                name, event.getPlayer().getUsername());
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        for (Session s : sessions.all()) {
            try { unregisterServer(s); } catch (RuntimeException ignored) {}
            s.shutdown();
        }
        if (controlListener != null) controlListener.close();
        if (bossGroup   != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }

    // Called by ConnectionGate after a HELLO is accepted and Session is in the
    // registry. We open a loopback acceptor here and register the server with
    // Velocity so players can be routed to it.
    public void onSessionEstablished(Session session) {
        try {
            LoopbackListener.bind(this, session, bossGroup, workerGroup);
            ServerInfo info = new ServerInfo(session.serverName,
                    new InetSocketAddress("127.0.0.1", session.loopbackPort));
            // If a placeholder with this name exists in velocity.toml (so the
            // try-list could validate at startup), drop it before we register
            // the live loopback target.
            proxy.getServer(session.serverName).ifPresent(existing -> {
                try { proxy.unregisterServer(existing.getServerInfo()); }
                catch (RuntimeException ignored) {}
            });
            RegisteredServer registered = proxy.registerServer(info);
            session.registeredServer = registered;
            logger.info("[zpeer] registered server '{}' -> 127.0.0.1:{}",
                    session.serverName, session.loopbackPort);
        } catch (Exception ex) {
            logger.error("[zpeer] failed to publish session for '{}'", session.serverName, ex);
            session.shutdown();
            sessions.unregister(session.serverName);
        }
    }

    public void onSessionLost(Session session) {
        sessions.unregister(session.serverName);
        unregisterServer(session);
        session.shutdown();
        logger.info("[zpeer] backend disconnected: server='{}'", session.serverName);
    }

    private void unregisterServer(Session session) {
        if (session.registeredServer != null) {
            try {
                proxy.unregisterServer(session.registeredServer.getServerInfo());
            } catch (RuntimeException ex) {
                logger.warn("[zpeer] unregister of '{}' failed: {}",
                        session.serverName, ex.toString());
            }
            session.registeredServer = null;
        }
    }

    public ProxyConfig     config()     { return config; }
    public SessionRegistry sessions()   { return sessions; }
    public Logger          logger()     { return logger; }
    public ProxyServer     proxyServer(){ return proxy; }
    public Path            dataDir()    { return dataDir; }

    /** Swap the live config reference. Existing sessions stay alive; new
     * HELLOs see the new token map. */
    public void setConfig(ProxyConfig newCfg) {
        this.config = newCfg;
    }

    /** Close the current control listener and re-bind on the new
     * host/port. Existing TLS sockets are unaffected — they already passed
     * accept() and are running on their own channels. */
    public synchronized void rebindListener(ProxyConfig newCfg) throws Exception {
        if (controlListener != null) {
            controlListener.close();
        }
        controlListener = new ControlListener(this, bossGroup, workerGroup);
        controlListener.bind(newCfg.listenHost, newCfg.listenPort);
    }
}

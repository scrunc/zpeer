package dev.servereer.zpeer.backend;

import dev.servereer.zpeer.backend.cmd.ZPeerBackendCommand;
import dev.servereer.zpeer.backend.net.ControlClient;
import dev.servereer.zpeer.backend.net.PoolMaintainer;
import dev.servereer.zpeer.common.tls.TlsKeyMaterial;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class ZPeerBackend extends JavaPlugin {

    private EventLoopGroup eventLoop;
    private final List<ControlClient> clients = new ArrayList<>();
    // Mirror of clients' source-of-truth ProxyConfigs so status/diff can compare
    private final List<ProxyConfig> activeProxies = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.eventLoop = new NioEventLoopGroup();

        int started = startProxiesFromCurrentConfig();
        if (started == 0) {
            getLogger().severe("[zpeer] no usable proxies. Plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PluginCommand cmd = getCommand("zpeer");
        if (cmd != null) {
            ZPeerBackendCommand handler = new ZPeerBackendCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
    }

    @Override
    public void onDisable() {
        stopAllClients();
        if (eventLoop != null) {
            eventLoop.shutdownGracefully();
            eventLoop = null;
        }
    }

    // ---- helpers used by ZPeerBackendCommand --------------------------------

    /** Stop every ControlClient and re-read config.yml from disk, then start
     *  fresh ControlClients per proxy entry. Returns the number of active
     *  proxies after reload (0 if all invalid). */
    public synchronized int reloadAll() {
        stopAllClients();
        reloadConfig();  // re-reads plugins/ZPeerBackend/config.yml
        int n = startProxiesFromCurrentConfig();
        getLogger().info("[zpeer] reload complete. active proxies: " + n);
        return n;
    }

    public synchronized int activeProxyCount() {
        return clients.size();
    }

    public synchronized List<String> statusLines() {
        List<String> out = new ArrayList<>();
        for (ProxyConfig p : activeProxies) {
            out.add(String.format("%s  -> %s:%d  (token=...%s)",
                    p.name, p.host, p.port,
                    p.token.length() <= 6 ? p.token : p.token.substring(p.token.length() - 6)));
        }
        return out;
    }

    // ---- internal ----------------------------------------------------------

    private int startProxiesFromCurrentConfig() {
        BackendConfig config = new BackendConfig(getConfig());
        if (!config.isValid()) {
            getLogger().severe("[zpeer] config.yml incomplete: ensure every proxy "
                    + "has `token` and `cert_fingerprint` set (no placeholders).");
            return 0;
        }

        for (ProxyConfig proxy : config.proxies) {
            String tag = proxy.logTag();

            SslContext tlsContext;
            try {
                byte[] pin = TlsKeyMaterial.parseFingerprint(proxy.certFingerprint);
                tlsContext = TlsKeyMaterial.clientContextPinned(pin);
            } catch (Exception ex) {
                getLogger().severe(tag + " invalid cert_fingerprint: " + ex.getMessage()
                        + " — skipping this proxy.");
                continue;
            }

            PoolMaintainer pm = new PoolMaintainer();
            ControlClient cc = new ControlClient(eventLoop, proxy, pm, tlsContext, getLogger());
            cc.start();
            clients.add(cc);
            activeProxies.add(proxy);

            getLogger().info(tag + " backend started: proxy="
                    + proxy.host + ":" + proxy.port
                    + " local=" + proxy.localHost + ":" + proxy.localPort
                    + " tls=TLSv1.3 pin=" + proxy.certFingerprint);
        }
        getLogger().info("[zpeer] active proxy connections: " + clients.size());
        return clients.size();
    }

    private void stopAllClients() {
        for (ControlClient cc : clients) {
            try { cc.stop(); } catch (Throwable ignored) {}
        }
        clients.clear();
        activeProxies.clear();
    }
}

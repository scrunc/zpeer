package dev.servereer.zpeer.backend;

import dev.servereer.zpeer.backend.net.ControlClient;
import dev.servereer.zpeer.backend.net.PoolMaintainer;
import dev.servereer.zpeer.common.tls.TlsKeyMaterial;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import org.bukkit.plugin.java.JavaPlugin;

public final class ZPeerBackend extends JavaPlugin {

    private EventLoopGroup eventLoop;
    private ControlClient  controlClient;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        BackendConfig config = new BackendConfig(getConfig());
        if (!config.isValid()) {
            getLogger().severe("[zpeer] config.yml incomplete: need both `token` and "
                    + "`proxy.cert_fingerprint` set (no placeholders). Plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Build the pinned-fingerprint TLS context up-front; fail fast if the
        // fingerprint string is malformed (wrong length, non-hex chars, etc).
        SslContext tlsContext;
        try {
            byte[] pin = TlsKeyMaterial.parseFingerprint(config.certFingerprint);
            tlsContext = TlsKeyMaterial.clientContextPinned(pin);
        } catch (Exception ex) {
            getLogger().severe("[zpeer] invalid proxy.cert_fingerprint: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.eventLoop     = new NioEventLoopGroup();
        PoolMaintainer pm  = new PoolMaintainer();
        this.controlClient = new ControlClient(eventLoop, config, pm, tlsContext, getLogger());
        this.controlClient.start();

        getLogger().info("[zpeer] backend started: proxy="
                + config.proxyHost + ":" + config.proxyPort
                + " local=" + config.localHost + ":" + config.localPort
                + " tls=TLSv1.3 pin=" + config.certFingerprint);
    }

    @Override
    public void onDisable() {
        if (controlClient != null) controlClient.stop();
        if (eventLoop != null) {
            eventLoop.shutdownGracefully();
            eventLoop = null;
        }
    }
}

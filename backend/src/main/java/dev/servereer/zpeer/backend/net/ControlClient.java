package dev.servereer.zpeer.backend.net;

import dev.servereer.zpeer.backend.ProxyConfig;
import dev.servereer.zpeer.common.ZPeerConstants;
import dev.servereer.zpeer.common.proto.FrameCodec;
import dev.servereer.zpeer.common.proto.Frames;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

// Owns the control channel lifecycle for ONE proxy: dial, send HELLO, on
// HELLO_OK start heartbeats and pool maintenance. Reconnects with exponential
// backoff on disconnect/failure. The maintainer's "target" is updated from
// HELLO_OK and reset to 0 on disconnect.
public final class ControlClient {

    private final EventLoopGroup group;
    private final ProxyConfig    proxy;
    private final String         tag;        // "[zpeer/<name>]"
    private final PoolMaintainer maintainer;
    private final PoolSocketDialer dialer;
    private final SslContext     tlsContext;
    private final Logger         log;

    private volatile Channel controlChannel;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> poolTickTask;
    private long reconnectDelayMs = ZPeerConstants.RECONNECT_INITIAL_MS;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public ControlClient(EventLoopGroup group, ProxyConfig proxy,
                         PoolMaintainer maintainer, SslContext tlsContext, Logger log) {
        this.group      = group;
        this.proxy      = proxy;
        this.tag        = proxy.logTag();
        this.maintainer = maintainer;
        this.tlsContext = tlsContext;
        this.dialer     = new PoolSocketDialer(group, proxy, maintainer, tlsContext, log);
        this.log        = log;
    }

    public void start() { connect(); }

    public void stop() {
        stopped.set(true);
        cancelTasks();
        Channel c = controlChannel;
        if (c != null && c.isActive()) c.close();
    }

    private void connect() {
        if (stopped.get()) return;
        Bootstrap b = new Bootstrap();
        b.group(group)
         .channel(NioSocketChannel.class)
         .option(ChannelOption.TCP_NODELAY, true)
         .option(ChannelOption.SO_KEEPALIVE, true)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override protected void initChannel(SocketChannel ch) {
                 SslHandler ssl = tlsContext.newHandler(ch.alloc(),
                         proxy.host, proxy.port);
                 ssl.setHandshakeTimeoutMillis(ZPeerConstants.TLS_HANDSHAKE_TIMEOUT_MS);
                 ch.pipeline()
                   .addLast("tls",     ssl)
                   .addLast("idle", new IdleStateHandler(
                           ZPeerConstants.HEARTBEAT_TIMEOUT_MS, 0, 0, TimeUnit.MILLISECONDS))
                   .addLast("decoder", new FrameCodec.Decoder())
                   .addLast("encoder", new FrameCodec.Encoder())
                   .addLast("control",  new ControlHandler(ControlClient.this, tag, log));
             }
         });
        log.info(tag + " dialing proxy at " + proxy.host + ":" + proxy.port);
        b.connect(proxy.host, proxy.port).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warning(tag + " control dial failed: " + f.cause());
                scheduleReconnect();
                return;
            }
            controlChannel = f.channel();
            f.channel().closeFuture().addListener(cl -> onControlClosed());
            f.channel().writeAndFlush(
                    Frames.hello(proxy.token, ZPeerConstants.PROTOCOL_VERSION))
              .addListener(w -> {
                  if (!w.isSuccess()) {
                      log.warning(tag + " HELLO write failed: " + w.cause());
                      f.channel().close();
                  }
              });
        });
    }

    public void onHelloOk(String serverName, int poolTarget) {
        log.info(tag + " HELLO_OK: server='" + serverName + "' pool-target=" + poolTarget);
        reconnectDelayMs = ZPeerConstants.RECONNECT_INITIAL_MS; // reset backoff
        maintainer.setTarget(poolTarget);
        startHeartbeat();
        startPoolTick();
    }

    public void onPoolTarget(int newTarget) {
        maintainer.setTarget(newTarget);
    }

    private void startHeartbeat() {
        cancelHeartbeat();
        heartbeatTask = group.next().scheduleAtFixedRate(() -> {
            Channel c = controlChannel;
            if (c != null && c.isActive()) c.writeAndFlush(Frames.heartbeat());
        }, ZPeerConstants.HEARTBEAT_INTERVAL_MS, ZPeerConstants.HEARTBEAT_INTERVAL_MS,
           TimeUnit.MILLISECONDS);
    }

    private void startPoolTick() {
        cancelPoolTick();
        poolTickTask = group.next().scheduleAtFixedRate(() -> {
            if (controlChannel == null || !controlChannel.isActive()) return;
            while (maintainer.tryReserve()) {
                dialer.dialOne();
            }
        }, 100, 200, TimeUnit.MILLISECONDS);
    }

    private void onControlClosed() {
        cancelTasks();
        maintainer.setTarget(0);
        maintainer.reset(); // forget in-flight count; idle sockets are dead
        controlChannel = null;
        if (stopped.get()) return;
        log.warning(tag + " control channel closed, will reconnect");
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, ZPeerConstants.RECONNECT_MAX_MS);
        group.next().schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelTasks() {
        cancelHeartbeat();
        cancelPoolTick();
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> t = heartbeatTask;
        if (t != null) t.cancel(false);
        heartbeatTask = null;
    }

    private void cancelPoolTick() {
        ScheduledFuture<?> t = poolTickTask;
        if (t != null) t.cancel(false);
        poolTickTask = null;
    }
}

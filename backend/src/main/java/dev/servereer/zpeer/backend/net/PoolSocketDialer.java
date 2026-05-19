package dev.servereer.zpeer.backend.net;

import dev.servereer.zpeer.common.ZPeerConstants;
import dev.servereer.zpeer.common.proto.FrameCodec;
import dev.servereer.zpeer.common.proto.Frames;
import dev.servereer.zpeer.backend.BackendConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class PoolSocketDialer {

    private final EventLoopGroup group;
    private final BackendConfig  config;
    private final PoolMaintainer maintainer;
    private final SslContext     tlsContext;
    private final Logger         log;

    public PoolSocketDialer(EventLoopGroup group, BackendConfig config,
                            PoolMaintainer maintainer, SslContext tlsContext, Logger log) {
        this.group      = group;
        this.config     = config;
        this.maintainer = maintainer;
        this.tlsContext = tlsContext;
        this.log        = log;
    }

    public void dialOne() {
        Bootstrap b = new Bootstrap();
        b.group(group)
         .channel(NioSocketChannel.class)
         .option(ChannelOption.TCP_NODELAY, true)
         .option(ChannelOption.SO_KEEPALIVE, true)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override protected void initChannel(SocketChannel ch) {
                 SslHandler ssl = tlsContext.newHandler(ch.alloc(),
                         config.proxyHost, config.proxyPort);
                 ssl.setHandshakeTimeoutMillis(ZPeerConstants.TLS_HANDSHAKE_TIMEOUT_MS);
                 ch.pipeline()
                   .addLast("tls",     ssl)
                   .addLast("idle", new IdleStateHandler(
                           ZPeerConstants.HEARTBEAT_TIMEOUT_MS, 0, 0, TimeUnit.MILLISECONDS))
                   .addLast("decoder", new FrameCodec.Decoder())
                   .addLast("encoder", new FrameCodec.Encoder())
                   .addLast("pool-hello", new PoolHelloHandler(config, maintainer, log));
             }
         });
        b.connect(config.proxyHost, config.proxyPort).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warning("[zpeer] pool dial failed: " + f.cause());
                maintainer.release();
                return;
            }
            f.channel().writeAndFlush(Frames.poolHello(config.token))
             .addListener(w -> {
                 if (!w.isSuccess()) {
                     log.warning("[zpeer] POOL_HELLO write failed: " + w.cause());
                     f.channel().close();
                 }
             });
        });
    }
}

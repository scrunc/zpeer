package dev.servereer.zpeer.proxy.net;

import dev.servereer.zpeer.common.ZPeerConstants;
import dev.servereer.zpeer.common.proto.FrameCodec;
import dev.servereer.zpeer.proxy.ZPeerProxy;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

public final class ControlListener {

    private final ZPeerProxy   plugin;
    private final EventLoopGroup boss;
    private final EventLoopGroup workers;

    private Channel listenChannel;

    public ControlListener(ZPeerProxy plugin, EventLoopGroup boss, EventLoopGroup workers) {
        this.plugin  = plugin;
        this.boss    = boss;
        this.workers = workers;
    }

    public void bind(String host, int port) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, workers)
         .channel(NioServerSocketChannel.class)
         .option(ChannelOption.SO_BACKLOG, 64)
         .childOption(ChannelOption.TCP_NODELAY, true)
         .childOption(ChannelOption.SO_KEEPALIVE, true)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override protected void initChannel(SocketChannel ch) {
                 SslHandler ssl = plugin.tlsContext().newHandler(ch.alloc());
                 ssl.setHandshakeTimeoutMillis(ZPeerConstants.TLS_HANDSHAKE_TIMEOUT_MS);
                 ch.pipeline()
                   .addLast("tls",     ssl)
                   .addLast("idle", new IdleStateHandler(
                           ZPeerConstants.HEARTBEAT_TIMEOUT_MS, 0, 0, TimeUnit.MILLISECONDS))
                   .addLast("decoder", new FrameCodec.Decoder())
                   .addLast("encoder", new FrameCodec.Encoder())
                   .addLast("gate",    new ConnectionGate(plugin));
             }
         });
        listenChannel = b.bind(host, port).sync().channel();
        plugin.logger().info("ZPeer control listener bound on {}:{}", host, port);
    }

    public void close() {
        if (listenChannel != null && listenChannel.isActive()) {
            listenChannel.close();
        }
    }
}

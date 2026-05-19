package dev.servereer.zpeer.proxy.loopback;

import dev.servereer.zpeer.proxy.ZPeerProxy;
import dev.servereer.zpeer.proxy.session.Session;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;

// Per-session loopback acceptor. Velocity dials this address as if it were a
// normal backend; we accept and hand the channel to the LoopbackHandler which
// attaches it to a pool socket.
public final class LoopbackListener {

    public static Channel bind(ZPeerProxy plugin, Session session,
                               EventLoopGroup boss, EventLoopGroup workers) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, workers)
         .channel(NioServerSocketChannel.class)
         .childOption(ChannelOption.TCP_NODELAY, true)
         .childOption(ChannelOption.AUTO_READ, false) // pause until we have a pool socket
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override protected void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast("loopback", new LoopbackHandler(plugin, session));
             }
         });
        Channel listen = b.bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        int port = ((InetSocketAddress) listen.localAddress()).getPort();
        session.loopbackListenChannel = listen;
        session.loopbackPort          = port;
        plugin.logger().info("[zpeer] loopback for '{}' bound at 127.0.0.1:{}",
                session.serverName, port);
        return listen;
    }
}

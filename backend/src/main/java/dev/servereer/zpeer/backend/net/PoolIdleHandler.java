package dev.servereer.zpeer.backend.net;

import dev.servereer.zpeer.backend.ProxyConfig;
import dev.servereer.zpeer.common.bridge.ByteBridgeHandler;
import dev.servereer.zpeer.common.proto.Frame;
import dev.servereer.zpeer.common.proto.FrameType;
import dev.servereer.zpeer.common.proto.Frames;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.logging.Logger;

// Backend's idle pool socket lifecycle. Waits for ATTACH. When ATTACH arrives,
// dials the local Minecraft server, then splices the two channels into a raw
// byte bridge.
public final class PoolIdleHandler extends SimpleChannelInboundHandler<Frame> {

    private final ProxyConfig    proxy;
    private final String         tag;
    private final PoolMaintainer maintainer;
    private final Logger         log;
    private boolean attached = false;

    public PoolIdleHandler(ProxyConfig proxy, PoolMaintainer maintainer, Logger log) {
        this.proxy      = proxy;
        this.tag        = proxy.logTag();
        this.maintainer = maintainer;
        this.log        = log;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        if (frame.type != FrameType.ATTACH) {
            log.warning(tag + " unexpected frame on idle pool socket: " + frame.type);
            ctx.close();
            return;
        }
        attached = true;
        maintainer.release(); // socket leaving idle pool — caller refills

        String playerAddr = Frames.attachPlayerAddr(frame);
        String playerName = Frames.attachPlayerName(frame);

        Channel pool = ctx.channel();
        pool.config().setAutoRead(false); // pause until local is ready

        Bootstrap b = new Bootstrap();
        b.group(pool.eventLoop())
         .channel(NioSocketChannel.class)
         .option(ChannelOption.TCP_NODELAY, true)
         .option(ChannelOption.AUTO_READ, false)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override protected void initChannel(SocketChannel ch) {
                 // bridge installed below once both sides are ready
             }
         });
        b.connect(proxy.localHost, proxy.localPort).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warning(tag + " local dial failed for player " + playerName + " ("
                        + playerAddr + "): " + f.cause());
                pool.close();
                return;
            }
            Channel local = f.channel();
            // Same ordering as the proxy side: install bridge first, then
            // remove codec/idle handlers, then resume reads.
            pool.pipeline().addLast("bridge", new ByteBridgeHandler(local));
            pool.pipeline().remove("encoder");
            pool.pipeline().remove("decoder");
            pool.pipeline().remove("idle");
            pool.pipeline().remove(PoolIdleHandler.this);

            local.pipeline().addLast("bridge", new ByteBridgeHandler(pool));
            local.config().setAutoRead(true);
            pool.config().setAutoRead(true);
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // If we never reached attached state, this is a lost idle socket;
        // release its slot so the maintainer can refill.
        if (!attached) maintainer.release();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warning(tag + " idle pool socket exception: " + cause);
        ctx.close();
    }
}

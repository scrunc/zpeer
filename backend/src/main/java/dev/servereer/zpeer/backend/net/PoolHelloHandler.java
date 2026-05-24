package dev.servereer.zpeer.backend.net;

import dev.servereer.zpeer.backend.ProxyConfig;
import dev.servereer.zpeer.common.proto.Frame;
import dev.servereer.zpeer.common.proto.FrameType;
import dev.servereer.zpeer.common.proto.Frames;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.logging.Logger;

public final class PoolHelloHandler extends SimpleChannelInboundHandler<Frame> {

    private final ProxyConfig    proxy;
    private final String         tag;
    private final PoolMaintainer maintainer;
    private final Logger         log;

    public PoolHelloHandler(ProxyConfig proxy, PoolMaintainer maintainer, Logger log) {
        this.proxy      = proxy;
        this.tag        = proxy.logTag();
        this.maintainer = maintainer;
        this.log        = log;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        if (frame.type == FrameType.POOL_HELLO_OK) {
            ctx.pipeline().replace("pool-hello", "pool-idle",
                    new PoolIdleHandler(proxy, maintainer, log));
            // socket is now idle and counted by the maintainer; nothing else to do.
        } else if (frame.type == FrameType.POOL_HELLO_ERR) {
            log.warning(tag + " proxy rejected POOL_HELLO: "
                    + Frames.poolHelloErrReason(frame));
            ctx.close();
        } else {
            log.warning(tag + " unexpected frame during pool handshake: " + frame.type);
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // failure before reaching idle: release the reservation
        maintainer.release();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warning(tag + " pool handshake exception: " + cause);
        ctx.close();
    }
}

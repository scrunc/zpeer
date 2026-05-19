package dev.servereer.zpeer.backend.net;

import dev.servereer.zpeer.backend.BackendConfig;
import dev.servereer.zpeer.common.proto.Frame;
import dev.servereer.zpeer.common.proto.FrameType;
import dev.servereer.zpeer.common.proto.Frames;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.logging.Logger;

public final class PoolHelloHandler extends SimpleChannelInboundHandler<Frame> {

    private final BackendConfig  config;
    private final PoolMaintainer maintainer;
    private final Logger         log;

    public PoolHelloHandler(BackendConfig config, PoolMaintainer maintainer, Logger log) {
        this.config     = config;
        this.maintainer = maintainer;
        this.log        = log;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        if (frame.type == FrameType.POOL_HELLO_OK) {
            ctx.pipeline().replace("pool-hello", "pool-idle",
                    new PoolIdleHandler(config, maintainer, log));
            // socket is now idle and counted by the maintainer; nothing else to do.
        } else if (frame.type == FrameType.POOL_HELLO_ERR) {
            log.warning("[zpeer] proxy rejected POOL_HELLO: "
                    + Frames.poolHelloErrReason(frame));
            ctx.close();
        } else {
            log.warning("[zpeer] unexpected frame during pool handshake: " + frame.type);
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
        log.warning("[zpeer] pool handshake exception: " + cause);
        ctx.close();
    }
}

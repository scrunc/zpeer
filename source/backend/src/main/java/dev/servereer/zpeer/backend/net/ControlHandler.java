package dev.servereer.zpeer.backend.net;

import dev.servereer.zpeer.common.proto.Frame;
import dev.servereer.zpeer.common.proto.Frames;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

import java.util.logging.Logger;

public final class ControlHandler extends SimpleChannelInboundHandler<Frame> {

    private final ControlClient client;
    private final Logger        log;

    public ControlHandler(ControlClient client, Logger log) {
        this.client = client;
        this.log    = log;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        switch (frame.type) {
            case HELLO_OK -> client.onHelloOk(
                    Frames.helloOkServer(frame), Frames.helloOkPoolTarget(frame));
            case HELLO_ERR -> {
                log.severe("[zpeer] proxy rejected HELLO: " + Frames.helloErrReason(frame));
                ctx.close();
            }
            case POOL_TARGET -> client.onPoolTarget(Frames.poolTargetValue(frame));
            case HEARTBEAT -> {} // proxy still alive
            case ERROR -> log.warning("[zpeer] proxy reported error: " + frame.payload);
            default -> {
                log.warning("[zpeer] unexpected frame on control: " + frame.type);
                ctx.close();
            }
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent ise && ise.state() == IdleState.READER_IDLE) {
            log.warning("[zpeer] proxy heartbeat timeout, dropping control");
            ctx.close();
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warning("[zpeer] control channel exception: " + cause);
        ctx.close();
    }
}

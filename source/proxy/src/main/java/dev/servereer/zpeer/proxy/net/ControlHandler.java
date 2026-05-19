package dev.servereer.zpeer.proxy.net;

import dev.servereer.zpeer.common.proto.Frame;
import dev.servereer.zpeer.common.proto.FrameType;
import dev.servereer.zpeer.common.proto.Frames;
import dev.servereer.zpeer.proxy.ZPeerProxy;
import dev.servereer.zpeer.proxy.session.Session;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

public final class ControlHandler extends SimpleChannelInboundHandler<Frame> {

    private final ZPeerProxy plugin;
    private final Session    session;

    public ControlHandler(ZPeerProxy plugin, Session session) {
        this.plugin  = plugin;
        this.session = session;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        switch (frame.type) {
            case HEARTBEAT ->
                    // Echo so the backend's reader-idle clock stays fresh. Without
                    // this the backend sees no inbound traffic for 30s and drops
                    // the control channel as "proxy dead."
                    ctx.writeAndFlush(Frames.heartbeat());
            case ERROR -> plugin.logger().warn("[zpeer] backend reported error on '{}': {}",
                    session.serverName, frame.payload);
            default -> {
                plugin.logger().warn("[zpeer] unexpected frame on control channel of '{}': {}",
                        session.serverName, frame.type);
                ctx.writeAndFlush(Frames.error(400, "unexpected frame: " + frame.type))
                   .addListener(f -> ctx.close());
            }
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent ise && ise.state() == IdleState.READER_IDLE) {
            plugin.logger().warn("[zpeer] heartbeat timeout on '{}', dropping", session.serverName);
            ctx.close();
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        plugin.onSessionLost(session);
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        plugin.logger().warn("[zpeer] control channel exception on '{}': {}",
                session.serverName, cause.toString());
        ctx.close();
    }
}

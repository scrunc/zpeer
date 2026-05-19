package dev.servereer.zpeer.proxy.net;

import dev.servereer.zpeer.common.proto.Frame;
import dev.servereer.zpeer.proxy.ZPeerProxy;
import dev.servereer.zpeer.proxy.session.Session;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

// Pool sockets sitting in the idle queue use this handler. Backend MUST NOT
// send frames on an idle pool socket; any inbound activity is treated as a
// protocol violation and the socket is dropped.
public final class PoolIdleHandler extends SimpleChannelInboundHandler<Frame> {

    private final ZPeerProxy plugin;
    private final Session    session;

    public PoolIdleHandler(ZPeerProxy plugin, Session session) {
        this.plugin  = plugin;
        this.session = session;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        plugin.logger().warn("[zpeer] unexpected frame on idle pool socket of '{}': {}",
                session.serverName, frame.type);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // The session keeps the channel in its idle queue; if the channel
        // dies, removeFromQueue on Session would be nice but ArrayDeque.remove
        // is O(n) and pool sockets are short-lived. The take-path checks
        // isActive() before using.
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        plugin.logger().warn("[zpeer] idle pool socket exception on '{}': {}",
                session.serverName, cause.toString());
        ctx.close();
    }
}

package dev.servereer.zpeer.proxy.net;

import dev.servereer.zpeer.common.ZPeerConstants;
import dev.servereer.zpeer.common.proto.Frame;
import dev.servereer.zpeer.common.proto.FrameType;
import dev.servereer.zpeer.common.proto.Frames;
import dev.servereer.zpeer.proxy.ZPeerProxy;
import dev.servereer.zpeer.proxy.session.Session;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

// First-frame dispatcher. Every inbound connection on the control port starts
// here; based on the first frame we promote the channel to a control or a
// pool socket lifecycle.
public final class ConnectionGate extends SimpleChannelInboundHandler<Frame> {

    private final ZPeerProxy plugin;

    public ConnectionGate(ZPeerProxy plugin) {
        this.plugin = plugin;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        if (frame.type == FrameType.HELLO) {
            handleHello(ctx, frame);
        } else if (frame.type == FrameType.POOL_HELLO) {
            handlePoolHello(ctx, frame);
        } else {
            ctx.writeAndFlush(Frames.error(400, "expected HELLO or POOL_HELLO, got " + frame.type))
               .addListener(f -> ctx.close());
        }
    }

    private void handleHello(ChannelHandlerContext ctx, Frame frame) {
        String token;
        int    protocol;
        try {
            token    = Frames.helloToken(frame);
            protocol = Frames.helloProtocol(frame);
        } catch (RuntimeException ex) {
            reject(ctx, "malformed HELLO");
            return;
        }
        if (protocol != ZPeerConstants.PROTOCOL_VERSION) {
            reject(ctx, "protocol mismatch (proxy=" + ZPeerConstants.PROTOCOL_VERSION
                    + ", backend=" + protocol + ")");
            return;
        }
        String serverName = plugin.config().tokenToServer.get(token);
        if (serverName == null) {
            reject(ctx, "unknown token");
            return;
        }
        Session existing = plugin.sessions().getByServer(serverName);
        if (existing != null) {
            reject(ctx, "server '" + serverName + "' already has an active backend");
            return;
        }

        Session session = new Session(token, serverName, ctx.channel(), plugin.config().poolTarget);
        if (!plugin.sessions().tryRegister(session)) {
            reject(ctx, "race: server '" + serverName + "' just claimed by another backend");
            return;
        }

        ctx.pipeline().replace("gate", "control",
                new ControlHandler(plugin, session));

        ctx.writeAndFlush(Frames.helloOk(serverName, plugin.config().poolTarget));
        plugin.onSessionEstablished(session);
        plugin.logger().info("[zpeer] backend connected: server='{}' from {}",
                serverName, ctx.channel().remoteAddress());
    }

    private void handlePoolHello(ChannelHandlerContext ctx, Frame frame) {
        String token;
        try {
            token = Frames.poolHelloToken(frame);
        } catch (RuntimeException ex) {
            ctx.writeAndFlush(Frames.poolHelloErr("malformed POOL_HELLO"))
               .addListener(f -> ctx.close());
            return;
        }
        String serverName = plugin.config().tokenToServer.get(token);
        if (serverName == null) {
            ctx.writeAndFlush(Frames.poolHelloErr("unknown token"))
               .addListener(f -> ctx.close());
            return;
        }
        Session session = plugin.sessions().getByServer(serverName);
        if (session == null) {
            ctx.writeAndFlush(Frames.poolHelloErr("no active control session for server '"
                    + serverName + "'"))
               .addListener(f -> ctx.close());
            return;
        }
        ctx.pipeline().replace("gate", "pool-idle",
                new PoolIdleHandler(plugin, session));

        ctx.writeAndFlush(Frames.poolHelloOk()).addListener(f -> {
            if (!f.isSuccess()) {
                ctx.close();
                return;
            }
            session.offerIdle(ctx.channel(), plugin.logger());
        });
    }

    private void reject(ChannelHandlerContext ctx, String reason) {
        ctx.writeAndFlush(Frames.helloErr(reason)).addListener(f -> ctx.close());
    }
}

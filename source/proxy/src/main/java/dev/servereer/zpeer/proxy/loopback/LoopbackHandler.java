package dev.servereer.zpeer.proxy.loopback;

import dev.servereer.zpeer.common.ZPeerConstants;
import dev.servereer.zpeer.common.bridge.ByteBridgeHandler;
import dev.servereer.zpeer.common.proto.Frames;
import dev.servereer.zpeer.proxy.ZPeerProxy;
import dev.servereer.zpeer.proxy.session.Session;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// On Velocity dialing the loopback for this session:
//  1. Try to take an idle pool socket; if none, register as waiter with timeout.
//  2. With a pool socket in hand, send ATTACH on it, then splice loopback<->pool.
public final class LoopbackHandler extends ChannelInboundHandlerAdapter {

    private final ZPeerProxy plugin;
    private final Session    session;

    public LoopbackHandler(ZPeerProxy plugin, Session session) {
        this.plugin  = plugin;
        this.session = session;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel loopback = ctx.channel();
        AtomicBoolean handed = new AtomicBoolean(false);

        Consumer<Channel> handoff = pool -> {
            if (!handed.compareAndSet(false, true)) {
                if (pool != null) pool.close();
                return;
            }
            if (pool == null || !pool.isActive()) {
                plugin.logger().warn("[zpeer] no pool socket available for '{}', closing player conn",
                        session.serverName);
                loopback.close();
                return;
            }
            attach(loopback, pool);
        };

        Channel immediate = session.takeOrWait(handoff);
        if (immediate != null) {
            handoff.accept(immediate);
            return;
        }
        // Waiter is registered; arm a timeout.
        loopback.eventLoop().schedule(() -> {
            if (handed.compareAndSet(false, true)) {
                session.removeWaiter(handoff);
                plugin.logger().warn("[zpeer] attach timeout on '{}', closing player conn",
                        session.serverName);
                loopback.close();
            }
        }, ZPeerConstants.ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void attach(Channel loopback, Channel pool) {
        String playerAddr = String.valueOf(loopback.remoteAddress());
        // v1 has no Velocity-side info about player identity; the MC handshake
        // packet that follows is what the backend will use. Player addr is
        // informational only.
        String playerName = "<unknown>";
        if (loopback.remoteAddress() instanceof InetSocketAddress isa) {
            playerAddr = isa.getAddress().getHostAddress() + ":" + isa.getPort();
        }

        pool.writeAndFlush(Frames.attach(playerName, playerAddr)).addListener(f -> {
            if (!f.isSuccess()) {
                plugin.logger().warn("[zpeer] ATTACH write failed on '{}': {}",
                        session.serverName, f.cause());
                loopback.close();
                pool.close();
                return;
            }
            // Order matters. Bridge goes in first (so any leftover decoder
            // accumulator bytes get fired through it), THEN the codec handlers
            // are removed. The encoder removal MUST happen before loopback
            // bytes can write into pool — otherwise raw ByteBufs hit the
            // FrameEncoder and crash.
            pool.pipeline().addLast("bridge", new ByteBridgeHandler(loopback));
            pool.pipeline().remove("encoder");
            pool.pipeline().remove("decoder");
            pool.pipeline().remove("idle");
            pool.pipeline().remove("pool-idle");

            loopback.pipeline().addLast("bridge", new ByteBridgeHandler(pool));
            loopback.pipeline().remove(LoopbackHandler.this);
            loopback.config().setAutoRead(true);
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        plugin.logger().warn("[zpeer] loopback exception on '{}': {}",
                session.serverName, cause.toString());
        ctx.close();
    }
}

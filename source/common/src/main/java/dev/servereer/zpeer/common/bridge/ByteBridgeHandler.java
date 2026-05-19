package dev.servereer.zpeer.common.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

// Splices bytes from this channel into the paired channel. Drop-in handler:
// once installed (typically after stripping the frame codec), every byte read
// is written to the peer with no buffering beyond what Netty itself owns.
// TCP backpressure flows end-to-end via channel auto-read toggling.
public final class ByteBridgeHandler extends ChannelInboundHandlerAdapter {

    private final Channel peer;

    public ByteBridgeHandler(Channel peer) {
        this.peer = peer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (!peer.isActive()) {
            buf.release();
            ctx.close();
            return;
        }
        peer.writeAndFlush(buf).addListener(f -> {
            if (!f.isSuccess()) {
                ctx.close();
                return;
            }
            // Apply backpressure: stop reading if peer's write buffer is over the high
            // watermark; resume when it drains under the low watermark (handled in
            // peer's channelWritabilityChanged via the matching handler).
            if (!peer.isWritable()) {
                ctx.channel().config().setAutoRead(false);
            }
        });
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable() && peer.isActive()) {
            peer.config().setAutoRead(true);
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (peer.isActive()) peer.close();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
        if (peer.isActive()) peer.close();
    }
}

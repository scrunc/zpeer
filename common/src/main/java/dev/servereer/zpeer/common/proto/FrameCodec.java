package dev.servereer.zpeer.common.proto;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class FrameCodec {

    public static final int MAX_PAYLOAD = 64 * 1024;
    private static final Gson GSON = new Gson();

    private FrameCodec() {}

    public static class Decoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (in.readableBytes() < 4) return;
            in.markReaderIndex();
            int typeCode = in.readUnsignedByte();
            int len      = in.readUnsignedMedium();
            if (len > MAX_PAYLOAD) {
                throw new CorruptedFrameException("frame too large: " + len);
            }
            if (in.readableBytes() < len) {
                in.resetReaderIndex();
                return;
            }
            FrameType type = FrameType.byCode(typeCode);
            if (type == null) {
                throw new CorruptedFrameException("unknown frame type: 0x"
                        + Integer.toHexString(typeCode));
            }
            JsonObject json;
            if (len == 0) {
                json = new JsonObject();
            } else {
                byte[] payload = new byte[len];
                in.readBytes(payload);
                json = GSON.fromJson(new String(payload, StandardCharsets.UTF_8),
                        JsonObject.class);
            }
            out.add(new Frame(type, json));
        }
    }

    public static class Encoder extends MessageToByteEncoder<Frame> {
        @Override
        protected void encode(ChannelHandlerContext ctx, Frame msg, ByteBuf out) {
            byte[] bytes = GSON.toJson(msg.payload).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_PAYLOAD) {
                throw new IllegalArgumentException("payload too large: " + bytes.length);
            }
            out.writeByte(msg.type.code);
            out.writeMedium(bytes.length);
            out.writeBytes(bytes);
        }
    }
}

package dev.servereer.zpeer.common.proto;

import com.google.gson.JsonObject;

public final class Frame {
    public final FrameType type;
    public final JsonObject payload;

    public Frame(FrameType type, JsonObject payload) {
        this.type = type;
        this.payload = payload == null ? new JsonObject() : payload;
    }

    public static Frame of(FrameType type) {
        return new Frame(type, new JsonObject());
    }

    @Override
    public String toString() {
        return "Frame{" + type + " " + payload + "}";
    }
}

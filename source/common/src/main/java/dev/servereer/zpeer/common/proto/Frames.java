package dev.servereer.zpeer.common.proto;

import com.google.gson.JsonObject;

// Typed constructors and accessors for known frames. Keeps payload-shape
// knowledge in one place so proxy/backend handlers don't litter string keys.
public final class Frames {

    private Frames() {}

    // ---------- control channel ----------

    public static Frame hello(String token, int protocolVersion) {
        JsonObject o = new JsonObject();
        o.addProperty("token", token);
        o.addProperty("protocol", protocolVersion);
        return new Frame(FrameType.HELLO, o);
    }

    public static String helloToken(Frame f)     { return f.payload.get("token").getAsString(); }
    public static int    helloProtocol(Frame f)  { return f.payload.get("protocol").getAsInt(); }

    public static Frame helloOk(String serverName, int poolTarget) {
        JsonObject o = new JsonObject();
        o.addProperty("server", serverName);
        o.addProperty("pool_target", poolTarget);
        return new Frame(FrameType.HELLO_OK, o);
    }

    public static String helloOkServer(Frame f)     { return f.payload.get("server").getAsString(); }
    public static int    helloOkPoolTarget(Frame f) { return f.payload.get("pool_target").getAsInt(); }

    public static Frame helloErr(String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("reason", reason);
        return new Frame(FrameType.HELLO_ERR, o);
    }

    public static String helloErrReason(Frame f) { return f.payload.get("reason").getAsString(); }

    public static Frame poolTarget(int target) {
        JsonObject o = new JsonObject();
        o.addProperty("target", target);
        return new Frame(FrameType.POOL_TARGET, o);
    }

    public static int poolTargetValue(Frame f) { return f.payload.get("target").getAsInt(); }

    public static Frame heartbeat() {
        return Frame.of(FrameType.HEARTBEAT);
    }

    // ---------- pool socket ----------

    public static Frame poolHello(String token) {
        JsonObject o = new JsonObject();
        o.addProperty("token", token);
        return new Frame(FrameType.POOL_HELLO, o);
    }

    public static String poolHelloToken(Frame f) { return f.payload.get("token").getAsString(); }

    public static Frame poolHelloOk() {
        return Frame.of(FrameType.POOL_HELLO_OK);
    }

    public static Frame poolHelloErr(String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("reason", reason);
        return new Frame(FrameType.POOL_HELLO_ERR, o);
    }

    public static String poolHelloErrReason(Frame f) { return f.payload.get("reason").getAsString(); }

    public static Frame attach(String playerName, String playerAddr) {
        JsonObject o = new JsonObject();
        o.addProperty("player", playerName);
        o.addProperty("addr", playerAddr);
        return new Frame(FrameType.ATTACH, o);
    }

    public static String attachPlayerName(Frame f) { return f.payload.get("player").getAsString(); }
    public static String attachPlayerAddr(Frame f) { return f.payload.get("addr").getAsString(); }

    // ---------- error ----------

    public static Frame error(int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("code", code);
        o.addProperty("message", message);
        return new Frame(FrameType.ERROR, o);
    }
}

# ZPeer

Reverse-tunnel plugin pair for Minecraft Velocity ↔ Paper. Backends without a public IP dial the proxy over TLS with a token; the proxy maintains a pre-warmed pool of TLS-wrapped TCP connections; players ride those pool sockets through a loopback bridge on the proxy side.

## Layout

```
source/
├── pom.xml          parent (Maven multi-module)
├── common/          wire protocol + TLS helpers (shared)
├── proxy/           Velocity plugin
└── backend/         Paper plugin
```

## Build

```bash
cd source
mvn package -DskipTests
```

Outputs:
- `proxy/target/zpeer-proxy-0.1.0.jar` — drop into Velocity `plugins/`
- `backend/target/zpeer-backend-0.1.0.jar` — drop into Paper `plugins/`

## First-time setup

**1. Install the proxy plugin.** On first start the plugin generates a self-signed RSA-2048 cert + key, persists them to `plugins/zpeer/data/proxy.{crt,key}`, and logs the SHA-256 fingerprint:

```
[zpeer] TLS cert ready. fingerprint = sha256:abc123...
[zpeer]   ^ paste this into each backend's `proxy.cert_fingerprint`
```

**2. Configure the proxy** (`plugins/zpeer/config.yml`):
```yaml
listen: { host: 0.0.0.0, port: 25577 }
pool-target: 8
tls:
  cert-file: data/proxy.crt
  key-file:  data/proxy.key
tokens:
  "long-random-token-1": creative
  "long-random-token-2": survival
```

Generate tokens with `openssl rand -hex 32`.

**3. Configure each backend** (`plugins/ZPeerBackend/config.yml`):
```yaml
proxy:
  host: proxy.example.com
  port: 25577
  cert_fingerprint: "sha256:abc123..."   # from the proxy's startup log
token: "long-random-token-1"
local: { host: 127.0.0.1, port: 25565 }
```

The backend refuses to start without a configured `cert_fingerprint`.

## Security model

**TLS 1.3 only, mandatory.** No plaintext fallback. The connection between proxy and backend is encrypted with AEAD ciphers and forward-secret ECDHE key exchange.

**Pinned-cert trust.** The backend has a hand-configured SHA-256 fingerprint and rejects any cert that doesn't match exactly. No CA chain involvement, no hostname validation — same trust model as SSH host keys. A MITM with a forged cert fails the handshake before any byte of the token or player data is sent.

**Token = capability.** Anyone holding the token can dial in as that backend (after passing the cert pin check). Treat the token like a server password: keep it in `.secrets/`, rotate by regenerating + updating both ends' configs + restarting.

**What this protects against:**

| Threat | Defended? |
|---|---|
| Passive eavesdrop of player traffic | ✓ (TLS encryption) |
| MITM with self-signed forged cert | ✓ (pin fails, handshake aborts) |
| MITM via stolen CA cert | ✓ (we don't trust CAs, only the pin) |
| Token leaked via wire sniff | ✓ (handshake completes before token is sent) |
| Token leaked via filesystem | ✗ (same exposure as any other server password) |
| Compromised proxy host | ✗ (out of scope — full machine takeover) |

## How it works

```
1. Backend boots:
   backend ─── TLS 1.3 ──▶ proxy:port
   (handshake: backend pins proxy's SHA-256 fingerprint; aborts on mismatch)
              HELLO {token, protocol=2}
              ◀── HELLO_OK {server="survival", pool_target=8}

2. Backend opens N pool sockets, each also TLS:
   backend ─── TLS 1.3 ──▶ proxy:port   ×8
              POOL_HELLO {token}
              ◀── POOL_HELLO_OK

3. Player joins:
   player ─── MC ──▶ proxy:player-port (Velocity)
   Velocity routes to "survival" → dials 127.0.0.1:<loopback>
   zpeer loopback handler grabs next idle pool socket:
       sends ATTACH on it, strips frame codec, installs byte bridge
       loopback ⇄ pool socket = encrypted bridge from this point on

4. Backend pool socket reads ATTACH:
       dials 127.0.0.1:25565 (local Paper)
       strips frame codec, installs byte bridge
       pool socket ⇄ local Paper = local bridge

5. End-to-end:
   player ⇄ proxy ⇄ [internet, TLS 1.3] ⇄ backend ⇄ local Paper
                    └─ Velocity's own modern-forwarding HMAC also applies
                       inside the TLS tunnel — defense in depth
```

## Reliability properties

- **No head-of-line blocking between players** — one TCP per player session.
- **No handshake latency on join** — TLS handshake done while the pool socket sat idle; ATTACH is a single small frame.
- **No plugin-induced drops** — TCP guarantees delivery; bridge is `read → write` with no extra buffering.
- **Control flap doesn't kick active players** — bridged pool sockets are independent of the control channel.
- **Backpressure** — automatic via TCP flow control + Netty `autoRead` toggling.

What's NOT guaranteed: anything beyond the plugin. Network loss between proxy and backend is real loss for the player. Long GC pauses stall bridging — use ZGC or Shenandoah on both sides. TLS adds ~3-5 % CPU overhead per byte; negligible on modern hardware with AES-NI.

## Wire protocol

Inside the TLS stream, frames use:

```
[type: u8][len: u24 big-endian][JSON payload]    max 64 KB payload
```

Control frames: `HELLO`, `HELLO_OK`, `HELLO_ERR`, `POOL_TARGET`, `HEARTBEAT`, `ERROR`.
Pool-socket frames: `POOL_HELLO`, `POOL_HELLO_OK`, `POOL_HELLO_ERR`, `ATTACH`.

After `ATTACH` is written/read, the pool socket flips to raw byte-bridge mode — no more frames on that socket. The SslHandler stays in the pipeline so bridged bytes are still encrypted on the wire.

See [source/common/src/main/java/dev/servereer/zpeer/common/](source/common/src/main/java/dev/servereer/zpeer/common/) for the canonical definitions (`proto/` for frames, `tls/` for the TLS plumbing).

## Cert rotation

To rotate the proxy cert:
1. Stop the proxy.
2. Delete `plugins/zpeer/data/proxy.{crt,key}`.
3. Start the proxy. A new cert is generated; note the new fingerprint in the log.
4. Update every backend's `proxy.cert_fingerprint` and restart them.

## Todos

- Bedrock-native (UDP/RakNet) — currently only Bedrock-through-Geyser works
- Multi-backend failover for a single server name
- Capability negotiation beyond protocol version
- `/zpeer` admin commands (status, reload, pool stats)

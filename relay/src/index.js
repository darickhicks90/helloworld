// 5IY jam relay.
//
// POST /code              → { code, key }   a fresh one-time code (8 characters) and a secret for whoever asked
// GET  /ws?code=…&key=…   → WebSocket for the code's creator
// GET  /ws?code=…         → WebSocket for the one person the code was shared with
//
// A room pairs exactly two phones. Once the second phone is in, the code is spent: nobody else
// can use it. A code nobody joins expires after 10 minutes. The relay keeps nothing: it passes
// each message from one phone to the other (the leader's sound, and small control messages such
// as "hey wait" or a request to swap roles) and forgets the room when either phone leaves.

const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O or 1/I mix-ups
const CODE_LEN = 8;
const EXPIRE_MS = 10 * 60 * 1000;
const MAX_MESSAGE = 64 * 1024;

function randomString(n, alphabet) {
  const bytes = new Uint8Array(n);
  crypto.getRandomValues(bytes);
  let s = "";
  for (const b of bytes) s += alphabet[b % alphabet.length];
  return s;
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/code" && request.method === "POST") {
      const code = randomString(CODE_LEN, ALPHABET);
      const key = randomString(32, ALPHABET);
      const room = env.ROOMS.get(env.ROOMS.idFromName(code));
      const r = await room.fetch("https://room/init", { method: "POST", body: JSON.stringify({ key }) });
      if (!r.ok) return json({ error: "try again" }, 503);
      return json({ code, key });
    }
    if (url.pathname === "/ws") {
      const code = (url.searchParams.get("code") || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
      if (code.length !== CODE_LEN) return json({ error: "bad code" }, 400);
      if (request.headers.get("Upgrade") !== "websocket") return json({ error: "websocket only" }, 426);
      const room = env.ROOMS.get(env.ROOMS.idFromName(code));
      return room.fetch(request);
    }
    return json({ ok: true, app: "5IY jam relay" });
  },
};

export class Room {
  constructor(state) {
    this.state = state;
    this.key = null;      // the creator's secret
    this.created = 0;
    this.spent = false;   // true once the second phone has joined
    this.host = null;     // the creator's socket
    this.guest = null;    // the other phone's socket
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (this.key === null) {
      // after a restart, pick the room back up (it only ever holds the secret, its age, and whether it's spent)
      const saved = await this.state.storage.get(["key", "created", "spent"]);
      this.key = saved.get("key") || null; this.created = saved.get("created") || 0; this.spent = !!saved.get("spent");
    }
    if (url.pathname === "/init") {
      if (this.key) return new Response("taken", { status: 409 });
      const { key } = await request.json();
      this.key = key; this.created = Date.now();
      await this.state.storage.put({ key, created: this.created, spent: false });
      return new Response("ok");
    }
    // a WebSocket for one side of the room
    if (!this.key || this.spent || Date.now() - this.created > EXPIRE_MS) return new Response("This code has expired or been used", { status: 410 });
    const isHost = url.searchParams.get("key") === this.key;
    if (isHost ? this.host : this.guest) return new Response("Already connected", { status: 409 });
    if (!isHost && !this.host) return new Response("The other phone isn't connected yet", { status: 425 });

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    if (isHost) this.host = server; else { this.guest = server; this.spent = true; this.state.storage.put("spent", true); }

    server.addEventListener("message", (ev) => {
      const other = server === this.host ? this.guest : this.host;
      const size = typeof ev.data === "string" ? ev.data.length : ev.data.byteLength;
      if (!other || size > MAX_MESSAGE) return;
      try { other.send(ev.data); } catch (_) {}
    });
    const gone = () => {
      const other = server === this.host ? this.guest : this.host;
      try { other && other.send(JSON.stringify({ t: "bye" })); other && other.close(1000, "partner left"); } catch (_) {}
      this.host = null; this.guest = null; this.spent = true; // the room is over
      this.state.storage.put("spent", true);
    };
    server.addEventListener("close", gone);
    server.addEventListener("error", gone);
    if (!isHost) {
      try { this.host.send(JSON.stringify({ t: "joined" })); } catch (_) {}
      server.send(JSON.stringify({ t: "joined" }));
    }
    return new Response(null, { status: 101, webSocket: client });
  }
}

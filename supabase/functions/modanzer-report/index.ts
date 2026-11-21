// ModAnzer screenshare reports -> the PrimeTiers bot's MongoDB.
// The script sends {report}. This checks it, trims it and saves it to the
// `modanzer_reports` collection with status "new". The PrimeTiers Discord bot
// picks up new reports and posts them in its mod-check channel.
// Secrets (set in this Supabase project):
//   MODANZER_MONGODB_URI  connection string. Use a database user that can only
//                         insert into modanzer_reports, not the bot's main login.
//   MODANZER_MONGODB_DB   database name, defaults to "primetiers"
// The connection string never leaves this server.

import { MongoClient } from "npm:mongodb@6.10.0";

const MAX_BODY = 1_000_000;
const LIMIT_PER_MINUTE = 6;
const recent = new Map<string, { n: number; t: number }>();
let client: MongoClient | null = null;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const str = (v: unknown, max: number) => (typeof v === "string" ? v.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, "").slice(0, max) : "");
const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? Math.max(0, Math.min(1_000_000, Math.floor(v))) : 0);
const arr = (v: unknown, max: number): unknown[] => (Array.isArray(v) ? v.slice(0, max) : []);
const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === "object" && !Array.isArray(v) ? v as Record<string, unknown> : null);

function alerts(v: unknown) {
  return arr(v, 500).map((a) => {
    const o = obj(a) ?? {};
    return { Time: str(o.Time, 10), Level: o.Level === "Red" ? "Red" : "Yellow", Text: str(o.Text, 400) };
  });
}

// Keep only the fields ModAnzer sends, with sane sizes.
export function sanitize(raw: unknown) {
  const r = obj(raw);
  if (!r) return null;
  const m = obj(r.mods), s = obj(r.system), i = obj(r.injection);
  return {
    tool: "ModAnzer",
    mode: str(r.mode, 40),
    finished: str(r.finished, 40),
    discordId: typeof r.discordId === "string" && /^\d{17,20}$/.test(r.discordId) ? r.discordId : "",
    accounts: arr(r.accounts, 20).map((a) => str(a, 16)).filter(Boolean),
    mods: m && {
      Folders: arr(m.Folders, 50).map((f) => str(f, 120)),
      Verified: num(m.Verified), CurseForgeApp: num(m.CurseForgeApp), Unknown: num(m.Unknown), Cheat: num(m.Cheat),
      Cheats: arr(m.Cheats, 300).map((c) => { const o = obj(c) ?? {}; return { Name: str(o.Name, 120), Where: str(o.Where, 120), Strings: str(o.Strings, 200) }; }),
      Unknowns: arr(m.Unknowns, 500).map((u) => { const o = obj(u) ?? {}; return { Name: str(o.Name, 120), Where: str(o.Where, 120) }; }),
      // Every mod that was scanned, with its result.
      All: arr(m.All, 3000).map((x) => {
        const o = obj(x) ?? {};
        const st = typeof o.Status === "string" && ["OK", "CF", "UNKNOWN", "CHECK", "CHEAT"].includes(o.Status) ? o.Status : "UNKNOWN";
        return { Status: st, Name: str(o.Name, 120), Title: str(o.Title, 200), Where: str(o.Where, 120) };
      }),
    },
    system: s && { Admin: s.Admin === true, Alerts: alerts(s.Alerts) },
    injection: i && { Admin: i.Admin === true, Uptime: str(i.Uptime, 30), GameFolder: str(i.GameFolder, 160), Alerts: alerts(i.Alerts) },
  };
}

async function shortHash(text: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(d), (b) => b.toString(16).padStart(2, "0")).join("").slice(0, 16);
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  const uri = Deno.env.get("MODANZER_MONGODB_URI");
  if (!uri) return json({ error: "reports are not set up on the server yet" }, 503);

  const ip = (req.headers.get("x-forwarded-for") ?? "?").split(",")[0].trim();
  const now = Date.now();
  const r0 = recent.get(ip);
  if (r0 && now - r0.t < 60_000) {
    if (++r0.n > LIMIT_PER_MINUTE) return json({ error: "too many reports, try again in a minute" }, 429);
  } else {
    if (recent.size > 5000) recent.clear();
    recent.set(ip, { n: 1, t: now });
  }

  const raw = await req.text();
  if (raw.length > MAX_BODY) return json({ error: "report too large" }, 413);
  let body: { report?: unknown };
  try {
    body = JSON.parse(raw);
  } catch {
    return json({ error: "body must be JSON" }, 400);
  }
  const report = sanitize(body.report);
  if (!report || (!report.mods && !report.system && !report.injection)) return json({ error: "missing report" }, 400);

  try {
    if (!client) {
      client = new MongoClient(uri, { maxPoolSize: 2, serverSelectionTimeoutMS: 8000 });
      await client.connect();
    }
    const doc = {
      ...report,
      status: "new",
      receivedAt: new Date(),
      // groups reports from the same sender without storing the IP address
      senderHash: await shortHash(ip + uri),
    };
    const res = await client.db(Deno.env.get("MODANZER_MONGODB_DB") ?? "primetiers").collection("modanzer_reports").insertOne(doc);
    return json({ ok: true, id: String(res.insertedId) });
  } catch (err) {
    client = null;
    console.error("insert failed:", err instanceof Error ? err.message : err);
    return json({ error: "the report server could not save it - try again" }, 502);
  }
});

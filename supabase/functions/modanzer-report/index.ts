// ModAnzer screenshare reports -> a Discord channel.
// The script sends {code, report}. This checks the screenshare code, then posts a
// summary embed plus the full report as a text file to the mod-check channel.
// Secrets (set in the Supabase project):
//   MODCHECK_WEBHOOK_URL  Discord webhook of the mod-check channel
//   MODCHECK_CODES        screenshare code(s) staff give players, comma-separated
// The webhook never leaves this server, and reports can't ping anyone.

const MAX_BODY = 300_000;
const LIMIT_PER_MINUTE = 6;
const recent = new Map<string, { n: number; t: number }>();

type Alert = { Time?: string; Level?: string; Text?: string };
type Report = {
  mode?: string;
  finished?: string;
  accounts?: string[];
  mods?: {
    Folders?: string[];
    Verified?: number;
    CurseForgeApp?: number;
    Unknown?: number;
    Cheat?: number;
    Cheats?: { Name?: string; Where?: string; Strings?: string }[];
    Unknowns?: { Name?: string; Where?: string }[];
  } | null;
  system?: { Admin?: boolean; Alerts?: Alert[] } | null;
  injection?: { Admin?: boolean; Uptime?: string; GameFolder?: string; Alerts?: Alert[] } | null;
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

// No pings, no runaway length.
function clean(v: unknown, max = 300): string {
  return String(v ?? "").replace(/@/g, "@\u200b").replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, "").slice(0, max);
}

function cap(lines: string[], max = 1000): string {
  let out = "";
  for (let i = 0; i < lines.length; i++) {
    const next = (out ? out + "\n" : "") + lines[i];
    if (next.length > max - 20) return out + `\n...and ${lines.length - i} more`;
    out = next;
  }
  return out || "-";
}

function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

function alertsOf(x: { Alerts?: Alert[] } | null | undefined): Alert[] {
  return Array.isArray(x?.Alerts) ? x!.Alerts!.slice(0, 500) : [];
}

function buildText(r: Report): string {
  const L: string[] = [];
  L.push(`ModAnzer report - ${clean(r.mode, 40)}`);
  L.push(`Finished: ${clean(r.finished, 40)}`);
  L.push(`Accounts: ${(r.accounts ?? []).map((a) => clean(a, 20)).join(", ") || "none found"}`);
  if (r.mods) {
    const m = r.mods;
    L.push("", "== Mod Analyzer ==");
    L.push(`Folders: ${(m.Folders ?? []).map((f) => clean(f, 80)).join("; ")}`);
    L.push(`Verified ${m.Verified ?? 0}, CurseForge app ${m.CurseForgeApp ?? 0}, Unknown ${m.Unknown ?? 0}, Cheats ${m.Cheat ?? 0}`);
    if (m.Cheats?.length) {
      L.push("Cheats:");
      for (const c of m.Cheats.slice(0, 300)) L.push(`  [${clean(c.Where, 80)}] ${clean(c.Name, 120)} - ${clean(c.Strings, 200)}`);
    }
    if (m.Unknowns?.length) {
      L.push("Unknown mods:");
      for (const u of m.Unknowns.slice(0, 500)) L.push(`  [${clean(u.Where, 80)}] ${clean(u.Name, 120)}`);
    }
  }
  for (const [title, sec] of [["System Check", r.system], ["Injection Checker", r.injection]] as const) {
    if (!sec) continue;
    L.push("", `== ${title} == (administrator: ${sec.Admin ? "yes" : "no"})`);
    if (title === "Injection Checker" && r.injection) {
      L.push(`Game: ${clean(r.injection.GameFolder, 120)} ${clean(r.injection.Uptime, 30)}`);
    }
    const a = alertsOf(sec);
    if (!a.length) L.push("  Nothing suspicious found.");
    for (const x of a) L.push(`  [${clean(x.Time, 10)}] ${x.Level === "Red" ? "SUSPICIOUS" : "worth a look"}: ${clean(x.Text, 400)}`);
  }
  return L.join("\n");
}

function buildEmbed(r: Report) {
  const fields: { name: string; value: string; inline?: boolean }[] = [];
  let red = 0, yellow = 0;
  if (r.mods) {
    const m = r.mods;
    red += m.Cheat ?? 0;
    fields.push({
      name: "Mod Analyzer",
      value: `Verified **${m.Verified ?? 0}** · CF app **${m.CurseForgeApp ?? 0}** · Unknown **${m.Unknown ?? 0}** · Cheats **${m.Cheat ?? 0}**`,
    });
    if (m.Cheats?.length) {
      fields.push({ name: "Cheats found", value: cap(m.Cheats.map((c) => `🔴 ${clean(c.Name, 80)} - ${clean(c.Strings, 80)}\n  ↳ ${clean(c.Where, 60)}`)) });
    }
  }
  for (const [title, sec] of [["System Check", r.system], ["Injection Checker", r.injection]] as const) {
    if (!sec) continue;
    const a = alertsOf(sec);
    const rs = a.filter((x) => x.Level === "Red"), ys = a.filter((x) => x.Level !== "Red");
    red += rs.length;
    yellow += ys.length;
    const head = `Suspicious **${rs.length}** · Worth a look **${ys.length}**${sec.Admin ? "" : " · *not admin*"}`;
    const lines = [...rs.map((x) => `🔴 ${clean(x.Text, 160)}`), ...ys.map((x) => `🟡 ${clean(x.Text, 160)}`)];
    fields.push({ name: title, value: cap([head, ...lines]) });
  }
  const player = (r.accounts ?? []).map((a) => clean(a, 20)).join(", ");
  return {
    title: `ModAnzer report${player ? `: ${player}` : ""}`.slice(0, 250),
    description: `**${clean(r.mode, 40)}** · finished ${clean(r.finished, 40)}`,
    color: red > 0 ? 0xef5350 : yellow > 0 ? 0xffb74d : 0x66bb6a,
    fields: fields.slice(0, 25),
    footer: { text: "Leads, not proof - full report attached" },
  };
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const webhook = Deno.env.get("MODCHECK_WEBHOOK_URL") ?? "";
  const codes = (Deno.env.get("MODCHECK_CODES") ?? "").split(",").map((c) => c.trim()).filter(Boolean);
  if (!/^https:\/\/(discord|discordapp)\.com\/api\/webhooks\//.test(webhook) || codes.length === 0) {
    return json({ error: "reports are not set up on the server yet" }, 503);
  }

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
  let body: { code?: unknown; report?: Report };
  try {
    body = JSON.parse(raw);
  } catch {
    return json({ error: "body must be JSON" }, 400);
  }
  const code = typeof body.code === "string" ? body.code.trim() : "";
  if (!code) return json({ error: "missing screenshare code" }, 400);
  if (!codes.some((c) => safeEqual(c, code))) return json({ error: "wrong screenshare code" }, 403);
  const report = body.report;
  if (!report || typeof report !== "object") return json({ error: "missing report" }, 400);

  const form = new FormData();
  form.append("payload_json", JSON.stringify({
    username: "ModAnzer",
    embeds: [buildEmbed(report)],
    allowed_mentions: { parse: [] },
  }));
  const stamp = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
  form.append("files[0]", new Blob([buildText(report)], { type: "text/plain" }), `modanzer-${stamp}.txt`);

  const res = await fetch(`${webhook}?wait=true`, { method: "POST", body: form });
  if (!res.ok) return json({ error: `Discord returned HTTP ${res.status}` }, 502);
  return json({ ok: true });
});

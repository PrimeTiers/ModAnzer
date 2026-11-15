// ModAnzer CurseForge lookup.
// Keeps the CurseForge API key on the server: the script/app sends CurseForge file
// fingerprints, this adds the key, asks CurseForge, and returns only the matches.
// It can only do fingerprint lookups, and it rate-limits each IP address.
// The key is read from the CURSEFORGE_API_KEY secret in the Supabase project.

const CF = "https://api.curseforge.com/v1";
const MAX_FINGERPRINTS = 1000;
const LIMIT_PER_MINUTE = 30;
const recent = new Map<string, { n: number; t: number }>();

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const key = Deno.env.get("CURSEFORGE_API_KEY");
  if (!key) return json({ error: "CurseForge key not configured" }, 503);

  // simple per-IP rate limit
  const ip = (req.headers.get("x-forwarded-for") ?? "?").split(",")[0].trim();
  const now = Date.now();
  const r = recent.get(ip);
  if (r && now - r.t < 60_000) {
    if (++r.n > LIMIT_PER_MINUTE) return json({ error: "rate limited, try again in a minute" }, 429);
  } else {
    if (recent.size > 5000) recent.clear();
    recent.set(ip, { n: 1, t: now });
  }

  let body: { fingerprints?: unknown };
  try {
    body = await req.json();
  } catch {
    return json({ error: "body must be JSON" }, 400);
  }
  const fps = body?.fingerprints;
  if (
    !Array.isArray(fps) || fps.length === 0 || fps.length > MAX_FINGERPRINTS ||
    !fps.every((x) => Number.isInteger(x) && x >= 0 && x <= 4294967295)
  ) {
    return json({ error: `fingerprints must be 1-${MAX_FINGERPRINTS} whole numbers (uint32)` }, 400);
  }

  const headers = { "x-api-key": key, "Content-Type": "application/json", "Accept": "application/json" };
  const res = await fetch(`${CF}/fingerprints/432`, {
    method: "POST",
    headers,
    body: JSON.stringify({ fingerprints: [...new Set(fps as number[])] }),
  });
  if (!res.ok) return json({ error: `CurseForge returned HTTP ${res.status}` }, 502);

  const exact = (await res.json())?.data?.exactMatches ?? [];
  const matches: Record<string, { modId: number; fileName: string; name: string | null }> = {};
  const modIds = new Set<number>();
  for (const m of exact) {
    const f = m?.file;
    if (!f || typeof f.fileFingerprint !== "number") continue;
    matches[String(f.fileFingerprint)] = { modId: f.modId, fileName: f.fileName, name: null };
    modIds.add(f.modId);
  }

  if (modIds.size > 0) {
    const res2 = await fetch(`${CF}/mods`, {
      method: "POST",
      headers,
      body: JSON.stringify({ modIds: [...modIds] }),
    });
    if (res2.ok) {
      const names = new Map<number, string>(
        ((await res2.json())?.data ?? []).map((x: { id: number; name: string }) => [x.id, x.name]),
      );
      for (const k of Object.keys(matches)) matches[k].name = names.get(matches[k].modId) ?? null;
    }
  }

  return json({ matches });
});

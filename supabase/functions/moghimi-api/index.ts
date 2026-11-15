import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const PROJECT_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SECRET = (() => {
  try { return JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}").default ?? Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""; }
  catch { return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""; }
})();
const PUBLIC = (() => {
  try { return JSON.parse(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS") ?? "{}").default ?? Deno.env.get("SUPABASE_ANON_KEY") ?? ""; }
  catch { return Deno.env.get("SUPABASE_ANON_KEY") ?? ""; }
})();
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
});
const hex = (b: Uint8Array) => Array.from(b, x => x.toString(16).padStart(2,"0")).join("");
async function hash(s: string) {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s))));
}
function headers() {
  const h: Record<string,string> = { apikey: SECRET, "content-type": "application/json" };
  if (SECRET.startsWith("eyJ")) h.authorization = "Bearer " + SECRET;
  return h;
}
async function rpc(name: string, args: Record<string,unknown>) {
  const r = await fetch(PROJECT_URL + "/rest/v1/rpc/" + name, {
    method:"POST", headers:headers(), body:JSON.stringify(args)
  });
  const t = await r.text();
  if (!r.ok) throw new Error("rpc_" + name + "_" + r.status);
  return t ? JSON.parse(t) : null;
}
function uuid(v: unknown): v is string {
  return typeof v === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v);
}
async function auth(b: Record<string,unknown>) {
  const deviceHash = String(b.device_hash ?? "");
  const token = String(b.device_token ?? "");
  if (!/^[0-9a-f]{64}$/.test(deviceHash) || token.length < 32 || token.length > 200)
    return { ok:false, code:"invalid_device_credentials" };
  const r = await rpc("moghimi_auth_device", { p_device_hash:deviceHash, p_token_hash:await hash(token) });
  if (!r?.ok || !uuid(r.device_id)) return { ok:false, code:r?.code ?? "device_auth_failed" };
  return { ok:true, device_id:r.device_id as string, username:String(r.username ?? "") };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null,{status:204});
  if (req.method !== "POST") return reply({ok:false,code:"method_not_allowed"},405);
  if (!PROJECT_URL || !SECRET || !PUBLIC || req.headers.get("apikey") !== PUBLIC)
    return reply({ok:false,code:"unauthorized_client"},401);
  try {
    const b = await req.json() as Record<string,unknown>;
    const action = String(b.action ?? "");
    if (action === "recover") {
      // Android ID is stable for the same app signing key/user/device across
      // ordinary uninstall/reinstall. Rotate the lost local token and restore
      // the only existing server-side profile for this device hash.
      const deviceHash = String(b.device_hash ?? "");
      const token = String(b.device_token ?? "");
      if (!/^[0-9a-f]{64}$/.test(deviceHash) || token.length < 32 || token.length > 200)
        return reply({ok:false,code:"invalid_device_credentials"},400);
      const r = await rpc("moghimi_recover_device", {
        p_device_hash:deviceHash,
        p_token_hash:await hash(token)
      });
      return reply(r, r?.ok ? 200 : (r?.code === "device_not_registered" ? 404 : 409));
    }
    if (action === "register") {
      const deviceHash = String(b.device_hash ?? "");
      const token = String(b.device_token ?? "");
      const username = String(b.username ?? "").trim().replace(/^@/,"");
      const phone = b.phone == null || String(b.phone).trim() === "" ? null : String(b.phone).trim();
      if (!/^[0-9a-f]{64}$/.test(deviceHash) || token.length < 32 || token.length > 200 ||
          !/^[A-Za-z0-9_]{5,32}$/.test(username) || (phone !== null && !/^09[0-9]{9}$/.test(phone)))
        return reply({ok:false,code:"invalid_profile"},400);
      const r = await rpc("moghimi_register_device", {
        p_device_hash:deviceHash, p_token_hash:await hash(token), p_username:username, p_phone:phone
      });
      return reply(r,r?.ok ? 200 : 409);
    }
    const device = await auth(b);
    if (!device.ok) return reply(device,401);

    if (action === "status") {
      return reply(await rpc("moghimi_device_status",{p_device_id:device.device_id}));
    }
    if (action === "usage") {
      const sessionId=b.session_id, seq=Number(b.sequence), rx=Number(b.rx_total), tx=Number(b.tx_total);
      if (!uuid(sessionId) || !Number.isSafeInteger(seq) || seq<1 ||
          !Number.isSafeInteger(rx) || rx<0 || !Number.isSafeInteger(tx) || tx<0)
        return reply({ok:false,code:"invalid_usage_report"},400);
      const r=await rpc("moghimi_apply_usage_report",{
        p_device_id:device.device_id,p_session_id:sessionId,p_sequence:seq,p_rx_total:rx,p_tx_total:tx
      });
      return reply(r,r?.ok ? 200 : 409);
    }
    if (action === "claim") {
      const code=String(b.activation_code ?? "").replace(/[\s-]/g,"").toUpperCase();
      if (!/^[A-F0-9]{32,64}$/.test(code)) return reply({ok:false,code:"invalid_activation_code"},400);
      const r=await rpc("moghimi_claim_activation_code",{
        p_device_id:device.device_id,p_code_hash:await hash(code),p_username:device.username
      });
      return reply(r,r?.ok ? 200 : 409);
    }
    if (action === "release") {
      const r=await fetch(PROJECT_URL + "/rest/v1/app_releases?select=version_name,version_code,apk_url,sha256,release_notes&is_active=eq.true&order=version_code.desc&limit=1",{headers:headers()});
      if (!r.ok) throw new Error("release_lookup_" + r.status);
      const rows=await r.json();
      return reply({ok:true,release:rows[0] ?? null});
    }
    return reply({ok:false,code:"unknown_action"},400);
  } catch (e) {
    console.error("moghimi-api",e instanceof Error ? e.message : "unexpected_error");
    return reply({ok:false,code:"temporarily_unavailable"},503);
  }
});

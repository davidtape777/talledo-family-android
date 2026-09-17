// Server ONLY. Firebase private key stays in Supabase Secrets, never in Android/GitHub.
export type Env = (name: string) => string | undefined;
type Fetcher = typeof fetch;
const encoder = new TextEncoder();
export async function sameSecret(a: string, b: string): Promise<boolean> {
  const [x, y] = await Promise.all([a, b].map(s => crypto.subtle.digest('SHA-256', encoder.encode(s))));
  const left = new Uint8Array(x), right = new Uint8Array(y);
  let result = 0;
  for (let i = 0; i < left.length; i++) result |= left[i] ^ right[i];
  return result === 0;
}
function b64(bytes: Uint8Array): string {
  return btoa(Array.from(bytes, v => String.fromCharCode(v)).join('')).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
}
async function googleToken(env: Env, send: Fetcher): Promise<{ token: string; project: string }> {
  const account = JSON.parse(env('FIREBASE_SERVICE_ACCOUNT_JSON') || '{}');
  if (account.project_id !== 'talledo-family' || typeof account.client_email !== 'string' || typeof account.private_key !== 'string') throw Error('Firebase configuration');
  const now = Math.floor(Date.now() / 1000);
  const header = b64(encoder.encode(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const claims = b64(encoder.encode(JSON.stringify({ iss: account.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 })));
  const pem = account.private_key.replace(/-----[^-]+-----/g, '').replace(/\s/g, '');
  const bytes = Uint8Array.from(atob(pem), ch => ch.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', bytes, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, encoder.encode(`${header}.${claims}`));
  const response = await send('https://oauth2.googleapis.com/token', { method: 'POST', signal: AbortSignal.timeout(15000), headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${header}.${claims}.${b64(new Uint8Array(signature))}` }) });
  if (!response.ok) throw Error('OAuth failed');
  const json = await response.json();
  if (typeof json.access_token !== 'string' || !json.access_token) throw Error('OAuth failed');
  return { token: json.access_token, project: account.project_id };
}

export async function handleRequest(request: Request, env: Env = name => Deno.env.get(name), send: Fetcher = fetch): Promise<Response> {
  const respond = (status: number, value: string) => new Response(JSON.stringify({ status: value }), { status, headers: { 'Content-Type': 'application/json' } });
  if (request.method !== 'POST') return respond(405, 'method');
  const service = env('SUPABASE_SERVICE_ROLE_KEY');
  const url = env('SUPABASE_URL');
  if (!service || !url) return respond(503, 'configuration');
  if (!await sameSecret(request.headers.get('Authorization') || '', `Bearer ${service}`)) return respond(401, 'unauthorized');
  if (Number(request.headers.get('Content-Length') || 0) > 16384) return respond(413, 'payload');
  let id: string;
  try {
    const raw = await request.text();
    if (encoder.encode(raw).length > 16384) return respond(413, 'payload');
    const data = JSON.parse(raw);
    if (data.type !== 'INSERT' || data.table !== 'family_notifications' || data.schema !== 'public' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(data.record?.id || '')) return respond(400, 'payload');
    id = data.record.id;
  } catch { return respond(400, 'payload'); }
  const rpc = async (name: string, data: object) => {
    const response = await send(`${url.replace(/\/$/, '')}/rest/v1/rpc/${name}`, { method: 'POST', signal: AbortSignal.timeout(15000), headers: { apikey: service, Authorization: `Bearer ${service}`, 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
    if (!response.ok) throw Error('Database failed');
    const body = await response.text();
    return body ? JSON.parse(body) : null;
  };
  let claimed = false;
  try {
    const notice = await rpc('claim_family_notification', { notice: id });
    if (!notice) return respond(200, 'skipped');
    claimed = true;
    if (!Array.isArray(notice.tokens) || !notice.tokens.length) {
      await rpc('complete_family_notification', { notice: id, succeeded: false });
      return respond(200, 'no_devices');
    }
    const google = await googleToken(env, send);
    let success = true;
    for (const token of notice.tokens) {
      const response = await send(`https://fcm.googleapis.com/v1/projects/${google.project}/messages:send`, {
        method: 'POST', signal: AbortSignal.timeout(15000), headers: { Authorization: `Bearer ${google.token}`, 'Content-Type': 'application/json' },
        // Data-only and generic: no GPS, names or message text on Google's transport/lock screen.
        body: JSON.stringify({ message: { token, data: { notification_id: id, user_id: notice.user_id, kind: notice.kind }, android: { priority: 'HIGH', ttl: '300s', collapse_key: id } } })
      });
      if (!response.ok) success = false;
    }
    await rpc('complete_family_notification', { notice: id, succeeded: success });
    return respond(success ? 200 : 502, success ? 'sent' : 'delivery_failed');
  } catch {
    if (claimed) await rpc('complete_family_notification', { notice: id, succeeded: false }).catch(() => {});
    // Do not log OAuth tokens, service credentials, recipient device IDs or private payloads.
    return respond(502, 'delivery_failed');
  }
}
// Supabase loads the module; do not rely on import.meta.main being true there.
if (Deno.env.get('FAMILY_PUSH_TEST') !== 'true') Deno.serve(request => handleRequest(request));

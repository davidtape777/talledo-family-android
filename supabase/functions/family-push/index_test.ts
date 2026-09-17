import { handleRequest, sameSecret } from './index.ts';
function assert(value: boolean, message = 'Assertion failed'): void { if (!value) throw Error(message); }
const webhookSecret = 'test-only-webhook-secret-32-characters';
const env = (key: string) => ({ SUPABASE_URL: 'https://test.supabase.co', SUPABASE_SERVICE_ROLE_KEY: 'test-service-secret', FAMILY_WEBHOOK_SECRET: webhookSecret } as Record<string, string>)[key];
const id = '00000000-0000-0000-0000-000000000001';
const payload = { type: 'INSERT', schema: 'public', table: 'family_notifications', record: { id } };
function request(body: object = payload, secret = webhookSecret) { return new Request('https://test/function', { method: 'POST', headers: { 'x-family-webhook-secret': secret }, body: JSON.stringify(body) }); }
Deno.test('constant-time digest compares secrets', async () => { assert(await sameSecret('abc', 'abc')); assert(!await sameSecret('abc', 'abcd')); });
Deno.test('rejects anon or normal user before database', async () => {
  const response = await handleRequest(request(payload, 'publishable'), env, () => { throw Error('Unexpected fetch'); });
  assert(response.status === 401);
});
Deno.test('missing, wrong or legacy Authorization cannot replace dedicated webhook secret', async () => {
  for (const headers of [{}, { Authorization: 'Bearer test-service-secret' }, { Authorization: 'Bearer sb_publishable_test' }, { 'x-family-webhook-secret': 'test-service-secret' }]) {
    const req = new Request('https://test/function', { method: 'POST', headers: headers as Record<string, string>, body: JSON.stringify(payload) });
    assert((await handleRequest(req, env, () => { throw Error('Unexpected fetch'); })).status === 401);
  }
});
Deno.test('missing or short or padded webhook configuration fails closed', async () => {
  for (const secret of [undefined, 'short', ` ${webhookSecret}`]) {
    assert((await handleRequest(request(), key => key === 'FAMILY_WEBHOOK_SECRET' ? secret : env(key), () => { throw Error('Unexpected fetch'); })).status === 503);
  }
});
Deno.test('valid custom header authorizes without Authorization; database still uses server credential', async () => {
  const response = await handleRequest(request(), env, async (_url, options) => {
    const headers = new Headers(options?.headers);
    assert(headers.get('Authorization') === 'Bearer test-service-secret');
    assert(headers.get('apikey') === 'test-service-secret');
    assert(headers.get('x-family-webhook-secret') === null);
    return new Response('null');
  });
  assert(response.status === 200);
});
Deno.test('rejects forged table and malformed UUID', async () => {
  for (const data of [{ ...payload, table: 'family_members' }, { ...payload, record: { id: 'bad' } }, { ...payload, type: 'UPDATE' }]) {
    assert((await handleRequest(request(data), env, () => { throw Error('Unexpected fetch'); })).status === 400);
  }
});
Deno.test('revoked/already sent notification is not delivered', async () => {
  let calls = 0;
  const response = await handleRequest(request(), env, async () => { calls++; return new Response('null'); });
  assert(response.status === 200 && calls === 1);
});
Deno.test('missing devices reported without fetching Firebase', async () => {
  const calls: string[] = [];
  const response = await handleRequest(request(), env, async url => {
    calls.push(String(url)); return new Response(calls.length === 1 ? JSON.stringify({ id, tokens: [] }) : 'null');
  });
  assert(response.status === 200 && calls.length === 2 && calls.every(url => url.startsWith('https://test.supabase.co/')));
});
Deno.test('does not leak Firebase configuration or errors', async () => {
  const response = await handleRequest(request(), env, async url => new Response(String(url).includes('claim_') ? JSON.stringify({ id, tokens: ['test-device'] }) : 'null'));
  assert(response.status === 502 && !((await response.text()).includes('secret')));
});
Deno.test('signs OAuth and sends only generic account-bound FCM data', async () => {
  const keys = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', keys.privateKey));
  const encoded = btoa(Array.from(pkcs8, v => String.fromCharCode(v)).join(''));
  const account = JSON.stringify({ project_id: 'talledo-family', client_email: 'test@test.iam.gserviceaccount.com', private_key: `-----BEGIN PRIVATE KEY-----\n${encoded}\n-----END PRIVATE KEY-----` });
  let delivered = false, completed = false;
  const fake: typeof fetch = async (url, options) => {
    const endpoint = String(url);
    if (endpoint.includes('claim_family_notification')) return new Response(JSON.stringify({ id, user_id: id, kind: 'message', tokens: ['test-device-token'] }));
    if (endpoint === 'https://oauth2.googleapis.com/token') {
      const form = options?.body as URLSearchParams;
      assert((form.get('assertion') || '').split('.').length === 3);
      return new Response(JSON.stringify({ access_token: 'fake-google-token' }));
    }
    if (endpoint.startsWith('https://fcm.googleapis.com/')) {
      const body = JSON.parse(String(options?.body));
      assert(body.message.notification === undefined);
      assert(Object.keys(body.message.data).sort().join(',') === 'kind,notification_id,user_id');
      assert(body.message.data.user_id === id && !JSON.stringify(body).includes('test-service-secret'));
      delivered = true; return new Response('{}');
    }
    if (endpoint.includes('complete_family_notification')) { assert(JSON.parse(String(options?.body)).succeeded === true); completed = true; return new Response('null'); }
    throw Error('Unexpected URL');
  };
  const response = await handleRequest(request(), key => key === 'FIREBASE_SERVICE_ACCOUNT_JSON' ? account : env(key), fake);
  assert(response.status === 200 && delivered && completed);
});

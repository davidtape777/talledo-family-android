import { handleRequest, sameSecret } from './index.ts';
function assert(value: boolean, message = 'Assertion failed'): void { if (!value) throw Error(message); }
const env = (key: string) => ({ SUPABASE_URL: 'https://test.supabase.co', SUPABASE_SERVICE_ROLE_KEY: 'test-service-secret' } as Record<string, string>)[key];
const id = '00000000-0000-0000-0000-000000000001';
const payload = { type: 'INSERT', schema: 'public', table: 'family_notifications', record: { id } };
function request(body: object = payload, secret = 'test-service-secret') { return new Request('https://test/function', { method: 'POST', headers: { Authorization: `Bearer ${secret}` }, body: JSON.stringify(body) }); }
Deno.test('constant-time digest compares secrets', async () => { assert(await sameSecret('abc', 'abc')); assert(!await sameSecret('abc', 'abcd')); });
Deno.test('rejects anon or normal user before database', async () => {
  const response = await handleRequest(request(payload, 'publishable'), env, () => { throw Error('Unexpected fetch'); });
  assert(response.status === 401);
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

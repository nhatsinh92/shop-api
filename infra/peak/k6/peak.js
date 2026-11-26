// Peak scenario for `make demo-peak`: 3 minutes of browse, cart and checkout traffic through the
// gateway while a 1 000 ms latency toxic is on cart-service (toxiproxy) from 60 s to 120 s.
// Exits non-zero when a threshold fails (plans/epics/peak-readiness.md, "k6 scenarios").
//
// Load is fixed (override with the environment variables in brackets):
//   browse   50 VUs  (BROWSE_VUS)    products, product detail, flash sale, availability
//   cart     50 VUs  (CART_VUS)      the Phase 4 mix on guest carts, one guest cart per VU
//   checkout 20 VUs  (CHECKOUT_VUS)  reserve two lines, COD order with Idempotency-Key, one replay
//   chaos     1 VU                   adds and removes the toxic, reads gateway_fallback_total
//
// Users: setup logs each of the 200 load users in once (seed/load-users.sql; the gateway allows 5
// logins per email per minute) and every checkout iteration reuses one of those sessions, spread
// over all users. Cart VUs are guests, so they never log in.
//
// Request tags: kind = browse | cart_read | cart_write | reservation | order | replay, and
// phase = toxic | normal (the toxic window plus TOXIC_GRACE_S for the breaker to close again).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ORIGIN = __ENV.WEB_ORIGIN || 'http://localhost:3000';
const TOXIPROXY_URL = __ENV.TOXIPROXY_URL || 'http://localhost:8474';
const PROMETHEUS_URL = __ENV.PROMETHEUS_URL || 'http://localhost:9090';
const PASSWORD = __ENV.LOAD_PASSWORD || 'load-test-2026';

const BROWSE_VUS = Number(__ENV.BROWSE_VUS || 50);
const CART_VUS = Number(__ENV.CART_VUS || 50);
const CHECKOUT_VUS = Number(__ENV.CHECKOUT_VUS || 20);
const USER_COUNT = 200;

// Timeline in seconds from the start of the scenarios.
const TOTAL_S = 180;
const TOXIC_START_S = 60;
const TOXIC_END_S = 120;
// The gateway breaker stays open for 10 s after the toxic is gone (gateway/CLAUDE.md), so a 503
// SERVICE_UNAVAILABLE right after the window is still the expected fallback.
const TOXIC_GRACE_S = Number(__ENV.TOXIC_GRACE_S || 10);
// Each cart VU compares its cart with its own writes once, in the last seconds of the run.
const CONVERGE_AT_S = TOTAL_S - 10;
const PROXIES = ['cart-service', 'cart-service-web'];
const TOXIC_NAME = 'peak-latency';

// Two high-stock SKUs (seed/load-users.sql raises their stock).
const CHECKOUT_SKUS = ['SKU-BOTTLE-750', 'SKU-PAD-90'];
const CART_SKUS = [
  'SKU-ANC-PRO-BLK', 'SKU-ANC-PRO-WHT', 'SKU-ANC-PRO-BLU', 'SKU-AIRFRY-6L', 'SKU-JACKET-WP',
  'SKU-KB-75', 'SKU-BOTTLE-750', 'SKU-MOUSE-SIL', 'SKU-STAND-AL', 'SKU-SLEEVE-14', 'SKU-LAMP-LED',
  'SKU-PAD-90',
];
const MAX_QTY = 10;
// Over all cart iterations: 60 % GET /api/cart, 20 % summary, 15 % add, 5 % update (cart.js).
const MIX = { cart: 0.6, summary: 0.2, add: 0.15 };

const cartReadOk = new Rate('cart_read_ok');
const cartConverged = new Rate('cart_converged');
const replaySame = new Rate('checkout_replay_same_order');
const fallbackGrew = new Rate('gateway_fallback_grew');

function scenario(vus, fn) {
  return { executor: 'constant-vus', vus, duration: `${TOTAL_S}s`, exec: fn };
}

export const options = {
  setupTimeout: '5m', // 200 BCrypt logins
  scenarios: {
    browse: scenario(BROWSE_VUS, 'browse'),
    cart: scenario(CART_VUS, 'cart'),
    checkout: scenario(CHECKOUT_VUS, 'checkout'),
    chaos: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: `${TOTAL_S}s`, exec: 'chaos' },
  },
  thresholds: {
    // Cart reads: p95 within the 300 ms timeout plus gateway overhead, 2xx or (during the toxic
    // phase) 503 SERVICE_UNAVAILABLE.
    'http_req_duration{kind:cart_read}': ['p(95)<350'],
    cart_read_ok: ['rate==1'],
    // Cart writes: 2 s timeout plus overhead, always 2xx (they succeed after about 1 s while slow).
    'http_req_duration{kind:cart_write}': ['p(95)<2200'],
    'http_req_failed{kind:cart_write}': ['rate==0'],
    cart_converged: ['rate==1'],
    // Browse and checkout.
    'http_req_failed{kind:browse}': ['rate<0.01'],
    'http_req_duration{kind:browse}': ['p(95)<300'],
    'http_req_failed{kind:reservation}': ['rate<0.01'],
    'http_req_duration{kind:reservation}': ['p(95)<2000'],
    'http_req_failed{kind:order}': ['rate<0.01'],
    'http_req_duration{kind:order}': ['p(95)<5000'],
    'http_req_failed{kind:replay}': ['rate<0.01'],
    checkout_replay_same_order: ['rate==1'],
    // gateway_fallback_total grew during the toxic phase.
    gateway_fallback_grew: ['rate==1'],
  },
};

// Only 2xx counts as success for http_req_failed; cart reads are judged by cart_read_ok instead.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }));

const jsonHeaders = { 'Content-Type': 'application/json', Origin: ORIGIN };

// ---------- helpers ----------

function cookie(res, name) {
  const found = res.cookies[name];
  return found && found.length > 0 ? found[0].value : '';
}

// Wall-clock start of the scenarios, set at the end of setup (k6 starts them right after).
let runStartMs = 0;

function phase() {
  const t = (Date.now() - runStartMs) / 1000;
  return t >= TOXIC_START_S && t < TOXIC_END_S + TOXIC_GRACE_S ? 'toxic' : 'normal';
}

function tags(kind) {
  return { kind, phase: phase() };
}

function toxicApi(method, path, body) {
  return http.request(method, `${TOXIPROXY_URL}${path}`, body ? JSON.stringify(body) : null, {
    headers: { 'Content-Type': 'application/json' },
    tags: { kind: 'toxiproxy' },
    responseCallback: http.expectedStatuses({ min: 200, max: 299 }, 404),
  });
}

function removeToxics() {
  for (const proxy of PROXIES) {
    toxicApi('DELETE', `/proxies/${proxy}/toxics/${TOXIC_NAME}`);
  }
}

function addToxics() {
  for (const proxy of PROXIES) {
    toxicApi('DELETE', `/proxies/${proxy}/toxics/${TOXIC_NAME}`); // idempotent re-run
    const res = toxicApi('POST', `/proxies/${proxy}/toxics`, {
      name: TOXIC_NAME, type: 'latency', stream: 'downstream', toxicity: 1, attributes: { latency: 1000 },
    });
    check(res, { [`latency toxic added to ${proxy}`]: (r) => r.status === 200 });
  }
}

// Sum of gateway_fallback_total over all routes and reasons, from Prometheus (0 while no series).
function fallbackTotal() {
  const query = encodeURIComponent('sum(gateway_fallback_total) or vector(0)');
  const res = http.get(`${PROMETHEUS_URL}/api/v1/query?query=${query}`, { tags: { kind: 'prometheus' } });
  const result = res.status === 200 ? res.json('data.result') : null;
  return result && result.length > 0 ? Number(result[0].value[1]) : NaN;
}

// One login per email, in parallel batches (the gateway allows 5 per email per minute).
function loginBatch(emails) {
  const responses = http.batch(
    emails.map((email) => ({
      method: 'POST',
      url: `${BASE_URL}/api/auth/login`,
      body: JSON.stringify({ email, password: PASSWORD }),
      params: { headers: jsonHeaders, tags: { kind: 'setup' } },
    })),
  );
  return responses.map((res, i) => {
    if (res.status !== 200) {
      throw new Error(`login failed for ${emails[i]}: HTTP ${res.status} (is the load seed loaded?)`);
    }
    return `access_token=${cookie(res, 'access_token')}; logged_in=1`;
  });
}

// ---------- setup and teardown ----------

export function setup() {
  removeToxics();
  const sessions = [];
  for (let i = 1; i <= USER_COUNT; i += 20) {
    const batch = [];
    for (let j = i; j < i + 20 && j <= USER_COUNT; j++) batch.push(`load-${String(j).padStart(4, '0')}@example.com`);
    sessions.push(...loginBatch(batch));
  }
  const list = http.get(`${BASE_URL}/api/products?limit=50`, { tags: { kind: 'setup' } });
  const items = list.status === 200 ? list.json('items') : [];
  const products = items.map((p) => ({ slug: p.slug, sku: p.sku }));
  if (products.length === 0) throw new Error(`no products from GET /api/products: HTTP ${list.status}`);
  // Warm the cart-service product cache: every SKU is added to a throwaway guest cart and read.
  let guest = '';
  for (const sku of CART_SKUS) {
    const res = http.post(`${BASE_URL}/api/cart/items`, JSON.stringify({ sku, quantity: 1 }), {
      headers: guest ? { ...jsonHeaders, Cookie: `guest_cart=${guest}` } : jsonHeaders,
      tags: { kind: 'setup' },
    });
    guest = guest || cookie(res, 'guest_cart');
  }
  http.get(`${BASE_URL}/api/cart`, { headers: { Cookie: `guest_cart=${guest}` }, tags: { kind: 'setup' } });
  runStartMs = Date.now();
  return { sessions, products, startMs: runStartMs };
}

// Runs also after an abort, so a failed run never leaves the toxic on cart-service.
export function teardown() {
  removeToxics();
}

function init(data) {
  runStartMs = data.startMs;
}

// ---------- chaos: toxic from 60 s to 120 s, then the Prometheus check ----------

export function chaos(data) {
  init(data);
  sleep(TOXIC_START_S);
  const before = fallbackTotal();
  addToxics();
  sleep(TOXIC_END_S - TOXIC_START_S);
  removeToxics();
  sleep(7); // one Prometheus scrape (5 s) plus margin
  const after = fallbackTotal();
  const grew = after > before;
  console.log(`gateway_fallback_total: ${before} before the toxic phase, ${after} after`);
  fallbackGrew.add(grew);
  check(null, { 'gateway_fallback_total grew during the toxic phase': () => grew });
}

// ---------- browse ----------

export function browse(data) {
  init(data);
  const t = tags('browse');
  const product = data.products[Math.floor(Math.random() * data.products.length)];
  http.get(`${BASE_URL}/api/products?limit=20`, { tags: t });
  http.get(`${BASE_URL}/api/products/${product.slug}`, { tags: t });
  http.get(`${BASE_URL}/api/flash-sales/current`, { tags: t });
  http.get(`${BASE_URL}/api/products/${product.sku}/availability`, { tags: t });
  sleep(0.2);
}

// ---------- cart (per-VU state: module scope is per VU in k6) ----------

let guestCookie = '';
let lines = {}; // sku -> quantity this VU's guest cart should hold
let nextSku = 0;
let converged = false;

function guestHeaders(write) {
  const headers = write ? { ...jsonHeaders } : {};
  if (guestCookie) headers.Cookie = `guest_cart=${guestCookie}`;
  return headers;
}

function cartRead(path) {
  const t = tags('cart_read');
  const res = http.get(`${BASE_URL}${path}`, {
    headers: guestHeaders(false),
    tags: t,
    responseCallback: http.expectedStatuses({ min: 200, max: 299 }, 503),
  });
  let ok = res.status >= 200 && res.status < 300;
  if (res.status === 503 && t.phase === 'toxic') {
    ok = res.json('code') === 'SERVICE_UNAVAILABLE';
  }
  cartReadOk.add(ok);
  return res;
}

function cartAdd() {
  const sku = CART_SKUS[nextSku++ % CART_SKUS.length];
  const res = http.post(`${BASE_URL}/api/cart/items`, JSON.stringify({ sku, quantity: 1 }), {
    headers: guestHeaders(true),
    tags: tags('cart_write'),
  });
  guestCookie = guestCookie || cookie(res, 'guest_cart');
  if (res.status === 200) lines[sku] = (lines[sku] || 0) + 1;
  if (lines[sku] >= MAX_QTY) {
    // At 10 units the line is removed (not part of the mix, still a write that must succeed).
    const del = http.del(`${BASE_URL}/api/cart/items/${sku}`, null, {
      headers: guestHeaders(true),
      tags: tags('cart_write'),
    });
    if (del.status >= 200 && del.status < 300) delete lines[sku];
  }
}

function cartUpdate() {
  const held = Object.keys(lines);
  const sku = held[Math.floor(Math.random() * held.length)];
  const quantity = 1 + Math.floor(Math.random() * (MAX_QTY - 1));
  const res = http.patch(`${BASE_URL}/api/cart/items/${sku}`, JSON.stringify({ quantity }), {
    headers: guestHeaders(true),
    tags: tags('cart_write'),
  });
  if (res.status === 200) lines[sku] = quantity;
}

// After the toxic is gone, the cart read back must hold exactly the lines this VU wrote.
function cartConverge() {
  converged = true;
  const res = http.get(`${BASE_URL}/api/cart`, { headers: guestHeaders(false), tags: tags('converge') });
  let match = false;
  if (res.status === 200) {
    const server = {};
    for (const item of res.json('items')) server[item.sku] = item.quantity;
    const held = Object.keys(lines);
    match = Object.keys(server).length === held.length && held.every((sku) => server[sku] === lines[sku]);
  }
  cartConverged.add(match);
  check(null, { [`cart of VU ${exec.vu.idInTest} matches its writes`]: () => match });
}

export function cart(data) {
  init(data);
  if (!converged && (Date.now() - runStartMs) / 1000 >= CONVERGE_AT_S && Object.keys(lines).length > 0) {
    cartConverge();
    return;
  }
  if (Object.keys(lines).length === 0) {
    // First iteration: create the guest cart (a write like any other).
    cartAdd();
    return;
  }
  const r = Math.random();
  if (r < MIX.cart) cartRead('/api/cart');
  else if (r < MIX.cart + MIX.summary) cartRead('/api/cart/summary');
  else if (r < MIX.cart + MIX.summary + MIX.add) cartAdd();
  else cartUpdate();
}

// ---------- checkout ----------

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const n = Math.floor(Math.random() * 16);
    return (c === 'x' ? n : (n & 0x3) | 0x8).toString(16);
  });
}

export function checkout(data) {
  init(data);
  // Spread the iterations over all load users (each user's session was made once in setup).
  // The scenario's own iteration counter: VU ids are numbered across all scenarios, so ids of
  // checkout VUs are not contiguous and an id-based slot sent some users several VUs' iterations,
  // over the per-user reservation limit.
  const slot = exec.scenario.iterationInTest;
  const auth = { ...jsonHeaders, Cookie: data.sessions[slot % USER_COUNT] };

  const reserved = http.post(
    `${BASE_URL}/api/checkout/reservations`,
    JSON.stringify({ items: CHECKOUT_SKUS.map((sku) => ({ sku, quantity: 1 })) }),
    { headers: auth, tags: tags('reservation') },
  );
  if (!check(reserved, { 'reservation created': (r) => r.status === 201 })) {
    sleep(1);
    return;
  }
  const body = JSON.stringify({
    reservationId: reserved.json('id'),
    shippingOptionId: 'STANDARD',
    paymentMethod: 'COD',
    shippingAddress: {
      fullName: 'Load User', phone: '0901234567', line1: '12 Nguyen Hue', ward: 'Ben Nghe',
      district: 'District 1', province: 'Ho Chi Minh City',
    },
    note: 'k6 peak',
  });
  const headers = { ...auth, 'Idempotency-Key': uuid() };
  const order = http.post(`${BASE_URL}/api/orders`, body, { headers, tags: tags('order') });
  if (check(order, { 'COD order placed': (r) => r.status === 201 })) {
    const replay = http.post(`${BASE_URL}/api/orders`, body, { headers, tags: tags('replay') });
    replaySame.add(replay.status === 201 && replay.json('id') === order.json('id'));
  }
  sleep(1);
}

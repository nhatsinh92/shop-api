// Cart load scenario for `make demo-k6-cart`: 50 VUs for 60 s through the gateway.
// Fails (non-zero exit) when p95 of the cart requests reaches 50 ms or 1 % of requests fail.
//
// Users and VUs: the seed has three demo users, so 50 VUs must not write to a shared cart.
// VUs 1-3 sign in (setup logs each user in once: one login per email, far below the limit of
// 5 per minute) and send only GET /api/cart and GET /api/cart/summary. VUs 4-50 are guests with
// their own guest_cart each and are the only ones that add and update.
//
// Mix: over all iterations 60 % GET /api/cart, 20 % GET /api/cart/summary, 15 % add, 5 % update.
// The three user VUs run 6 % of the iterations and split them 60:20 between the two reads, so
// guests draw the remainder (see GUEST_MIX); all VUs iterate at the same pace (no sleep).
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ORIGIN = __ENV.WEB_ORIGIN || 'http://localhost:3000';
const VUS = 50;
const MAX_QTY = 10;

// The three demo users come from DEMO_USERS=email:password,... (the Makefile reads the
// passwords from seed/README.md).
const USERS = (__ENV.DEMO_USERS || '')
  .split(',')
  .filter((pair) => pair.includes(':'))
  .map((pair) => {
    const i = pair.indexOf(':');
    return { email: pair.slice(0, i), password: pair.slice(i + 1) };
  });
const SKUS = [
  'SKU-ANC-PRO-BLK', 'SKU-ANC-PRO-WHT', 'SKU-ANC-PRO-BLU', 'SKU-AIRFRY-6L', 'SKU-JACKET-WP',
  'SKU-KB-75', 'SKU-BOTTLE-750', 'SKU-MOUSE-SIL', 'SKU-STAND-AL', 'SKU-SLEEVE-14', 'SKU-LAMP-LED',
  'SKU-PAD-90',
];

// Target shares of all iterations, and the shares guests draw so that the total matches.
const MIX = { cart: 0.6, summary: 0.2, add: 0.15, update: 0.05 };
const userShare = USERS.length / VUS;
const guestShare = 1 - userShare;
const GUEST_MIX = {
  cart: (MIX.cart - userShare * 0.75) / guestShare,
  summary: (MIX.summary - userShare * 0.25) / guestShare,
  add: MIX.add / guestShare,
  update: MIX.update / guestShare,
};

export const options = {
  vus: VUS,
  duration: '60s',
  thresholds: {
    'http_req_duration{group:cart}': ['p(95)<50'],
    http_req_failed: ['rate<0.01'],
  },
};

// Only 2xx counts as success (also for http_req_failed).
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }));

const json = { 'Content-Type': 'application/json', Origin: ORIGIN };
const cartTags = { group: 'cart' };

function cookie(res, name) {
  const found = res.cookies[name];
  return found && found.length > 0 ? found[0].value : '';
}

export function setup() {
  if (USERS.length !== 3) {
    throw new Error('DEMO_USERS must list the three demo users as email:password pairs');
  }
  const sessions = USERS.map((u) => {
    const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify(u), { headers: json });
    if (!check(res, { 'demo user logged in': (r) => r.status === 200 })) {
      throw new Error(`login failed for ${u.email}: HTTP ${res.status}`);
    }
    return `access_token=${cookie(res, 'access_token')}; logged_in=1`;
  });
  // Warm the product cache: every SKU is added to a throwaway guest cart and read once.
  let guest = '';
  for (const sku of SKUS) {
    const res = http.post(`${BASE_URL}/api/cart/items`, JSON.stringify({ sku, quantity: 1 }), {
      headers: guest ? { ...json, Cookie: `guest_cart=${guest}` } : json,
    });
    guest = guest || cookie(res, 'guest_cart');
  }
  http.get(`${BASE_URL}/api/cart`, { headers: { Cookie: `guest_cart=${guest}` } });
  return { sessions };
}

// Per-VU state (module scope is per VU in k6).
let guestCookie = '';
let lines = {}; // sku -> quantity held in this VU's guest cart
let nextSku = 0;

function guestHeaders(write) {
  const headers = write ? { ...json } : {};
  if (guestCookie) headers.Cookie = `guest_cart=${guestCookie}`;
  return headers;
}

function add() {
  const sku = SKUS[nextSku++ % SKUS.length];
  const res = http.post(`${BASE_URL}/api/cart/items`, JSON.stringify({ sku, quantity: 1 }), {
    headers: guestHeaders(true),
    tags: cartTags,
  });
  guestCookie = guestCookie || cookie(res, 'guest_cart');
  if (res.status === 200) lines[sku] = (lines[sku] || 0) + 1;
  // At 10 units the line is removed (not part of the mix, not tagged group:cart).
  if (lines[sku] >= MAX_QTY) {
    http.del(`${BASE_URL}/api/cart/items/${sku}`, null, { headers: guestHeaders(true) });
    delete lines[sku];
  }
  return res;
}

function update() {
  const held = Object.keys(lines);
  const sku = held[Math.floor(Math.random() * held.length)];
  const quantity = 1 + Math.floor(Math.random() * (MAX_QTY - 1));
  const res = http.patch(`${BASE_URL}/api/cart/items/${sku}`, JSON.stringify({ quantity }), {
    headers: guestHeaders(true),
    tags: cartTags,
  });
  if (res.status === 200) lines[sku] = quantity;
}

function read(path, headers) {
  http.get(`${BASE_URL}${path}`, { headers, tags: cartTags });
}

export default function (data) {
  if (__VU <= USERS.length) {
    // Signed-in demo user: reads only, never writes to the shared user cart.
    const headers = { Cookie: data.sessions[__VU - 1] };
    read(Math.random() < 0.75 ? '/api/cart' : '/api/cart/summary', headers);
    return;
  }
  if (Object.keys(lines).length === 0) {
    // First iteration of a guest VU: create its cart (not tagged, not part of the mix).
    const sku = SKUS[nextSku++ % SKUS.length];
    const res = http.post(`${BASE_URL}/api/cart/items`, JSON.stringify({ sku, quantity: 1 }), {
      headers: guestHeaders(true),
    });
    guestCookie = guestCookie || cookie(res, 'guest_cart');
    if (res.status === 200) lines[sku] = 1;
    return;
  }
  const r = Math.random();
  if (r < GUEST_MIX.cart) read('/api/cart', guestHeaders(false));
  else if (r < GUEST_MIX.cart + GUEST_MIX.summary) read('/api/cart/summary', guestHeaders(false));
  else if (r < GUEST_MIX.cart + GUEST_MIX.summary + GUEST_MIX.add) add();
  else update();
}

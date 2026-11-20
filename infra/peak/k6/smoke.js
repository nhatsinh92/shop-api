// Placeholder load script for `make demo-peak`. Real scenarios arrive in Phase 6.
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = { vus: 5, duration: '20s' };

export default function () {
  const res = http.get(`${__ENV.BASE_URL}/actuator/health`);
  check(res, { 'gateway is up': (r) => r.status === 200 });
  sleep(1);
}

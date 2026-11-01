// Independent cross-check of the custom open-loop generator.
// constant-arrival-rate = open loop: iterations start on schedule regardless of response time;
// if VUs run out, k6 reports dropped_iterations instead of silently slowing down.
//
//   docker run --rm -v ${PWD}/bench/k6:/scripts grafana/k6 run -e URL=http://host.docker.internal:8091 -e RATE=2000 -e DURATION=30s /scripts/bid.js
import http from 'k6/http';
import { check } from 'k6';

const RATE = parseInt(__ENV.RATE || '2000');
const DURATION = __ENV.DURATION || '30s';
const URL = (__ENV.URL || 'http://localhost:8091') + '/openrtb/bid';

export const options = {
  scenarios: {
    open_loop: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(50, Math.ceil(RATE / 20)),
      maxVUs: Math.max(500, RATE / 2),
    },
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)', 'max'],
  thresholds: { http_req_failed: ['rate<0.01'] },
};

const GEOS = ['IN', 'US', 'GB', 'DE', 'BR', 'ID', 'JP', 'FR'];
const SEGS = ['sports', 'news', 'finance', 'gaming', 'travel', 'fashion', 'tech', 'food', 'auto', 'health'];
const CATS = ['news', 'sports', 'tech', 'finance', 'entertainment', 'lifestyle', 'travel', 'gaming', 'food', 'auto', 'health', 'education'];
const DEV = ['mobile', 'desktop', 'tablet', 'ctv'];

// deterministic LCG so runs are reproducible (k6's Math.random cannot be seeded)
let state = 42;
function rnd() { state = (state * 1664525 + 1013904223) % 4294967296; return state / 4294967296; }

export default function () {
  const u = Math.floor(Math.pow(1e6, rnd()));
  const pub = Math.floor(Math.pow(300, rnd()));
  const body = JSON.stringify({
    id: `k6-${__VU}-${__ITER}`,
    publisherId: `pub${pub}`,
    siteCategory: CATS[pub % CATS.length],
    device: DEV[u % 4],
    geo: GEOS[u % 8],
    userId: `u${u}`,
    segments: [SEGS[u % 10], SEGS[(u >> 3) % 10]],
    slots: [{ id: 's0', w: 300, h: 250, floorCpmMicros: 100000 + (pub % 7) * 400000 }],
  });
  const res = http.post(URL, body, { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'status is 200/204': (r) => r.status === 200 || r.status === 204 });
}

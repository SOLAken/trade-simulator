// 预热脚本：100 单/秒持续 15 秒，让 JIT 编译热点路径后再正式压测
import http from 'k6/http'
const GW = 'http://localhost:8080'
export const options = { setupTimeout: '2m', scenarios: { s: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 100), timeUnit: '1s', duration: "15s", preAllocatedVUs: 200, maxVUs: 800 } } }
export function setup() {
  const run = Date.now().toString(36); const tokens = []
  const reqs = []; for (let j = 0; j < 100; j++) reqs.push(['POST', `${GW}/api/user/register`, JSON.stringify({ username: `pb_${run}_${j}`, password: 'secret123' }), { headers: { 'Content-Type': 'application/json' } }])
  for (const r of http.batch(reqs)) { const t = r.json('data.token'); if (t) tokens.push(t) }
  http.batch(tokens.map((t) => ['POST', `${GW}/api/user/contests/1/join`, null, { headers: { Authorization: `Bearer ${t}` } }]))
  return { tokens, q: http.get(`${GW}/api/market/symbols`).json('data').map((s) => s.quote) }
}
export default function (d) {
  const t = d.tokens[Math.floor(Math.random() * d.tokens.length)]; const q = d.q[Math.floor(Math.random() * d.q.length)]
  const mkt = Math.random() < 0.4
  http.post(`${__ENV.TARGET || GW}/api/trade/orders`, JSON.stringify({ contestId: 1, symbol: q.symbol, side: 1, type: mkt ? 1 : 2, qty: 100, price: mkt ? 0 : Math.max(q.limitDown, Math.round(q.prevClose * 0.97)) }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}`, 'Idempotency-Key': `${__VU}-${__ITER}-${Date.now()}` } })
}

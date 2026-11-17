// 开盘瞬间集中下单压测
//   k6 run deploy/loadtest/open-burst.js
//   USERS=500 PEAK=2000 k6 run deploy/loadtest/open-burst.js
//
// 预期：网关 trade 路由 500 QPS 上限 + 单用户 5 单/秒 → 洪峰被 Sentinel 削平为 429，
// 放行的请求延迟稳定、无 5xx；压测结束后对账零差异。
import http from 'k6/http'
import { check } from 'k6'
import { Counter, Trend } from 'k6/metrics'
import exec from 'k6/execution'

const GW = __ENV.GW || 'http://localhost:8080'
const USERS = Number(__ENV.USERS || 500)
const PEAK = Number(__ENV.PEAK || 2000)
const CONTEST = Number(__ENV.CONTEST || 1)

const accepted = new Counter('orders_accepted')
const limited = new Counter('orders_rate_limited')
const rejected = new Counter('orders_biz_rejected')
const failed = new Counter('orders_server_error')
const acceptedLatency = new Trend('accepted_latency', true)

export const options = {
  setupTimeout: '5m',
  discardResponseBodies: false,
  scenarios: {
    open_burst: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 300,
      maxVUs: 1500,
      stages: [
        { target: 100, duration: '10s' },   // 开盘前：零星下单
        { target: PEAK, duration: '3s' },   // 开盘：瞬间涌入
        { target: PEAK, duration: '20s' },  // 洪峰持续
        { target: 200, duration: '5s' },
        { target: 200, duration: '10s' },   // 回落
      ],
    },
  },
  thresholds: {
    orders_server_error: ['count<1'],             // 不允许 5xx：限流应返回 429 而不是把服务打挂
    accepted_latency: ['p(99)<500'],
  },
}

const json = { headers: { 'Content-Type': 'application/json' } }

export function setup() {
  const run = Date.now().toString(36)
  const tokens = []
  for (let i = 0; i < USERS; i += 50) {
    const reqs = []
    for (let j = i; j < Math.min(i + 50, USERS); j++) {
      reqs.push(['POST', `${GW}/api/user/register`, JSON.stringify({ username: `lt_${run}_${j}`, password: 'secret123' }), json])
    }
    for (const r of http.batch(reqs)) {
      const t = r.json('data.token')
      if (t) tokens.push(t)
    }
  }
  const joins = tokens.map((t) => ['POST', `${GW}/api/user/contests/${CONTEST}/join`, null,
    { headers: { Authorization: `Bearer ${t}` } }])
  for (let i = 0; i < joins.length; i += 50) http.batch(joins.slice(i, i + 50))
  const symbols = http.get(`${GW}/api/market/symbols`).json('data').map((s) => s.quote)
  console.log(`setup: ${tokens.length} users joined contest ${CONTEST}`)
  return { tokens, symbols }
}

export default function (data) {
  const token = data.tokens[exec.scenario.iterationInTest % data.tokens.length]
  const q = data.symbols[Math.floor(Math.random() * data.symbols.length)]
  const market = Math.random() < 0.4
  // 限价单挂在昨收下方 3%，大概率挂单不成交；市价单 100 股立即成交
  const price = Math.max(q.limitDown, Math.round(q.prevClose * 0.97))
  const body = JSON.stringify({
    contestId: CONTEST, symbol: q.symbol, side: 1, type: market ? 1 : 2, qty: 100, price: market ? 0 : price,
  })
  const res = http.post(`${GW}/api/trade/orders`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`,
      'Idempotency-Key': `${exec.vu.idInTest}-${exec.scenario.iterationInTest}-${Date.now()}` },
    tags: { name: 'place_order' },
  })
  if (res.status === 200) {
    accepted.add(1)
    acceptedLatency.add(res.timings.duration)
  } else if (res.status === 429) {
    limited.add(1)
  } else if (res.status >= 500) {
    failed.add(1)
  } else {
    rejected.add(1)
  }
  check(res, { 'not 5xx': (r) => r.status < 500 })
}

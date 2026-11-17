<template>
  <div class="layout">
    <header class="panel top">
      <strong class="logo">模拟交易大赛</strong>
      <ContestBar />
      <span v-if="market.clock" class="clock">
        第 {{ market.clock.tradingDay }} 交易日 ·
        <b :class="market.clock.open ? 'up' : 'muted'">{{ market.clock.open ? '交易中' : '休市' }}</b>
        <span class="muted"> · {{ countdown }}</span>
      </span>
      <span class="dot" :class="{ on: market.connected }" :title="market.connected ? '行情已连接' : '重连中'" />
      <span class="spacer" />
      <router-link to="/rank" class="nav">排行榜</router-link>
      <span class="muted">{{ auth.nickname }}</span>
      <button @click="logout">退出</button>
    </header>

    <aside class="panel side"><QuoteList v-model="symbol" /></aside>

    <main class="panel main">
      <div v-if="quote" class="quote">
        <div>
          <div class="title">{{ name }} <small class="muted">{{ symbol }}</small></div>
          <div class="price" :class="trend(quote.last, quote.prevClose)">
            {{ yuan(quote.last) }}
            <small>{{ pct(quote.last, quote.prevClose) }}</small>
          </div>
        </div>
        <dl>
          <div><dt>今开</dt><dd>{{ yuan(quote.open) }}</dd></div>
          <div><dt>最高</dt><dd class="up">{{ yuan(quote.high) }}</dd></div>
          <div><dt>最低</dt><dd class="down">{{ yuan(quote.low) }}</dd></div>
          <div><dt>昨收</dt><dd>{{ yuan(quote.prevClose) }}</dd></div>
          <div><dt>涨停</dt><dd class="up">{{ yuan(quote.limitUp) }}</dd></div>
          <div><dt>跌停</dt><dd class="down">{{ yuan(quote.limitDown) }}</dd></div>
          <div><dt>成交量</dt><dd>{{ volume(quote.volume) }}</dd></div>
        </dl>
      </div>
      <div class="intervals">
        <button v-for="i in intervals" :key="i.v" :class="{ on: interval === i.v }" @click="interval = i.v">{{ i.label }}</button>
      </div>
      <div class="chart-wrap"><KlineChart v-if="symbol" :symbol="symbol" :interval="interval" /></div>
    </main>

    <aside class="panel right">
      <AccountSummary />
      <OrderPanel v-if="symbol" :symbol="symbol" />
      <div v-if="game.contest && !game.joined" class="join-tip muted">报名参赛后即可下单</div>
      <RankMini />
    </aside>

    <section class="panel bottom"><BottomTabs @pick="symbol = $event" /></section>
  </div>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import QuoteList from '../components/QuoteList.vue'
import KlineChart from '../components/KlineChart.vue'
import ContestBar from '../components/ContestBar.vue'
import OrderPanel from '../components/OrderPanel.vue'
import AccountSummary from '../components/AccountSummary.vue'
import BottomTabs from '../components/BottomTabs.vue'
import RankMini from '../components/RankMini.vue'
import { useGame } from '../stores/game.js'
import { useMarket } from '../stores/market.js'
import { useAuth } from '../stores/auth.js'
import { yuan, pct, trend, volume } from '../utils/format.js'

const market = useMarket()
const game = useGame()
const auth = useAuth()
const router = useRouter()
const symbol = ref('')
const interval = ref('5s')
const intervals = [
  { v: '5s', label: '5秒' }, { v: '15s', label: '15秒' }, { v: '1m', label: '1分' },
  { v: '5m', label: '5分' }, { v: '1d', label: '日K' },
]
const quote = computed(() => market.quotes[symbol.value])
const name = computed(() => market.symbols.find((s) => s.symbol === symbol.value)?.name)

const now = ref(Date.now())
const timer = setInterval(() => (now.value = Date.now()), 1000)
onBeforeUnmount(() => clearInterval(timer))
const countdown = computed(() => {
  const c = market.clock
  if (!c) return ''
  const skew = c.serverTime - Date.now()
  const target = c.open ? c.closeAt : c.nextOpenAt
  const s = Math.max(0, Math.round((target - now.value - skew) / 1000))
  return `${c.open ? '距收盘' : '距开盘'} ${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
})

onMounted(async () => {
  if (!market.symbols.length) await market.init()
  if (!game.contests.length) await game.init()
  symbol.value ||= market.symbols[0]?.symbol
})

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout {
  display: grid; gap: 8px; padding: 8px; height: 100vh;
  grid-template-columns: 240px minmax(0, 1fr) 320px; grid-template-rows: auto minmax(0, 1fr) 260px;
  grid-template-areas: "top top top" "side main right" "side bottom right";
}
.top { grid-area: top; display: flex; align-items: center; gap: 14px; padding: 10px 16px; }
.logo { color: var(--accent); }
.spacer { flex: 1; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--up); }
.dot.on { background: var(--down); }
.side { grid-area: side; overflow: auto; }
.right { grid-area: right; overflow: auto; display: flex; flex-direction: column; }
.right > :deep(*) + :deep(*) { border-top: 1px solid var(--border); }
.bottom { grid-area: bottom; min-height: 0; overflow: hidden; }
.nav { color: var(--text); text-decoration: none; }
.nav:hover { color: var(--accent); }
.join-tip { padding: 10px 14px; font-size: 12px; }
.main { grid-area: main; display: flex; flex-direction: column; padding: 12px 16px; min-width: 0; }
.quote { display: flex; gap: 32px; align-items: center; flex-wrap: wrap; }
.title { font-size: 16px; font-weight: 600; }
.price { font-size: 30px; font-weight: 700; }
.price small { font-size: 16px; margin-left: 6px; }
dl { display: flex; gap: 20px; margin: 0; flex-wrap: wrap; }
dt { color: var(--muted); font-size: 12px; }
dd { margin: 2px 0 0; }
.intervals { display: flex; gap: 6px; margin: 14px 0 6px; }
.intervals button { padding: 3px 10px; font-size: 12px; }
.intervals .on { border-color: var(--accent); color: var(--accent); }
.chart-wrap { flex: 1; min-height: 0; }

@media (max-width: 1100px) {
  .layout { grid-template-columns: 220px minmax(0, 1fr); grid-template-rows: auto 460px auto 300px;
    grid-template-areas: "top top" "side main" "right right" "bottom bottom"; height: auto; }
}
@media (max-width: 760px) {
  .layout { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto;
    grid-template-areas: "top" "main" "right" "side" "bottom"; height: auto; }
  .top { flex-wrap: wrap; }
  .chart-wrap { height: 360px; flex: none; }
  .bottom { height: 320px; }
}
</style>

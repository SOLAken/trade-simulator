<template>
  <div class="panel-inner">
    <div class="sides">
      <button :class="{ buy: side === 1 }" @click="side = 1">买入</button>
      <button :class="{ sell: side === 2 }" @click="side = 2">卖出</button>
    </div>
    <div class="types">
      <button v-for="t in types" :key="t.v" :class="{ on: type === t.v }" @click="type = t.v">{{ t.label }}</button>
    </div>

    <label v-if="type === 2">委托价（元）
      <div class="stepper">
        <button @click="nudge(-1)">−</button>
        <input v-model.number="price" type="number" step="0.01" />
        <button @click="nudge(1)">+</button>
      </div>
    </label>
    <label v-if="type === 3">触发价（元）
      <input v-model.number="stop" type="number" step="0.01" />
    </label>
    <label>数量（股）
      <input v-model.number="qty" type="number" :step="side === 1 ? 100 : 1" min="1" />
    </label>
    <div class="quick">
      <button v-for="f in [0.25, 0.5, 1]" :key="f" @click="fill(f)">{{ f === 1 ? '全部' : `${f * 100}%` }}</button>
    </div>
    <div class="hint muted">
      <span v-if="side === 1">可用 {{ yuan(game.overview?.cash) }} · 最多可买 {{ maxBuy }} 股</span>
      <span v-else>可卖 {{ sellable }} 股</span>
      <span v-if="quote"> · 涨停 {{ yuan(quote.limitUp) }} / 跌停 {{ yuan(quote.limitDown) }}</span>
    </div>
    <div class="hint muted" v-if="type === 1">市价单：按最新价 ± {{ game.rules?.slippageBps ?? '-' }} 基点滑点成交，未成交部分撤销</div>
    <div class="hint muted" v-if="type === 3">止损单：价格触及触发价后转为市价单</div>
    <button class="submit" :class="side === 1 ? 'buy' : 'sell'" :disabled="busy || !game.joined" @click="submit">
      {{ side === 1 ? '买入' : '卖出' }} {{ symbolName }}
    </button>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useGame } from '../stores/game.js'
import { useMarket } from '../stores/market.js'
import { useToast } from '../stores/toast.js'
import { yuan } from '../utils/format.js'
import { api } from '../api.js'

const props = defineProps({ symbol: String })
const game = useGame()
const market = useMarket()
const toast = useToast()
const side = ref(1)
const type = ref(2)
const price = ref(0)
const stop = ref(0)
const qty = ref(100)
const busy = ref(false)
const types = [{ v: 2, label: '限价' }, { v: 1, label: '市价' }, { v: 3, label: '止损' }]

const quote = computed(() => market.quotes[props.symbol])
const symbolName = computed(() => market.symbols.find((s) => s.symbol === props.symbol)?.name ?? '')
const position = computed(() => game.overview?.positions?.find((p) => p.symbol === props.symbol))
const sellable = computed(() => position.value?.avail ?? 0)
const maxBuy = computed(() => {
  const px = type.value === 2 ? Math.round(price.value * 100) : quote.value?.limitUp
  if (!px || !game.overview) return 0
  return Math.floor(game.overview.cash / (px * (1 + Number(game.rules?.feeRate ?? 0.0003))) / 100) * 100
})

// 切换股票时把委托价/触发价重置为最新价
watch(() => props.symbol, () => {
  if (quote.value) price.value = stop.value = quote.value.last / 100
}, { immediate: true })
watch(quote, (q, old) => { if (q && !old) price.value = stop.value = q.last / 100 })

const nudge = (d) => (price.value = Math.round(price.value * 100 + d) / 100)
const fill = (f) => {
  qty.value = side.value === 1 ? Math.floor((maxBuy.value * f) / 100) * 100 : Math.floor(sellable.value * f)
}

const STATUS = ['已报', '部分成交', '全部成交', '已撤', '拒绝']

async function submit() {
  busy.value = true
  try {
    const o = await game.place({
      symbol: props.symbol, side: side.value, type: type.value, qty: qty.value,
      price: type.value === 2 ? Math.round(price.value * 100) : 0,
      stopPrice: type.value === 3 ? Math.round(stop.value * 100) : 0,
    })
    const filled = o.filledQty ? `，成交 ${o.filledQty} 股` : ''
    toast.ok(`委托${STATUS[o.status]}${filled}`)
  } catch (e) {
    if (e.code === 3001) {
      // 被风控封禁：展示原因与剩余时长
      const s = await api('/api/risk/me').catch(() => null)
      toast.err(s?.blocked ? `${s.reason}，${Math.ceil(s.secondsLeft / 60)} 分钟后解除` : e.message)
    } else {
      toast.err(e.message)
    }
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.panel-inner { display: flex; flex-direction: column; gap: 10px; padding: 14px; }
.sides, .types, .quick { display: grid; grid-auto-flow: column; gap: 6px; }
.sides button { padding: 8px; font-weight: 600; }
.sides .buy { background: var(--up); border-color: var(--up); color: #fff; }
.sides .sell { background: var(--down); border-color: var(--down); color: #fff; }
.types button, .quick button { padding: 4px; font-size: 12px; }
.types .on { border-color: var(--accent); color: var(--accent); }
label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--muted); }
.stepper { display: grid; grid-template-columns: 34px 1fr 34px; gap: 4px; }
.stepper button { padding: 0; }
.hint { font-size: 12px; line-height: 1.5; }
.submit { padding: 10px; font-weight: 700; color: #fff; border: none; }
.submit.buy { background: var(--up); }
.submit.sell { background: var(--down); }
.submit:disabled { opacity: 0.5; cursor: not-allowed; }
</style>

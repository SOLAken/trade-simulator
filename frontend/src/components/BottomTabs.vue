<template>
  <div class="tabs-wrap">
    <div class="tabs">
      <button v-for="t in tabs" :key="t.k" :class="{ on: tab === t.k }" @click="tab = t.k">
        {{ t.label }}<small v-if="t.count" class="muted"> {{ t.count }}</small>
      </button>
    </div>
    <div class="table-wrap">
      <table v-if="tab === 'pos'">
        <thead><tr><th>股票</th><th>持仓</th><th>可卖</th><th>T+1 待解锁</th><th>成本价</th><th>现价</th><th>市值</th><th>浮动盈亏</th></tr></thead>
        <tbody>
          <tr v-for="p in positions" :key="p.symbol" @click="$emit('pick', p.symbol)">
            <td>{{ name(p.symbol) }}</td><td>{{ p.qty }}</td><td>{{ p.avail }}</td><td>{{ p.pending }}</td>
            <td>{{ p.qty ? yuan(Math.round(p.cost / p.qty)) : '--' }}</td><td>{{ yuan(p.last) }}</td>
            <td>{{ yuan(p.marketValue) }}</td><td :class="cls(p.pnl)">{{ yuan(p.pnl) }}</td>
          </tr>
          <tr v-if="!positions.length"><td colspan="8" class="empty">暂无持仓</td></tr>
        </tbody>
      </table>
      <table v-else-if="tab === 'orders'">
        <thead><tr><th>时间</th><th>股票</th><th>方向</th><th>类型</th><th>价格</th><th>数量</th><th>已成交</th><th>状态</th><th></th></tr></thead>
        <tbody>
          <tr v-for="o in game.orders" :key="o.id">
            <td>{{ time(o.createdAt) }}</td><td>{{ name(o.symbol) }}</td>
            <td :class="o.side === 1 ? 'up' : 'down'">{{ o.side === 1 ? '买' : '卖' }}</td>
            <td>{{ TYPES[o.type] }}</td><td>{{ o.type === 2 ? yuan(o.price) : o.type === 3 ? '触发 ' + yuan(o.stopPrice) : '市价' }}</td>
            <td>{{ o.qty }}</td><td>{{ o.filledQty }}</td>
            <td :title="o.rejectReason || ''">{{ STATUS[o.status] }}<small v-if="o.rejectReason" class="muted"> · {{ o.rejectReason }}</small></td>
            <td><button v-if="o.status < 2" class="link" @click="cancel(o.id)">撤单</button></td>
          </tr>
          <tr v-if="!game.orders.length"><td colspan="9" class="empty">暂无委托</td></tr>
        </tbody>
      </table>
      <table v-else>
        <thead><tr><th>时间</th><th>股票</th><th>方向</th><th>成交价</th><th>数量</th><th>成交额</th><th>佣金</th><th>印花税</th></tr></thead>
        <tbody>
          <tr v-for="t in game.trades" :key="t.id">
            <td>{{ time(t.createdAt) }}</td><td>{{ name(t.symbol) }}</td>
            <td :class="t.side === 1 ? 'up' : 'down'">{{ t.side === 1 ? '买' : '卖' }}</td>
            <td>{{ yuan(t.price) }}</td><td>{{ t.qty }}</td><td>{{ yuan(t.amount) }}</td>
            <td>{{ yuan(t.commission) }}</td><td>{{ yuan(t.tax) }}</td>
          </tr>
          <tr v-if="!game.trades.length"><td colspan="8" class="empty">暂无成交</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useGame } from '../stores/game.js'
import { useMarket } from '../stores/market.js'
import { useToast } from '../stores/toast.js'
import { yuan } from '../utils/format.js'

defineEmits(['pick'])
const game = useGame()
const market = useMarket()
const toast = useToast()
const tab = ref('pos')
const TYPES = ['', '市价', '限价', '止损']
const STATUS = ['已报', '部分成交', '全部成交', '已撤', '拒绝']
const positions = computed(() => game.overview?.positions ?? [])
const tabs = computed(() => [
  { k: 'pos', label: '持仓', count: positions.value.length },
  { k: 'orders', label: '委托', count: game.orders.filter((o) => o.status < 2).length },
  { k: 'trades', label: '成交', count: 0 },
])
const name = (s) => market.symbols.find((x) => x.symbol === s)?.name ?? s
const cls = (v) => (v > 0 ? 'up' : v < 0 ? 'down' : '')
const time = (t) => (t ? new Date(t + 'Z').toLocaleTimeString('zh-CN', { hour12: false }) : '')

async function cancel(id) {
  try {
    const ok = await game.cancel(id)
    ok ? toast.ok('撤单成功') : toast.err('订单已成交或已撤销')
  } catch (e) {
    toast.err(e.message)
  }
}
</script>

<style scoped>
.tabs-wrap { display: flex; flex-direction: column; min-height: 0; height: 100%; }
.tabs { display: flex; gap: 4px; padding: 8px 12px 0; border-bottom: 1px solid var(--border); }
.tabs button { border: none; background: none; border-bottom: 2px solid transparent; border-radius: 0; padding: 6px 10px; }
.tabs .on { border-bottom-color: var(--accent); color: var(--accent); }
.table-wrap { overflow: auto; flex: 1; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th { position: sticky; top: 0; background: var(--panel); color: var(--muted); font-weight: 400; font-size: 12px; text-align: right; padding: 6px 12px; }
td { text-align: right; padding: 6px 12px; border-top: 1px solid #1f2633; white-space: nowrap; }
th:first-child, td:first-child, th:nth-child(2), td:nth-child(2) { text-align: left; }
tbody tr:hover { background: var(--panel-2); cursor: default; }
.empty { text-align: center !important; color: var(--muted); padding: 24px; }
.link { border: none; background: none; color: var(--accent); padding: 0; }
</style>

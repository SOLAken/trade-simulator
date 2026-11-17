<template>
  <div class="list">
    <div class="head muted"><span>名称</span><span>最新</span><span>涨跌幅</span></div>
    <button
      v-for="s in market.symbols" :key="s.symbol" class="row"
      :class="{ active: s.symbol === modelValue }" @click="$emit('update:modelValue', s.symbol)">
      <span class="name">{{ s.name }}<small class="muted">{{ s.symbol }}</small></span>
      <span :class="cls(s.symbol)">{{ yuan(q(s.symbol)?.last) }}</span>
      <span :class="cls(s.symbol)">{{ q(s.symbol) ? pct(q(s.symbol).last, q(s.symbol).prevClose) : '--' }}</span>
    </button>
  </div>
</template>

<script setup>
import { useMarket } from '../stores/market.js'
import { yuan, pct, trend } from '../utils/format.js'

defineProps({ modelValue: String })
defineEmits(['update:modelValue'])
const market = useMarket()
const q = (s) => market.quotes[s]
const cls = (s) => (q(s) ? trend(q(s).last, q(s).prevClose) : '')
</script>

<style scoped>
.list { display: flex; flex-direction: column; }
.head, .row { display: grid; grid-template-columns: 1.4fr 1fr 1fr; gap: 8px; padding: 8px 12px; text-align: right; }
.head { font-size: 12px; }
.head span:first-child, .row span:first-child { text-align: left; }
.row { border: none; border-radius: 0; background: none; border-left: 2px solid transparent; }
.row:hover { background: var(--panel-2); }
.row.active { background: var(--panel-2); border-left-color: var(--accent); }
.name { display: flex; flex-direction: column; }
.name small { font-size: 11px; }
</style>

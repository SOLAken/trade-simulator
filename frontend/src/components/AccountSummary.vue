<template>
  <div class="summary" v-if="o">
    <div class="big">
      <span class="muted">总资产</span>
      <b>{{ yuan(o.totalAsset) }}</b>
      <span :class="o.returnPct > 0 ? 'up' : o.returnPct < 0 ? 'down' : ''">{{ o.returnPct > 0 ? '+' : '' }}{{ o.returnPct.toFixed(2) }}%</span>
    </div>
    <dl>
      <div><dt>可用</dt><dd>{{ yuan(o.cash) }}</dd></div>
      <div><dt>冻结</dt><dd>{{ yuan(o.frozen) }}</dd></div>
      <div><dt>持仓市值</dt><dd>{{ yuan(o.marketValue) }}</dd></div>
    </dl>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useGame } from '../stores/game.js'
import { yuan } from '../utils/format.js'
const game = useGame()
const o = computed(() => game.overview)
</script>

<style scoped>
.summary { padding: 14px; border-bottom: 1px solid var(--border); }
.big { display: flex; align-items: baseline; gap: 10px; }
.big b { font-size: 22px; }
dl { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 10px 0 0; }
dt { font-size: 12px; color: var(--muted); }
dd { margin: 2px 0 0; }
</style>

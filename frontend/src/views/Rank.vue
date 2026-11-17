<template>
  <div class="page">
    <header class="panel top">
      <router-link to="/" class="back">← 返回交易</router-link>
      <strong>{{ game.contest?.name }} · 排行榜</strong>
      <span class="muted">{{ board?.participants ?? 0 }} 人参赛 · 实时刷新</span>
    </header>
    <section class="panel me" v-if="board?.me">
      <div>我的排名 <b class="accent">第 {{ board.me.rank }} 名</b></div>
      <div>总资产 <b>{{ yuan(board.me.total) }}</b></div>
      <div>收益率 <b :class="cls(board.me.returnPct)">{{ sign(board.me.returnPct) }}%</b></div>
    </section>
    <section class="panel curve">
      <div class="title">我的收益曲线</div>
      <div ref="chartEl" class="chart" />
      <p v-if="!curve.length" class="muted empty">暂无数据（每 30 秒记录一次，交易一会儿再来看）</p>
    </section>
    <section class="panel list">
      <table>
        <thead><tr><th>名次</th><th>选手</th><th>总资产</th><th>收益率</th></tr></thead>
        <tbody>
          <tr v-for="e in board?.top ?? []" :key="e.userId" :class="{ mine: e.userId === auth.userId }">
            <td><span class="no" :class="'n' + e.rank">{{ e.rank }}</span></td>
            <td>{{ e.nickname }}</td><td>{{ yuan(e.total) }}</td>
            <td :class="cls(e.returnPct)">{{ sign(e.returnPct) }}%</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { api } from '../api.js'
import { useAuth } from '../stores/auth.js'
import { useGame } from '../stores/game.js'
import { yuan } from '../utils/format.js'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])
const auth = useAuth()
const game = useGame()
const board = ref(null)
const curve = ref([])
const chartEl = ref()
let chart, timer

const cls = (v) => (v > 0 ? 'up' : v < 0 ? 'down' : '')
const sign = (v) => `${v > 0 ? '+' : ''}${v.toFixed(2)}`

async function load() {
  board.value = await api(`/api/rank/${game.contestId}?top=100`).catch(() => board.value)
}

async function loadCurve() {
  curve.value = await api(`/api/rank/${game.contestId}/curve?hours=24`).catch(() => [])
  const init = board.value?.initCash || 1
  chart.setOption({
    animation: false,
    grid: { left: 64, right: 20, top: 16, bottom: 28 },
    tooltip: { trigger: 'axis', backgroundColor: '#1c2230', borderColor: '#2a3140', textStyle: { color: '#e6edf3' },
      valueFormatter: (v) => `${v.toFixed(2)}%` },
    xAxis: { type: 'time', axisLine: { lineStyle: { color: '#2a3140' } }, axisLabel: { color: '#8b949e' } },
    yAxis: { type: 'value', scale: true, axisLabel: { color: '#8b949e', formatter: '{value}%' }, splitLine: { lineStyle: { color: '#1f2633' } } },
    series: [{
      type: 'line', showSymbol: false, lineStyle: { width: 2, color: '#f0b90b' },
      areaStyle: { color: 'rgba(240,185,11,0.08)' },
      data: curve.value.map(([t, v]) => [t, ((v - init) / init) * 100]),
      markLine: { silent: true, symbol: 'none', data: [{ yAxis: 0 }], lineStyle: { color: '#8b949e', type: 'dashed' }, label: { show: false } },
    }],
  })
}

onMounted(async () => {
  if (!game.contests.length) await game.init()
  chart = echarts.init(chartEl.value)
  await load()
  await loadCurve()
  timer = setInterval(load, 2000)
})
onBeforeUnmount(() => { clearInterval(timer); chart?.dispose() })
</script>

<style scoped>
.page { max-width: 960px; margin: 0 auto; padding: 8px; display: flex; flex-direction: column; gap: 8px; }
.top { display: flex; gap: 16px; align-items: center; padding: 10px 16px; flex-wrap: wrap; }
.back { color: var(--accent); text-decoration: none; }
.me { display: flex; gap: 32px; padding: 14px 16px; flex-wrap: wrap; }
.accent { color: var(--accent); }
.curve { padding: 12px 16px; position: relative; }
.title { font-weight: 600; margin-bottom: 6px; }
.chart { height: 260px; }
.empty { position: absolute; inset: 40px 0 0; display: grid; place-items: center; margin: 0; }
.list { overflow: auto; }
table { width: 100%; border-collapse: collapse; }
th { color: var(--muted); font-weight: 400; font-size: 12px; text-align: left; padding: 8px 16px; }
td { padding: 8px 16px; border-top: 1px solid #1f2633; }
th:nth-child(n+3), td:nth-child(n+3) { text-align: right; }
tr.mine { background: rgb(240 185 11 / 0.12); }
.no { display: inline-block; width: 24px; text-align: center; color: var(--muted); }
.n1 { color: #f0b90b; font-weight: 700; } .n2 { color: #c0c7d0; font-weight: 700; } .n3 { color: #cd7f32; font-weight: 700; }
</style>

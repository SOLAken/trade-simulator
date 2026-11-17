<template>
  <div ref="el" class="chart" />
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { CandlestickChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, DataZoomComponent, AxisPointerComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { api } from '../api.js'
import { onTick } from '../stores/market.js'

echarts.use([CandlestickChart, BarChart, GridComponent, TooltipComponent, DataZoomComponent, AxisPointerComponent, CanvasRenderer])

const props = defineProps({ symbol: String, interval: String })
const el = ref()
const UP = '#f6465d', DOWN = '#0ecb81'
const MS = { '5s': 5e3, '15s': 15e3, '1m': 6e4, '5m': 3e5, '15m': 9e5 }
let chart, bars = [], off, lastVolume = 0, lastDay = 0

const pad = (n) => String(n).padStart(2, '0')
const fmtTime = (ts) => {
  const d = new Date(ts)
  return props.interval === '1d'
    ? `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
    : `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function render() {
  const x = bars.map((b) => fmtTime(b[0]))
  chart.setOption({
    xAxis: [{ data: x }, { data: x }],
    series: [
      { data: bars.map((b) => [b[1], b[2], b[3], b[4]].map((v) => v / 100)) },
      { data: bars.map((b) => ({ value: b[5], itemStyle: { color: b[2] >= b[1] ? UP : DOWN } })) },
    ],
  })
}

async function load() {
  bars = (await api(`/api/market/kline?symbol=${props.symbol}&interval=${props.interval}&limit=200`)) || []
  lastVolume = 0
  render()
}

/** 实时 tick 增量合并到最后一根 K 线：同 bucket 更新 high/low/close，跨 bucket 新开一根。 */
function onLive(t) {
  if (t.symbol !== props.symbol) return
  const qty = lastVolume && t.volume >= lastVolume && t.tradingDay === lastDay ? t.volume - lastVolume : 0
  lastVolume = t.volume
  lastDay = t.tradingDay
  if (props.interval === '1d') return   // 日线只在加载时拉取
  const bucket = Math.floor(t.ts / MS[props.interval]) * MS[props.interval]
  const last = bars[bars.length - 1]
  if (last && last[0] === bucket) {
    last[2] = t.last
    last[3] = Math.min(last[3], t.last)
    last[4] = Math.max(last[4], t.last)
    last[5] += qty
  } else {
    bars.push([bucket, t.last, t.last, t.last, t.last, qty])
    if (bars.length > 400) bars.shift()
  }
  render()
}

onMounted(() => {
  chart = echarts.init(el.value, null, { renderer: 'canvas' })
  const axis = { axisLine: { lineStyle: { color: '#2a3140' } }, axisLabel: { color: '#8b949e' } }
  chart.setOption({
    animation: false,
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' }, backgroundColor: '#1c2230', borderColor: '#2a3140', textStyle: { color: '#e6edf3' } },
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [{ left: 56, right: 16, top: 16, height: '62%' }, { left: 56, right: 16, top: '78%', height: '14%' }],
    xAxis: [{ type: 'category', ...axis, boundaryGap: true }, { type: 'category', gridIndex: 1, ...axis, axisLabel: { show: false } }],
    yAxis: [
      { scale: true, ...axis, splitLine: { lineStyle: { color: '#1f2633' } } },
      { gridIndex: 1, ...axis, axisLabel: { show: false }, splitLine: { show: false } },
    ],
    dataZoom: [{ type: 'inside', xAxisIndex: [0, 1], start: 40, end: 100 }],
    series: [
      { name: 'K线', type: 'candlestick', itemStyle: { color: UP, color0: DOWN, borderColor: UP, borderColor0: DOWN } },
      { name: '成交量', type: 'bar', xAxisIndex: 1, yAxisIndex: 1 },
    ],
  })
  const ro = new ResizeObserver(() => chart.resize())
  ro.observe(el.value)
  off = onTick(onLive)
  load()
  onBeforeUnmount(() => { ro.disconnect(); off(); chart.dispose() })
})

watch(() => [props.symbol, props.interval], load)
</script>

<style scoped>
.chart { width: 100%; height: 100%; min-height: 320px; }
</style>

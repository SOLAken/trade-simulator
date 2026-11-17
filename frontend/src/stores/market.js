import { defineStore } from 'pinia'
import { api } from '../api.js'

/**
 * 行情 store：一条 WebSocket 订阅全部股票，断线指数退避重连。
 * 组件通过 onTick 注册监听（K 线增量更新），quotes 供列表响应式渲染。
 */
export const useMarket = defineStore('market', {
  state: () => ({ symbols: [], quotes: {}, clock: null, connected: false }),
  actions: {
    async init() {
      const list = await api('/api/market/symbols')
      this.symbols = list.map(({ symbol, name }) => ({ symbol, name }))
      for (const s of list) if (s.quote) this.quotes[s.symbol] = s.quote
      this.refreshClock()
      setInterval(() => this.refreshClock(), 5000)
      this.connect()
    },
    async refreshClock() {
      this.clock = await api('/api/market/clock').catch(() => this.clock)
    },
    connect(retry = 0) {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(`${proto}://${location.host}/ws/market`)
      ws.onopen = () => {
        this.connected = true
        retry = 0
        ws.send(JSON.stringify({ op: 'sub', symbols: ['*'] }))
      }
      ws.onmessage = (e) => {
        const t = JSON.parse(e.data)
        this.quotes[t.symbol] = t
        listeners.forEach((fn) => fn(t))
      }
      ws.onclose = () => {
        this.connected = false
        setTimeout(() => this.connect(retry + 1), Math.min(30000, 1000 * 2 ** retry))
      }
    },
  },
})

const listeners = new Set()
export const onTick = (fn) => {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

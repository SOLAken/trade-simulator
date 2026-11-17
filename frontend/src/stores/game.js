import { defineStore } from 'pinia'
import { api } from '../api.js'

/** 当前比赛与个人交易数据。资产 2 秒轮询；下单/撤单后立即刷新。 */
export const useGame = defineStore('game', {
  state: () => ({
    contests: [],
    contestId: Number(localStorage.getItem('tradesim.contest') || 1),
    overview: null,
    orders: [],
    trades: [],
    rules: null,
  }),
  getters: {
    contest: (s) => s.contests.find((c) => c.id === s.contestId),
    joined() { return !!this.contest?.joined },
  },
  actions: {
    async init() {
      this.rules = await api('/api/market/rules').catch(() => null)
      await this.loadContests()
      setInterval(() => this.joined && this.refreshOverview(), 2000)
      setInterval(() => this.joined && this.refreshOrders(), 4000)
    },
    async loadContests() {
      this.contests = await api('/api/user/contests')
      if (!this.contest && this.contests.length) this.contestId = this.contests[0].id
      if (this.joined) await this.refreshAll()
    },
    async select(id) {
      this.contestId = id
      try { localStorage.setItem('tradesim.contest', String(id)) } catch { /* ignore */ }
      this.overview = null
      this.orders = []
      this.trades = []
      if (this.joined) await this.refreshAll()
    },
    async join() {
      await api(`/api/user/contests/${this.contestId}/join`, { method: 'POST' })
      await this.loadContests()
    },
    async create(name, initCashYuan, durationMinutes) {
      const id = await api('/api/user/contests', { method: 'POST', body: { name, initCashYuan, durationMinutes } })
      await this.loadContests()
      await this.select(id)
    },
    async refreshAll() {
      await Promise.all([this.refreshOverview(), this.refreshOrders()])
    },
    async refreshOverview() {
      this.overview = await api(`/api/account/overview?contestId=${this.contestId}`).catch(() => this.overview)
    },
    async refreshOrders() {
      const [o, t] = await Promise.all([
        api(`/api/trade/orders?contestId=${this.contestId}`),
        api(`/api/trade/trades?contestId=${this.contestId}`),
      ])
      this.orders = o
      this.trades = t
    },
    async place(order) {
      const res = await api('/api/trade/orders', {
        method: 'POST',
        body: { contestId: this.contestId, ...order },
        headers: { 'Idempotency-Key': crypto.randomUUID() },
      })
      await this.refreshAll()
      return res
    },
    async cancel(id) {
      const ok = await api(`/api/trade/orders/${id}`, { method: 'DELETE' })
      await this.refreshAll()
      return ok
    },
  },
})

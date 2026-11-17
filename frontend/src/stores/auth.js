import { defineStore } from 'pinia'

const KEY = 'tradesim.auth'
const load = () => {
  try { return JSON.parse(localStorage.getItem(KEY)) || {} } catch { return {} }
}

export const useAuth = defineStore('auth', {
  state: () => ({ token: '', userId: 0, nickname: '', ...load() }),
  actions: {
    set({ token, userId, nickname }) {
      Object.assign(this, { token, userId, nickname })
      try { localStorage.setItem(KEY, JSON.stringify({ token, userId, nickname })) } catch { /* 隐私模式 */ }
    },
    logout() {
      this.token = ''
      try { localStorage.removeItem(KEY) } catch { /* ignore */ }
    },
  },
})

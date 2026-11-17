import { defineStore } from 'pinia'

let seq = 0
export const useToast = defineStore('toast', {
  state: () => ({ items: [] }),
  actions: {
    show(text, type = 'info') {
      const id = ++seq
      this.items.push({ id, text, type })
      setTimeout(() => (this.items = this.items.filter((t) => t.id !== id)), 3000)
    },
    ok(text) { this.show(text, 'ok') },
    err(text) { this.show(text, 'err') },
  },
})

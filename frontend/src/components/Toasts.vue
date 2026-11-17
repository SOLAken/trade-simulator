<template>
  <div class="toasts">
    <div v-for="t in toast.items" :key="t.id" class="toast" :class="t.type">{{ t.text }}</div>
  </div>
</template>

<script setup>
import { useToast } from '../stores/toast.js'
const toast = useToast()
</script>

<style scoped>
.toasts { position: fixed; top: 16px; left: 50%; transform: translateX(-50%); z-index: 100; display: flex; flex-direction: column; gap: 8px; }
.toast { background: var(--panel-2); border: 1px solid var(--border); border-radius: 6px; padding: 8px 16px; box-shadow: 0 6px 20px rgb(0 0 0 / 0.4); }
.toast.ok { border-color: var(--down); }
.toast.err { border-color: var(--up); color: var(--up); }
</style>

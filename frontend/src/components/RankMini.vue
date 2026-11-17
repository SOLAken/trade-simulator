<template>
  <div class="rank">
    <div class="head">
      <b>实时排行</b>
      <span class="muted">{{ board?.participants ?? 0 }} 人参赛</span>
      <router-link to="/rank" class="more">完整榜单 →</router-link>
    </div>
    <ol>
      <li v-for="e in board?.top?.slice(0, 10) ?? []" :key="e.userId" :class="{ me: e.userId === auth.userId }">
        <span class="no" :class="'n' + e.rank">{{ e.rank }}</span>
        <span class="nick">{{ e.nickname }}</span>
        <span :class="e.returnPct > 0 ? 'up' : e.returnPct < 0 ? 'down' : ''">{{ e.returnPct > 0 ? '+' : '' }}{{ e.returnPct.toFixed(2) }}%</span>
      </li>
    </ol>
  </div>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { useAuth } from '../stores/auth.js'
import { useGame } from '../stores/game.js'
import { api } from '../api.js'

const auth = useAuth()
const game = useGame()
const board = ref(null)
let ws, closed = false

function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  ws = new WebSocket(`${proto}://${location.host}/ws/rank?token=${auth.token}`)
  ws.onopen = () => ws.send(JSON.stringify({ contestId: game.contestId }))
  ws.onmessage = (e) => (board.value = JSON.parse(e.data))
  ws.onclose = () => !closed && setTimeout(connect, 3000)
}

watch(() => game.contestId, async (id) => {
  board.value = await api(`/api/rank/${id}?top=10`).catch(() => null)
  if (ws?.readyState === 1) ws.send(JSON.stringify({ contestId: id }))
}, { immediate: true })

connect()
onBeforeUnmount(() => { closed = true; ws?.close() })
</script>

<style scoped>
.rank { padding: 12px 14px; }
.head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; }
.more { margin-left: auto; color: var(--accent); font-size: 12px; text-decoration: none; }
ol { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
li { display: grid; grid-template-columns: 24px 1fr auto; gap: 8px; padding: 4px 6px; border-radius: 4px; font-size: 13px; }
li.me { background: rgb(240 185 11 / 0.12); }
.no { color: var(--muted); text-align: center; }
.n1 { color: #f0b90b; font-weight: 700; } .n2 { color: #c0c7d0; font-weight: 700; } .n3 { color: #cd7f32; font-weight: 700; }
.nick { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>

<template>
  <div class="contest">
    <select :value="game.contestId" @change="game.select(Number($event.target.value))">
      <option v-for="c in game.contests" :key="c.id" :value="c.id">{{ c.name }}（{{ c.status }} · {{ c.members }}人）</option>
    </select>
    <button v-if="game.contest && !game.joined" class="primary" @click="join">报名参赛</button>
    <span v-else-if="game.contest" class="muted ends">{{ endsIn }}</span>
    <button @click="showCreate = !showCreate">+ 创建比赛</button>
    <form v-if="showCreate" class="panel create" @submit.prevent="create">
      <input v-model.trim="form.name" placeholder="比赛名称" required maxlength="30" />
      <label>初始资金（万元）<input v-model.number="form.wan" type="number" min="1" max="10000" /></label>
      <label>时长（分钟）<input v-model.number="form.minutes" type="number" min="5" max="43200" /></label>
      <button class="primary">创建并报名</button>
    </form>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useGame } from '../stores/game.js'
import { useToast } from '../stores/toast.js'

const game = useGame()
const toast = useToast()
const showCreate = ref(false)
const form = reactive({ name: '', wan: 100, minutes: 120 })

const endsIn = computed(() => {
  const end = game.contest?.endAt
  if (!end || end > Date.now() + 365 * 864e5) return '长期赛'
  const m = Math.max(0, Math.round((end - Date.now()) / 60000))
  return m > 0 ? `剩余 ${Math.floor(m / 60)} 小时 ${m % 60} 分` : '已结束'
})

async function join() {
  try { await game.join(); toast.ok('报名成功，初始资金已到账') } catch (e) { toast.err(e.message) }
}
async function create() {
  try {
    await game.create(form.name, form.wan * 10000, form.minutes)
    showCreate.value = false
    toast.ok('比赛已创建')
  } catch (e) { toast.err(e.message) }
}
</script>

<style scoped>
.contest { display: flex; align-items: center; gap: 8px; position: relative; flex-wrap: wrap; min-width: 0; }
.contest button { white-space: nowrap; }
select { width: auto; max-width: 260px; min-width: 0; padding: 5px 8px; }
@media (max-width: 760px) { .contest { width: 100%; } select { flex: 1; max-width: none; } }
.ends { font-size: 12px; }
.create { position: absolute; top: 40px; left: 0; z-index: 10; padding: 14px; display: flex; flex-direction: column; gap: 8px; width: 240px; box-shadow: 0 10px 30px rgb(0 0 0 / 0.5); }
.create label { font-size: 12px; color: var(--muted); display: flex; flex-direction: column; gap: 4px; }
</style>

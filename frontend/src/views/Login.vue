<template>
  <div class="wrap">
    <form class="panel card" @submit.prevent="submit">
      <h1>模拟交易大赛</h1>
      <p class="muted">虚拟资金 · 实时行情 · 实时排行</p>
      <div class="tabs">
        <button type="button" :class="{ on: mode === 'login' }" @click="mode = 'login'">登录</button>
        <button type="button" :class="{ on: mode === 'register' }" @click="mode = 'register'">注册</button>
      </div>
      <label>用户名<input v-model.trim="form.username" autocomplete="username" required /></label>
      <label v-if="mode === 'register'">昵称<input v-model.trim="form.nickname" maxlength="20" /></label>
      <label>密码<input v-model="form.password" type="password" autocomplete="current-password" required minlength="6" /></label>
      <p v-if="error" class="err">{{ error }}</p>
      <button class="primary" :disabled="loading">{{ mode === 'login' ? '登录' : '注册并登录' }}</button>
    </form>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api.js'
import { useAuth } from '../stores/auth.js'

const mode = ref('login')
const form = reactive({ username: '', password: '', nickname: '' })
const error = ref('')
const loading = ref(false)
const auth = useAuth()
const router = useRouter()

async function submit() {
  error.value = ''
  loading.value = true
  try {
    const data = await api(`/api/user/${mode.value}`, { method: 'POST', body: form })
    auth.set(data)
    router.push('/')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.wrap { min-height: 100vh; display: grid; place-items: center; padding: 16px; }
.card { width: 100%; max-width: 360px; padding: 28px; display: flex; flex-direction: column; gap: 14px; }
h1 { margin: 0; font-size: 22px; color: var(--accent); }
p { margin: 0; }
label { display: flex; flex-direction: column; gap: 6px; color: var(--muted); font-size: 13px; }
.tabs { display: flex; gap: 8px; }
.tabs button { flex: 1; }
.tabs .on { border-color: var(--accent); color: var(--accent); }
.err { color: var(--up); font-size: 13px; }
.primary { padding: 10px; margin-top: 4px; }
</style>

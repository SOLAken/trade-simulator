import { useAuth } from './stores/auth.js'
import router from './router.js'

/** 统一请求：带 JWT，解包 Result，401 跳登录。 */
export async function api(path, { method = 'GET', body, headers = {} } = {}) {
  const auth = useAuth()
  const res = await fetch(path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(auth.token ? { Authorization: `Bearer ${auth.token}` } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  const json = await res.json().catch(() => ({ code: res.status, msg: res.statusText }))
  if (res.status === 401) {
    auth.logout()
    router.push('/login')
  }
  if (json.code !== 0) throw Object.assign(new Error(json.msg || `请求失败 ${res.status}`), { code: json.code })
  return json.data
}

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期所有请求走网关，和生产一致（鉴权、限流都在网关）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
})

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src')
    }
  },
  server: {
    port: 3001,
    proxy: {
      // ⭐ 代理 /assistant/api 到 API Gateway
      '/assistant/api': {
        target: 'http://localhost:8081',
        changeOrigin: true
      },
      // ⭐ 兼容旧路径 /api (可选，用于逐步迁移)
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '/assistant/api')
      }
    }
  }
})

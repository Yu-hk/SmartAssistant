import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://localhost:3001',
        changeOrigin: true
      }
    }
  },
  css: {
    preprocessorOptions: {
      less: {
        javascriptEnabled: true
      }
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          // ⭐ React 核心库单独打包（利用浏览器缓存）
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          // ⭐ TDesign UI 组件库单独打包
          'vendor-tdesign': ['tdesign-react', '@tdesign-react/chat', '@tdesign-react/aigc', 'tdesign-icons-react'],
          // ⭐ 图标库单独打包（减少主 chunk 体积）
          'vendor-icons': ['lucide-react'],
        }
      }
    },
    chunkSizeWarningLimit: 600
  }
});

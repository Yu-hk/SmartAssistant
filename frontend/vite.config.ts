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
        target: 'http://localhost:8081',
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
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined;
          if (id.includes('@tdesign-react/aigc')) return 'vendor-tdesign-aigc';
          if (id.includes('@tdesign-react/chat')) return 'vendor-tdesign-chat';
          if (id.includes('tdesign-icons-react')) return 'vendor-tdesign-icons';
          if (id.includes('tdesign-react')) return 'vendor-tdesign-core';
          if (id.includes('lucide-react')) return 'vendor-icons';
          if (id.includes('react-dom') || id.includes('react-router-dom')
              || /[/\\]react[/\\]/.test(id)) return 'vendor-react';
          return undefined;
        }
      }
    },
    chunkSizeWarningLimit: 600
  }
});

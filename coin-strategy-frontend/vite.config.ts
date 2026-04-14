import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

const backendTarget = process.env.VITE_BACKEND_TARGET || 'http://127.0.0.1:8081'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/strategy/api': {
        target: backendTarget,
        changeOrigin: true,
      },
    },
  },
})

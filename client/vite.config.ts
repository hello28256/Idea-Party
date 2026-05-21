import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'

const env = loadEnv('', process.cwd(), '')

export default defineConfig({
  plugins: [
    vue(),
    tailwindcss()
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, './src')
    }
  },
  server: {
    port: Number(env.VITE_PORT) || 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: `http://127.0.0.1:${env.VITE_SERVER_PROXY_PORT || '8082'}`,
        changeOrigin: true
      },
      '/uploads': {
        target: `http://127.0.0.1:${env.VITE_SERVER_PROXY_PORT || '8082'}`,
        changeOrigin: true
      }
    }
  }
})

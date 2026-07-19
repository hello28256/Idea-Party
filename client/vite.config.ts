import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import electron from 'vite-plugin-electron'
import renderer from 'vite-plugin-electron-renderer'
import { resolve } from 'path'

const env = loadEnv('', process.cwd(), '')

export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
    electron([
      {
        // 主进程入口
        entry: 'electron/main/index.ts',
        vite: {
          build: {
            outDir: 'dist-electron/main',
            rollupOptions: {
              external: ['electron']
            }
          }
        }
      },
      {
        // Preload 脚本
        entry: 'electron/preload/index.ts',
        onstart(options) {
          // 监听 preload 重启(可选,先注释掉,debug 用)
          // options.reload()
        },
        vite: {
          build: {
            outDir: 'dist-electron/preload',
            rollupOptions: {
              external: ['electron']
            }
          }
        }
      }
    ]),
    renderer()
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, './src')
    }
  },
  // 修复 node_modules/.vite/ 被 root 占用的遗留问题:
  // force: true 跳过 metadata.json unlink,直接重新预构建
  // cacheDir 重定向到能写的目录,避免依赖 root 拥有的子目录
  optimizeDeps: {
    force: true
  },
  cacheDir: resolve(__dirname, '.vite-cache'),
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
      },
      '/ws': {
        target: `ws://127.0.0.1:${env.VITE_SERVER_PROXY_PORT || '8082'}`,
        ws: true,
        changeOrigin: true
      }
    }
  }
})

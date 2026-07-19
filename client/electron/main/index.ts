import { app, BrowserWindow, shell, Menu, nativeTheme } from 'electron'
import { join } from 'node:path'
import { electronApp, optimizer, is } from '@electron-toolkit/utils'

// app.getAppPath() 返回应用根目录(开发时是 client/,打包后是 .app/Contents/Resources/app/)
// 路径都基于这个算,不依赖 __dirname,避开 ESM 兼容问题。
const APP_ROOT = app.getAppPath()

/**
 * 主进程入口。
 *
 * 开发模式:连接 Vite dev server(HMR 直接走 Vue 现有流程)。
 * 生产模式:加载本地 dist/index.html。
 *
 * 后端地址由渲染进程读 VITE_SERVER_URL(已通过 env 注入),
 * 桌面端不绑死后端 host——连云或连本地 jar 由用户在 UI 里切换。
 */

// vite-plugin-electron 注入的 dev server URL,通常是 http://localhost:5173
// (注意:不是 ELECTRON_RENDERER_URL,那个是 electron-vite 的命名)
const SERVER_URL_ENV = process.env['VITE_DEV_SERVER_URL']
// dev 模式:!app.isPackaged(没打包的版本就是 dev)
const isDev = !app.isPackaged
// 模块顶层,createWindow 和 buildMenu 共用
const isMac = process.platform === 'darwin'

function createWindow(): void {
  const win = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 960,
    minHeight: 600,
    // 先 show:false,等 ready-to-show 触发后再显示,避免白屏闪一下
    show: false,
    // macOS:标题栏融入内容区,视觉更干净
    // Windows/Linux:不设(默认有标准标题栏)
    ...(isMac && { titleBarStyle: 'hiddenInset' as const }),
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#0f172a' : '#ffffff',
    webPreferences: {
      preload: join(APP_ROOT, 'dist-electron/preload/index.js'),
      sandbox: false, // 需要在 preload 里用 Node API,不能开 sandbox
      contextIsolation: true,
      nodeIntegration: false
    }
  })

  // ready-to-show 偶尔不触发(尤其 loadFile 路径错误时),加个保险:
  // 5 秒后无论如何强制显示,方便看到错误而不是空白
  const forceShow = setTimeout(() => win.show(), 5000)
  win.on('ready-to-show', () => {
    clearTimeout(forceShow)
    win.show()
  })

  // 调试:把页面加载错误打到主进程 stdout
  win.webContents.on('did-fail-load', (_e, code, desc, url) => {
    console.error(`[main] did-fail-load code=${code} desc=${desc} url=${url}`)
  })
  win.webContents.on('console-message', (_e, level, msg, line, source) => {
    console.log(`[renderer:${level}] ${msg} (${source}:${line})`)
  })

  // 外链在系统浏览器打开,不在 Electron 内跳
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })

  if (isDev) {
    // 优先用 vite-plugin-electron 注入的 URL,
    // 兜底用 http://localhost:5173(必须跟 vite.config.ts 里 server.port 一致)
    const devUrl = SERVER_URL_ENV || 'http://localhost:5173'
    console.log(`[main] dev mode, loading ${devUrl}`)
    win.loadURL(devUrl)
  } else {
    // 生产模式:renderer 的 index.html 由 vite 打到 dist/ 下
    const indexHtml = join(APP_ROOT, 'dist/index.html')
    console.log(`[main] prod mode, loading file ${indexHtml}`)
    win.loadFile(indexHtml)
  }
}

function buildMenu(): void {
  // isMac 已在 createWindow 里声明过,直接用
  const template: Electron.MenuItemConstructorOptions[] = [
    ...(isMac
      ? [
          {
            label: app.name,
            submenu: [
              { role: 'about' as const },
              { type: 'separator' as const },
              { role: 'services' as const },
              { type: 'separator' as const },
              { role: 'hide' as const },
              { role: 'hideOthers' as const },
              { role: 'unhide' as const },
              { type: 'separator' as const },
              { role: 'quit' as const }
            ]
          }
        ]
      : []),
    {
      label: 'Edit',
      submenu: [
        { role: 'undo' },
        { role: 'redo' },
        { type: 'separator' },
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        { role: 'selectAll' }
      ]
    },
    {
      label: 'View',
      submenu: [
        { role: 'reload' },
        { role: 'toggleDevTools' },
        { type: 'separator' },
        { role: 'togglefullscreen' }
      ]
    },
    {
      label: 'Window',
      submenu: [{ role: 'minimize' }, { role: 'close' }]
    }
  ]
  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}

app.whenReady().then(() => {
  electronApp.setAppUserModelId('com.ideaparty.client')

  app.on('browser-window-created', (_, window) => {
    optimizer.watchWindowShortcuts(window)
  })

  buildMenu()
  createWindow()

  app.on('activate', () => {
    // macOS:点 dock 图标且无窗口时,重建窗口
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  // macOS 习惯:全部关闭后仍驻留 dock
  if (process.platform !== 'darwin') app.quit()
})

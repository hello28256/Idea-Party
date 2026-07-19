import { contextBridge, ipcRenderer } from 'electron'
import { electronAPI } from '@electron-toolkit/preload'

/**
 * Preload 脚本:在隔离上下文里暴露受控 API 给渲染进程。
 *
 * 渲染进程通过 window.electronAPI 访问,只能调用这里显式列出的方法,
 * 拿不到 Node API,符合 contextIsolation + sandbox 安全实践。
 *
 * 目前只暴露:
 *   - platform: 'darwin' | 'win32' | 'linux',用于 macOS 专属样式分支
 *   - isDesktop: true,渲染进程用来判断走桌面版逻辑(隐藏"部署到云"等按钮)
 *
 * 业务 API(axios / socket.io)由渲染进程直接走 HTTP/WS,不绕 IPC——更简单更快。
 */

if (process.contextIsolated) {
  try {
    contextBridge.exposeInMainWorld('electronAPI', {
      ...electronAPI,
      platform: process.platform,
      isDesktop: true,
      // 占位:后续要加原生能力(截图、文件对话框、菜单事件)在这里加 onXxx / doXxx
      openExternal: (url: string) => ipcRenderer.invoke('open-external', url)
    })
  } catch (error) {
    console.error('[preload] contextBridge failed', error)
  }
} else {
  // 非隔离模式(理论上不该走到这,留个兜底)
  // @ts-ignore
  window.electronAPI = {
    platform: process.platform,
    isDesktop: true
  }
}

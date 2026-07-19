# Idea Party macOS 桌面版

> AI 多角色聊天室的 macOS 原生壳。Vue 业务代码 0 修改,只多了一层 Electron 容器。

## 快速开始(开发模式)

```bash
cd client
npm install --legacy-peer-deps   # 项目有遗留 peer dep 冲突,需要这个 flag
npm run electron:dev             # 同时启 Vite + Electron 主进程,自动 HMR
```

启动后会自动弹出窗口。如果窗口空白,看下 console(菜单 → View → Toggle DevTools)。

## 打 DMG

```bash
npm run electron:build           # 出 release/0.1.0/Idea Party-0.1.0-arm64.dmg
npm run electron:build:dir       # 只出 .app(不打包 dmg,调试快)
```

第一次打 dmg 会比较慢,要下载 Electron 二进制(~150MB)。

## 首次打开 ⚠️ Gatekeeper 拦截

因为没买 Apple Developer 账号($99/年),没做代码签名和公证。macOS 会拦截:

```
"Idea Party" 无法打开,因为无法确认开发者的身份。
```

**解决(只需做一次)**:

1. 在 Finder 找到 `Idea Party.app`
2. **右键**(或按住 Control 点击)→ **打开**
3. 弹出确认框,再点一次 **打开**
4. 之后就能正常双击了

如果之前点过"移到废纸篓",需要去 **系统设置 → 隐私与安全性** 页面底部点 **"仍要打开"**。

## 后端怎么连?

桌面端不绑后端,有两种模式,通过 `client/.env` 切换:

```bash
# 默认:走你部署好的公网 API(改 VITE_API_BASE_URL)
VITE_API_BASE_URL=https://your-api.ideaparty.com

# 本地:启动 server jar,桌面端连 localhost
VITE_API_BASE_URL=http://localhost:8080
```

重启桌面端生效。

> 想让桌面端"自带后端"需要把 server jar 打进 .app,后续 Phase 4 再做。

## 目录结构

```
client/
├── electron/                # 桌面端专属代码
│   ├── main/index.ts        # 主进程(窗口、菜单、生命周期)
│   └── preload/index.ts     # 安全桥(contextBridge 暴露 API)
├── src/                     # Vue 业务代码(桌面端 / Web 共用,无修改)
├── electron-builder.yml     # 打包配置(签名策略在这里改)
├── tsconfig.electron.json   # electron 进程的 TS 配置
└── vite.config.ts           # 已注入 vite-plugin-electron 插件
```

## 已知限制

| 问题 | 原因 | 何时解决 |
|------|------|----------|
| 首次打开要右键 | 没签名 + 没公证 | 付费 Apple 账号后开启 notarize |
| 自动更新没接 | electron-updater 需 HTTPS 托管 | Phase 3 |
| 深色模式不自动跟随系统 | 需要监听 nativeTheme | Phase 2 |
| 后端要自己起 | server jar 没内嵌 | Phase 4 |

## 调试技巧

- **开 DevTools**:菜单 → View → Toggle Developer Tools(或 Cmd+Option+I)
- **看主进程日志**:终端里 `npm run electron:dev` 的 stdout
- **preload 报错**:DevTools Console 里搜 `[preload]`
- **窗口位置/大小重置**:删 `~/Library/Application Support/Idea Party/config.json`

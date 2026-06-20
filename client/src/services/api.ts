import type { Character, Room, Message, CreateRoomRequest, SendMessageRequest } from '../types';

// 前端业务侧 REST API 客户端（与 src/api/auth.ts 中的 axios 实例职责不同）：
// - 名称同为 `api`，但本文件直接基于浏览器 fetch，业务调用方（messages.ts 等）按需 import。
// - baseURL：固定走 `/api` 前缀，由 Vite dev server 代理到后端；
//   生产环境由 Nginx 等反向代理转发，前端无需感知真实后端域名，避免 CORS 与硬编码地址。
// - 错误处理：非 2xx 统一抛 `Error('Failed to ...')`，由调用方（store/composable）自行 try/catch；
//   此处不做拦截器/JWT 注入——鉴权信息由浏览器 Cookie 自动携带，或由调用方按需加 header。
// - 之所以单独存在：早期模块先于 axios 体系建立，遗留的 ChatRoomPanel 仍消费此 `api.getMessages`；
//   新增接口优先在 src/api/* 下用 axios 风格封装，避免两套风格无限蔓延。
//
// 通过相对路径走 Vite dev server 的代理，避免 CORS 与硬编码后端地址
// 这样生产环境由反向代理（Nginx 等）转发，前端无需感知真实后端域名
const API_BASE = '/api';

/**
 * 前端 REST API 客户端。
 * 统一封装对后端的 fetch 调用，供 Pinia store / 视图组件复用；
 * 鉴权信息（JWT）由浏览器自动通过 Cookie 或拦截器携带，这里只关心业务路径。
 */
export const api = {
  /**
   * 拉取全部角色列表。
   * HTTP GET /characters。
   * 列表接口返回全集，由调用方（store）在本地按需过滤，避免后端为每个筛选条件暴露独立 endpoint。
   * 调用方：useCharacterStore 初始化加载。
   */
  // 列表接口返回全集，由调用方（store）在本地按需过滤，避免后端为每个筛选条件暴露独立 endpoint
  async getCharacters(): Promise<Character[]> {
    const res = await fetch(`${API_BASE}/characters`);
    if (!res.ok) throw new Error('Failed to fetch characters');
    return res.json();
  },

  /**
   * 拉取单个角色详情。
   * HTTP GET /characters/{id}。
   * 单角色详情：用于角色配置/编辑面板回显，id 由路由参数保证非空。
   * 调用方：EditCharacterModal、CharacterDetailModal。
   */
  // 单角色详情：用于角色配置/编辑面板回显，id 由路由参数保证非空
  async getCharacter(id: string): Promise<Character> {
    const res = await fetch(`${API_BASE}/characters/${id}`);
    if (!res.ok) throw new Error('Failed to fetch character');
    return res.json();
  },

  /**
   * 创建聊天室。
   * HTTP POST /rooms（body: CreateRoomRequest）。
   * 创建房间：服务端会异步触发角色 prompt 生成与首次 Moderator 编排，
   * 此处只负责同步落库并返回 Room 实体，调用方应监听后续的 socket 事件来获取 AI 响应。
   * 调用方：CreateRoomModal 提交。
   */
  // 创建房间：服务端会异步触发角色 prompt 生成与首次 Moderator 编排，
  // 此处只负责同步落库并返回 Room 实体，调用方应监听后续的 socket 事件来获取 AI 响应
  async createRoom(data: CreateRoomRequest): Promise<Room> {
    const res = await fetch(`${API_BASE}/rooms`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    if (!res.ok) throw new Error('Failed to create room');
    return res.json();
  },

  /**
   * 拉取房间元信息（成员与角色），不包含消息历史。
   * HTTP GET /rooms/{id}。
   * 房间元信息：包含参与者列表与系统提示；不包含消息历史（消息历史走单独接口以便分页/懒加载）。
   * 调用方：ChatRoomView 挂载。
   */
  // 房间元信息：包含参与者列表与系统提示；不包含消息历史（消息历史走单独接口以便分页/懒加载）
  async getRoom(id: string): Promise<Room> {
    const res = await fetch(`${API_BASE}/rooms/${id}`);
    if (!res.ok) throw new Error('Failed to fetch room');
    return res.json();
  },

  /**
   * 拉取房间历史消息。
   * HTTP GET /rooms/{roomId}/messages。
   * 进入聊天室时一次性加载，实时增量由 socket 推送，避免轮询。
   * 调用方：ChatRoomView 挂载（被 src/api/messages.ts 转桥为 ChatMessage）。
   */
  // 拉取历史消息：进入聊天室时一次性加载，实时增量由 socket 推送，避免轮询
  async getMessages(roomId: string): Promise<Message[]> {
    const res = await fetch(`${API_BASE}/rooms/${roomId}/messages`);
    if (!res.ok) throw new Error('Failed to fetch messages');
    return res.json();
  },

  /**
   * 提交用户消息。
   * HTTP POST /rooms/{roomId}/messages（body: SendMessageRequest）。
   * 发送用户消息：仅提交用户输入；AI 的多角色回复由 Moderator 编排后通过 socket 流式回推，
   * 此接口同步返回的是用户消息本身的持久化实体（用于乐观更新回填 id/timestamp）。
   * 调用方：ChatInput 提交。
   */
  // 发送用户消息：仅提交用户输入；AI 的多角色回复由 Moderator 编排后通过 socket 流式回推，
  // 此接口同步返回的是用户消息本身的持久化实体（用于乐观更新回填 id/timestamp）
  async sendMessage(roomId: string, data: SendMessageRequest): Promise<Message> {
    const res = await fetch(`${API_BASE}/rooms/${roomId}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    if (!res.ok) throw new Error('Failed to send message');
    return res.json();
  },
};

// 重新导出类型，便于调用方从统一入口引用领域模型，避免分散的 import 路径
export type { Character, Room, Message, CreateRoomRequest, SendMessageRequest };

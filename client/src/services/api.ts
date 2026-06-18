import type { Character, Room, Message, CreateRoomRequest, SendMessageRequest } from '../types';

// 通过相对路径走 Vite dev server 的代理，避免 CORS 与硬编码后端地址
// 这样生产环境由反向代理（Nginx 等）转发，前端无需感知真实后端域名
const API_BASE = '/api';

/**
 * 前端 REST API 客户端。
 * 统一封装对后端的 fetch 调用，供 Pinia store / 视图组件复用；
 * 鉴权信息（JWT）由浏览器自动通过 Cookie 或拦截器携带，这里只关心业务路径。
 */
export const api = {
  // 列表接口返回全集，由调用方（store）在本地按需过滤，避免后端为每个筛选条件暴露独立 endpoint
  async getCharacters(): Promise<Character[]> {
    const res = await fetch(`${API_BASE}/characters`);
    if (!res.ok) throw new Error('Failed to fetch characters');
    return res.json();
  },

  // 单角色详情：用于角色配置/编辑面板回显，id 由路由参数保证非空
  async getCharacter(id: string): Promise<Character> {
    const res = await fetch(`${API_BASE}/characters/${id}`);
    if (!res.ok) throw new Error('Failed to fetch character');
    return res.json();
  },

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

  // 房间元信息：包含参与者列表与系统提示；不包含消息历史（消息历史走单独接口以便分页/懒加载）
  async getRoom(id: string): Promise<Room> {
    const res = await fetch(`${API_BASE}/rooms/${id}`);
    if (!res.ok) throw new Error('Failed to fetch room');
    return res.json();
  },

  // 拉取历史消息：进入聊天室时一次性加载，实时增量由 socket 推送，避免轮询
  async getMessages(roomId: string): Promise<Message[]> {
    const res = await fetch(`${API_BASE}/rooms/${roomId}/messages`);
    if (!res.ok) throw new Error('Failed to fetch messages');
    return res.json();
  },

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

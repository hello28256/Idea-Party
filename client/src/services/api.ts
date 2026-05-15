import type { Character, Room, Message, CreateRoomRequest, SendMessageRequest } from '../types';

const API_BASE = '/api';

export const api = {
  async getCharacters(): Promise<Character[]> {
    const res = await fetch(`${API_BASE}/characters`);
    if (!res.ok) throw new Error('Failed to fetch characters');
    return res.json();
  },

  async getCharacter(id: string): Promise<Character> {
    const res = await fetch(`${API_BASE}/characters/${id}`);
    if (!res.ok) throw new Error('Failed to fetch character');
    return res.json();
  },

  async createRoom(data: CreateRoomRequest): Promise<Room> {
    const res = await fetch(`${API_BASE}/rooms`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    if (!res.ok) throw new Error('Failed to create room');
    return res.json();
  },

  async getRoom(id: string): Promise<Room> {
    const res = await fetch(`${API_BASE}/rooms/${id}`);
    if (!res.ok) throw new Error('Failed to fetch room');
    return res.json();
  },

  async getMessages(roomId: string): Promise<Message[]> {
    const res = await fetch(`${API_BASE}/rooms/${roomId}/messages`);
    if (!res.ok) throw new Error('Failed to fetch messages');
    return res.json();
  },

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

export type { Character, Room, Message, CreateRoomRequest, SendMessageRequest };

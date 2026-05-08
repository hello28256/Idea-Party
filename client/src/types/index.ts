export interface Character {
  id: string;
  name: string;
  avatar: string;
  description: string;
  expertise: string[];
  era: string;
  speakingStyle: string;
}

export interface Room {
  id: string;
  name: string;
  theme: string;
  createdAt: string;
  characters: Character[];
}

export interface Message {
  id: string;
  content: string;
  role: 'user' | 'character' | 'system';
  characterId?: string;
  characterName?: string;
  characterAvatar?: string;
  roomId: string;
  createdAt: string;
}

export interface CreateRoomRequest {
  name: string;
  theme?: string;
  characterIds: string[];
}

export interface SendMessageRequest {
  content: string;
  role: 'user' | 'character';
  characterId?: string;
}

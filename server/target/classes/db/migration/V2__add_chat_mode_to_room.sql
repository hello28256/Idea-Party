-- Add chat mode and discussion settings to room
-- V2__add_chat_mode_to_room.sql

ALTER TABLE rooms
ADD COLUMN chat_mode VARCHAR(20) DEFAULT 'dialogue' COMMENT 'dialogue|discussion',
ADD COLUMN max_discussion_rounds INT DEFAULT 5 COMMENT 'Max rounds for discussion mode';

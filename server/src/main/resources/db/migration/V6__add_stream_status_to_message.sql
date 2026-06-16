-- Add stream_status column to messages for LLM generation health tracking
-- V6__add_stream_status_to_message.sql

ALTER TABLE messages
  ADD COLUMN stream_status VARCHAR(16) NULL DEFAULT 'COMPLETE'
  COMMENT 'COMPLETE | EMPTY | FAILED';

-- Per-AI-message observation row. One row per AI message, upserted whenever
-- feedback status changes. Lets the admin overview show ALL AI messages
-- (not just ones the user has explicitly rated).
-- V5__create_message_observations.sql

CREATE TABLE IF NOT EXISTS message_observations (
  message_id        VARCHAR(36)  NOT NULL,
  room_id           CHAR(36)     NOT NULL,
  character_id      CHAR(36)     NULL,
  feedback_count    INT          NOT NULL DEFAULT 0 COMMENT 'Users who have rated this message',
  like_count        INT          NOT NULL DEFAULT 0,
  dislike_count     INT          NOT NULL DEFAULT 0,
  last_feedback_at  DATETIME(6)  NULL,
  created_at        DATETIME(6)  NOT NULL,
  updated_at        DATETIME(6)  NOT NULL,
  PRIMARY KEY (message_id),
  KEY idx_room_created (room_id, created_at DESC),
  KEY idx_char_created (character_id, created_at DESC),
  KEY idx_feedback_count (feedback_count, created_at DESC),
  CONSTRAINT fk_obs_msg FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-AI-message feedback observation rollup';

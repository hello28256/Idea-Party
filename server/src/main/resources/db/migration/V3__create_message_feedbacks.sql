-- User feedback (like/dislike + category + comment) on AI messages
-- V3__create_message_feedbacks.sql

CREATE TABLE IF NOT EXISTS message_feedbacks (
  id            CHAR(36)     NOT NULL,
  message_id    VARCHAR(36)  NOT NULL COMMENT 'FK -> messages.id',
  user_id       CHAR(36)     NOT NULL COMMENT 'FK -> users.id',
  type          VARCHAR(16)  NOT NULL COMMENT 'LIKE|DISLIKE',
  category      VARCHAR(32)  NULL     COMMENT 'IRRELEVANT|INACCURATE|UNSAFE|STYLE_BAD|OTHER (DISLIKE only)',
  comment       TEXT         NULL     COMMENT 'Optional user-supplied reason (max 1000 chars)',
  created_at    DATETIME(6)  NOT NULL,
  updated_at    DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_msg_user (message_id, user_id),
  KEY idx_user_created (user_id, created_at),
  KEY idx_category_created (category, created_at),
  CONSTRAINT fk_fb_msg  FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE CASCADE,
  CONSTRAINT fk_fb_user FOREIGN KEY (user_id)    REFERENCES users    (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User like/dislike feedback on AI messages';

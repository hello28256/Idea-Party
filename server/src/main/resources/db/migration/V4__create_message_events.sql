-- Implicit message events (rewrite, copy, read-complete, edit) for feedback signals
-- V4__create_message_events.sql

CREATE TABLE IF NOT EXISTS message_events (
  id            CHAR(36)     NOT NULL,
  message_id    VARCHAR(36)  NOT NULL COMMENT 'FK -> messages.id',
  user_id       CHAR(36)     NOT NULL COMMENT 'FK -> users.id',
  event_type    VARCHAR(32)  NOT NULL COMMENT 'REWRITE|COPY|READ_COMPLETE|EDIT|FOCUS',
  dwell_ms      INT          NULL     COMMENT 'For READ_COMPLETE / FOCUS: time spent in ms',
  metadata      TEXT         NULL     COMMENT 'Optional JSON blob for event-specific data',
  created_at    DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_msg_event (message_id, event_type),
  KEY idx_user_event (user_id, event_type, created_at),
  CONSTRAINT fk_evt_msg  FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE CASCADE,
  CONSTRAINT fk_evt_user FOREIGN KEY (user_id)    REFERENCES users    (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Implicit user events for AI message feedback';

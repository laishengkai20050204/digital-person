CREATE TABLE person_conversation_episode (
    episode_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    person_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_start_turn_id BIGINT UNSIGNED NOT NULL,
    source_end_turn_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(200) NOT NULL,
    summary_text TEXT NOT NULL,
    event_type VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    participants_text TEXT NOT NULL,
    emotions_text TEXT NOT NULL,
    outcome_text TEXT NOT NULL,
    importance DECIMAL(4, 3) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    ended_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (episode_id),
    UNIQUE KEY uk_person_conversation_episode_source_title (
        person_id,
        source_start_turn_id,
        source_end_turn_id,
        title
    ),
    KEY idx_person_conversation_episode_recent (
        person_id,
        ended_at DESC,
        episode_id DESC
    ),
    KEY idx_person_conversation_episode_importance (
        person_id,
        importance DESC,
        ended_at DESC
    ),
    CONSTRAINT fk_person_conversation_episode_person
        FOREIGN KEY (person_id)
        REFERENCES digital_person (person_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_person_conversation_episode_source_range
        CHECK (source_start_turn_id > 0 AND source_end_turn_id >= source_start_turn_id),
    CONSTRAINT chk_person_conversation_episode_importance
        CHECK (importance >= 0.0 AND importance <= 1.0),
    CONSTRAINT chk_person_conversation_episode_time_range
        CHECK (ended_at >= started_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

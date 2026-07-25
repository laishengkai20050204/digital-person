CREATE TABLE person_conversation_turn (
    conversation_turn_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    person_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(16) NOT NULL,
    turn_text MEDIUMTEXT NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (conversation_turn_id),
    CONSTRAINT fk_person_conversation_turn_person
        FOREIGN KEY (person_id)
        REFERENCES digital_person (person_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_person_conversation_turn_role
        CHECK (role IN ('USER', 'PERSON', 'SYSTEM')),
    INDEX idx_person_conversation_turn_recent (
        person_id,
        conversation_turn_id DESC
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

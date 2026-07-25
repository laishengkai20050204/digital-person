CREATE TABLE person_conversation_summary (
    person_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    summary_text MEDIUMTEXT NOT NULL,
    covered_through_turn_id BIGINT UNSIGNED NOT NULL,
    summarized_turn_count BIGINT UNSIGNED NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (person_id),
    CONSTRAINT fk_person_conversation_summary_person
        FOREIGN KEY (person_id)
        REFERENCES digital_person (person_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_person_conversation_summary_covered_positive
        CHECK (covered_through_turn_id > 0),
    CONSTRAINT chk_person_conversation_summary_count_positive
        CHECK (summarized_turn_count > 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

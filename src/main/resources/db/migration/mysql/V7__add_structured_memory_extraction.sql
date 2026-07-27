CREATE TABLE person_structured_memory_extraction_cursor (
    person_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    covered_through_turn_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    processed_turn_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    last_entity_count INT UNSIGNED NOT NULL DEFAULT 0,
    last_fact_count INT UNSIGNED NOT NULL DEFAULT 0,
    last_completed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (person_id),
    CONSTRAINT fk_structured_memory_extraction_person
        FOREIGN KEY (person_id)
        REFERENCES digital_person (person_id)
        ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE person_memory_fact_evidence (
    fact_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_start_turn_id BIGINT UNSIGNED NOT NULL,
    source_end_turn_id BIGINT UNSIGNED NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (fact_id, source_start_turn_id, source_end_turn_id),
    KEY idx_memory_fact_evidence_source (
        source_start_turn_id,
        source_end_turn_id,
        fact_id
    ),
    CONSTRAINT fk_memory_fact_evidence_fact
        FOREIGN KEY (fact_id)
        REFERENCES person_memory_fact (fact_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_memory_fact_evidence_range
        CHECK (source_end_turn_id >= source_start_turn_id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

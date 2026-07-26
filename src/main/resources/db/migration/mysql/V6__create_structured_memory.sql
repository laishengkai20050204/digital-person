CREATE TABLE memory_entity (
    entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    person_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonical_name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    description TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (entity_id),
    UNIQUE KEY uk_memory_entity_canonical (
        person_id,
        entity_type,
        normalized_name
    ),
    KEY idx_memory_entity_person_type (
        person_id,
        entity_type,
        updated_at DESC
    ),
    CONSTRAINT fk_memory_entity_person
        FOREIGN KEY (person_id)
        REFERENCES digital_person (person_id)
        ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE memory_entity_alias (
    alias_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    alias_text VARCHAR(255) NOT NULL,
    normalized_alias VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    confidence DECIMAL(4, 3) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (alias_id),
    UNIQUE KEY uk_memory_entity_alias (
        entity_id,
        normalized_alias
    ),
    KEY idx_memory_entity_alias_lookup (
        normalized_alias,
        confidence DESC,
        entity_id
    ),
    CONSTRAINT fk_memory_entity_alias_entity
        FOREIGN KEY (entity_id)
        REFERENCES memory_entity (entity_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_memory_entity_alias_confidence
        CHECK (confidence >= 0.0 AND confidence <= 1.0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE person_memory_fact (
    fact_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fact_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    person_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    memory_section VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    domain VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    predicate VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_entity_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    text_value VARCHAR(1000) NOT NULL,
    statement_text TEXT NOT NULL,
    confidence DECIMAL(4, 3) NOT NULL,
    importance DECIMAL(4, 3) NOT NULL,
    evidence_count INT UNSIGNED NOT NULL DEFAULT 1,
    valid_from TIMESTAMP(6) NULL,
    valid_until TIMESTAMP(6) NULL,
    last_confirmed_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (fact_id),
    UNIQUE KEY uk_person_memory_fact_key (
        person_id,
        fact_key
    ),
    KEY idx_person_memory_fact_section (
        person_id,
        memory_section,
        importance DESC,
        last_confirmed_at DESC
    ),
    KEY idx_person_memory_fact_domain (
        person_id,
        domain,
        predicate,
        updated_at DESC
    ),
    KEY idx_person_memory_fact_subject (
        person_id,
        subject_entity_id,
        predicate
    ),
    KEY idx_person_memory_fact_object (
        person_id,
        object_entity_id,
        predicate
    ),
    KEY idx_person_memory_fact_validity (
        person_id,
        valid_from,
        valid_until
    ),
    CONSTRAINT fk_person_memory_fact_person
        FOREIGN KEY (person_id)
        REFERENCES digital_person (person_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_person_memory_fact_subject
        FOREIGN KEY (subject_entity_id)
        REFERENCES memory_entity (entity_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_person_memory_fact_object
        FOREIGN KEY (object_entity_id)
        REFERENCES memory_entity (entity_id)
        ON DELETE SET NULL,
    CONSTRAINT chk_person_memory_fact_confidence
        CHECK (confidence >= 0.0 AND confidence <= 1.0),
    CONSTRAINT chk_person_memory_fact_importance
        CHECK (importance >= 0.0 AND importance <= 1.0),
    CONSTRAINT chk_person_memory_fact_evidence
        CHECK (evidence_count > 0),
    CONSTRAINT chk_person_memory_fact_validity
        CHECK (
            valid_from IS NULL
            OR valid_until IS NULL
            OR valid_until > valid_from
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

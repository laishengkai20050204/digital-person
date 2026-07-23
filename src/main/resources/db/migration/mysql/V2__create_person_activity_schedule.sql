CREATE TABLE person_activity_schedule (
    person_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_review_at TIMESTAMP(6) NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until TIMESTAMP(6) NULL,
    failure_count INT UNSIGNED NOT NULL DEFAULT 0,
    last_error_type VARCHAR(128) NULL,
    last_started_at TIMESTAMP(6) NULL,
    last_completed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (person_id),
    KEY idx_person_activity_schedule_due (
        enabled,
        next_review_at,
        lease_until
    ),
    CONSTRAINT fk_person_activity_schedule_person
        FOREIGN KEY (person_id)
        REFERENCES digital_person (person_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_person_activity_schedule_lease_pair CHECK (
        (lease_token IS NULL AND lease_until IS NULL)
        OR (lease_token IS NOT NULL AND lease_until IS NOT NULL)
    )
) ENGINE = InnoDB;
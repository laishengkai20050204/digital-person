CREATE TABLE digital_person (
    person_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL,
    aggregate_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (person_id),
    CONSTRAINT chk_digital_person_version_nonnegative CHECK (version >= 0)
) ENGINE = InnoDB;

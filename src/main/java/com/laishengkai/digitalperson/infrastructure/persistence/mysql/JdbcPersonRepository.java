package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Optional;

/** MySQL/InnoDB adapter that stores one complete aggregate as a JSON document. */
public final class JdbcPersonRepository implements PersonRepository {
    private static final String FIND_SQL = """
            SELECT version, aggregate_json
            FROM digital_person
            WHERE person_id = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO digital_person (
                person_id,
                version,
                aggregate_json,
                created_at,
                updated_at
            ) VALUES (?, 0, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            """;

    private static final String SAVE_SQL = """
            UPDATE digital_person
            SET aggregate_json = ?,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE person_id = ?
              AND version = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PersonAggregateJsonMapper aggregateMapper;

    JdbcPersonRepository(
            JdbcTemplate jdbcTemplate,
            PersonAggregateJsonMapper aggregateMapper
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate cannot be null"
        );
        this.aggregateMapper = Objects.requireNonNull(
                aggregateMapper,
                "aggregateMapper cannot be null"
        );
    }

    /**
     * Inserts a new aggregate at version zero.
     *
     * @return {@code false} when the person identifier already exists
     */
    public boolean insert(Person person) {
        Person source = Objects.requireNonNull(person, "person cannot be null");
        try {
            return jdbcTemplate.update(
                    INSERT_SQL,
                    source.getId().toString(),
                    aggregateMapper.write(source)
            ) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override
    public Optional<VersionedPerson> findById(PersonId personId) {
        PersonId requestedId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        VersionedPerson result = jdbcTemplate.query(
                FIND_SQL,
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }
                    long version = resultSet.getLong("version");
                    Person person = aggregateMapper.read(
                            resultSet.getString("aggregate_json")
                    );
                    if (!person.getId().equals(requestedId)) {
                        throw new PersonPersistenceException(
                                "row identifier does not match aggregate document"
                        );
                    }
                    if (resultSet.next()) {
                        throw new PersonPersistenceException(
                                "multiple rows found for one person identifier"
                        );
                    }
                    return new VersionedPerson(person, version);
                },
                requestedId.toString()
        );
        return Optional.ofNullable(result);
    }

    @Override
    public boolean save(Person person, long expectedVersion) {
        Person source = Objects.requireNonNull(person, "person cannot be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion cannot be negative");
        }
        int updatedRows = jdbcTemplate.update(
                SAVE_SQL,
                aggregateMapper.write(source),
                source.getId().toString(),
                expectedVersion
        );
        if (updatedRows > 1) {
            throw new PersonPersistenceException(
                    "CAS update modified more than one person row"
            );
        }
        return updatedRows == 1;
    }
}

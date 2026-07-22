package com.laishengkai.digitalperson.person;

import java.util.Optional;

/**
 * Persistence boundary for one digital-person aggregate.
 *
 * <p>Loads include an optimistic-lock version. Saving succeeds only when the
 * persisted version still equals {@code expectedVersion}; successful saves
 * advance the persisted version exactly once.</p>
 */
public interface PersonRepository {
    Optional<VersionedPerson> findById(PersonId personId);

    boolean save(Person person, long expectedVersion);
}

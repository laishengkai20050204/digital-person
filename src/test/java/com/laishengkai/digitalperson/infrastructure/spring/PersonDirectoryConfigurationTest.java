package com.laishengkai.digitalperson.infrastructure.spring;

import com.laishengkai.digitalperson.application.PersonDirectoryService;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonCreationRepository;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PersonDirectoryConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PersonApplicationConfiguration.class);

    @Test
    void createsDirectoryWhenApiAndBothPersistenceCapabilitiesExist() {
        contextRunner
                .withPropertyValues("digital-person.person-api.enabled=true")
                .withBean(CombinedRepository.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(PersonDirectoryService.class));
    }

    @Test
    void doesNotCreateDirectoryWhenApiIsDisabled() {
        contextRunner
                .withBean(CombinedRepository.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PersonDirectoryService.class));
    }

    @Test
    void enabledApiFailsClearlyForReadOnlyRepository() {
        contextRunner
                .withPropertyValues("digital-person.person-api.enabled=true")
                .withBean(PersonRepository.class, ReadOnlyRepository::new)
                .run(context -> assertThat(context).hasFailed());
    }

    private static final class CombinedRepository
            implements PersonRepository, PersonCreationRepository {
        @Override
        public boolean insert(Person person) {
            return true;
        }

        @Override
        public Optional<VersionedPerson> findById(PersonId personId) {
            return Optional.empty();
        }

        @Override
        public boolean save(Person person, long expectedVersion) {
            return false;
        }
    }

    private static final class ReadOnlyRepository implements PersonRepository {
        @Override
        public Optional<VersionedPerson> findById(PersonId personId) {
            return Optional.empty();
        }

        @Override
        public boolean save(Person person, long expectedVersion) {
            return false;
        }
    }
}

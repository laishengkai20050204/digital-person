package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonDirectoryService;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonCreationRepository;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import com.laishengkai.digitalperson.personality.Personality;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersonControllerTest {
    private static final String TOKEN = "test-person-api-token";
    private InMemoryRepository repository;
    private PersonDirectoryService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository();
        service = new PersonDirectoryService(repository, repository);
        PersonController controller = new PersonController(
                service,
                new PersonApiProperties(true, TOKEN)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PersonApiExceptionHandler())
                .build();
    }

    @Test
    void rejectsRequestsWithoutTheInternalToken() throws Exception {
        mockMvc.perform(get("/api/persons/{personId}", PersonId.random()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("UNAUTHORIZED"));
    }

    @Test
    void createsAndPersistsBaselinePerson() throws Exception {
        mockMvc.perform(post("/api/persons")
                        .header(PersonController.INTERNAL_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/persons/" + repository.onlyId())))
                .andExpect(jsonPath("$.personId").value(repository.onlyId().toString()))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.personality.emotionality").value(0.7))
                .andExpect(jsonPath("$.state.energy").value(0.5))
                .andExpect(jsonPath("$.personEventCount").value(0))
                .andExpect(jsonPath("$.activeEffectChannels").isEmpty());
    }

    @Test
    void readsPersonAndStateWithStableStringIdentifiers() throws Exception {
        PersonId personId = service.create(neutralPersonality()).personId();

        mockMvc.perform(get("/api/persons/{personId}", personId)
                        .header(PersonController.INTERNAL_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personId").value(personId.toString()))
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(get("/api/persons/{personId}/state", personId)
                        .header(PersonController.INTERNAL_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personId").value(personId.toString()))
                .andExpect(jsonPath("$.state.valence").value(0.0))
                .andExpect(jsonPath("$.lastUpdatedAt").doesNotExist());
    }

    @Test
    void mapsUnknownAndMalformedIdentifiersSeparately() throws Exception {
        mockMvc.perform(get("/api/persons/{personId}", PersonId.random())
                        .header(PersonController.INTERNAL_TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("PERSON_NOT_FOUND"));

        mockMvc.perform(get("/api/persons/not-a-uuid")
                        .header(PersonController.INTERNAL_TOKEN_HEADER, TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsMissingAndOutOfRangePersonalityDimensions() throws Exception {
        mockMvc.perform(post("/api/persons")
                        .header(PersonController.INTERNAL_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "personality": {
                                    "honestyHumility": 0.5,
                                    "emotionality": null,
                                    "extraversion": 0.5,
                                    "agreeableness": 0.5,
                                    "conscientiousness": 0.5,
                                    "openness": 0.5
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/persons")
                        .header(PersonController.INTERNAL_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("\"emotionality\": 0.7", "\"emotionality\": 1.7")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_REQUEST"));
    }

    private static String validRequest() {
        return """
                {
                  "personality": {
                    "honestyHumility": 0.4,
                    "emotionality": 0.7,
                    "extraversion": 0.3,
                    "agreeableness": 0.8,
                    "conscientiousness": 0.6,
                    "openness": 0.9
                  }
                }
                """;
    }

    private static Personality neutralPersonality() {
        return new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5);
    }

    private static final class InMemoryRepository
            implements PersonRepository, PersonCreationRepository {
        private final Map<PersonId, VersionedPerson> people = new HashMap<>();

        @Override
        public boolean insert(Person person) {
            if (people.containsKey(person.getId())) {
                return false;
            }
            people.put(person.getId(), new VersionedPerson(person.copy(), 0L));
            return true;
        }

        @Override
        public Optional<VersionedPerson> findById(PersonId personId) {
            VersionedPerson stored = people.get(personId);
            return stored == null
                    ? Optional.empty()
                    : Optional.of(new VersionedPerson(
                            stored.person().copy(),
                            stored.version()
                    ));
        }

        @Override
        public boolean save(Person person, long expectedVersion) {
            VersionedPerson stored = people.get(person.getId());
            if (stored == null || stored.version() != expectedVersion) {
                return false;
            }
            people.put(
                    person.getId(),
                    new VersionedPerson(person.copy(), expectedVersion + 1)
            );
            return true;
        }

        private PersonId onlyId() {
            if (people.size() != 1) {
                throw new IllegalStateException("expected exactly one stored person");
            }
            return people.keySet().iterator().next();
        }
    }
}

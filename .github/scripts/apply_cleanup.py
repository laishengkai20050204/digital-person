from pathlib import Path

root = Path('.')


def read(path):
    return (root / path).read_text()


def write(path, text):
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected one match, found {count}')
    return text.replace(old, new, 1)


# Remove active-only compatibility constructors and adapters.
for path, class_name in [
    ('src/main/java/com/laishengkai/digitalperson/application/PersonEventCommandService.java', 'PersonEventCommandService'),
    ('src/main/java/com/laishengkai/digitalperson/application/UpdatePersonStateService.java', 'UpdatePersonStateService'),
]:
    text = read(path)
    text = replace_once(
        text,
        'import com.laishengkai.digitalperson.state.StateTransitionEvaluator;\n',
        '',
        path + ' import',
    )
    constructor = f'''    /** Compatibility constructor for active-only evaluators. */
    public {class_name}(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            StateTransitionEvaluator evaluator
    ) {{
        this(
                personRepository,
                stateUpdater,
                adapt(evaluator),
                DefaultStateEvaluationContextAssembler.withoutExternalSources()
        );
    }}

'''
    text = replace_once(text, constructor, '', path + ' compatibility constructor')
    constructor_with_context = f'''    /** Compatibility constructor for active-only evaluators with custom context. */
    public {class_name}(
            PersonRepository personRepository,
            StateUpdater stateUpdater,
            StateTransitionEvaluator evaluator,
            StateEvaluationContextAssembler contextAssembler
    ) {{
        this(personRepository, stateUpdater, adapt(evaluator), contextAssembler);
    }}

'''
    text = replace_once(
        text,
        constructor_with_context,
        '',
        path + ' compatibility constructor with context',
    )
    adapter = '''    private static EventStateImpactEvaluator adapt(StateTransitionEvaluator evaluator) {
        StateTransitionEvaluator requestedEvaluator = Objects.requireNonNull(
                evaluator,
                "evaluator cannot be null"
        );
        return context -> Objects.requireNonNull(
                        requestedEvaluator.evaluate(context),
                        "evaluator stage cannot be null"
                )
                .thenApply(transitions -> EventStateImpact.activeOnly(
                        List.copyOf(Objects.requireNonNull(
                                transitions,
                                "evaluator result cannot be null"
                        ))
                ));
    }

'''
    text = replace_once(text, adapter, '', path + ' adapter')
    write(path, text)

# Keep EventStateImpact as the unified effect collection only.
write(
    'src/main/java/com/laishengkai/digitalperson/state/EventStateImpact.java',
    '''package com.laishengkai.digitalperson.state;

import java.util.List;
import java.util.Objects;

/** Model-evaluated independent effects caused by one event. */
public record EventStateImpact(List<StateEffectDraft> effects) {
    public EventStateImpact {
        effects = List.copyOf(Objects.requireNonNull(effects, "effects cannot be null"));
        for (StateEffectDraft effect : effects) {
            Objects.requireNonNull(effect, "effect cannot be null");
        }
    }

    public static EventStateImpact none() {
        return new EventStateImpact(List.of());
    }
}
''',
)

write(
    'src/main/java/com/laishengkai/digitalperson/state/EventStateImpactEvaluator.java',
    '''package com.laishengkai.digitalperson.state;

import java.util.concurrent.CompletionStage;

/** Evaluates the independent state effects directly caused by one event. */
@FunctionalInterface
public interface EventStateImpactEvaluator {
    CompletionStage<EventStateImpact> evaluate(StateEvaluationContext context);
}
''',
)

# Remove the compatibility Spring bean.
path = 'src/main/java/com/laishengkai/digitalperson/infrastructure/state/StateTransitionEvaluationConfiguration.java'
text = read(path)
text = replace_once(
    text,
    'import com.laishengkai.digitalperson.state.StateTransitionEvaluator;\n',
    '',
    path + ' import',
)
text = replace_once(
    text,
    '''    /** Compatibility bean for callers that cannot represent independent lifecycles. */
    @Bean
    @ConditionalOnMissingBean(StateTransitionEvaluator.class)
    StateTransitionEvaluator stateTransitionEvaluator(
            EventStateImpactEvaluator evaluator
    ) {
        return context -> evaluator.evaluate(context)
                .thenApply(impact -> impact.effects().stream()
                        .flatMap(effect -> effect.transitions().stream())
                        .toList());
    }

''',
    '',
    path + ' compatibility bean',
)
write(path, text)

# Remove the dead aggregate dialogue method.
path = 'src/main/java/com/laishengkai/digitalperson/person/Person.java'
text = read(path)
text = replace_once(text, 'import com.laishengkai.digitalperson.dialogue.DialogueResult;\n', '', path + ' dialogue import')
text = replace_once(text, 'import java.util.concurrent.CompletionStage;\n', '', path + ' completion import')
text = replace_once(
    text,
    '''    /**
     * @deprecated Dialogue orchestration belongs to an application service.
     */
    @Deprecated(forRemoval = true)
    public CompletionStage<DialogueResult> chatAsync(String userMessage) {
        throw new UnsupportedOperationException(
                "Use an application-layer chat service instead"
        );
    }

''',
    '',
    path + ' chatAsync',
)
write(path, text)

path = 'src/main/java/com/laishengkai/digitalperson/dialogue/DialogueResult.java'
text = read(path)
text = replace_once(text, 'import com.laishengkai.digitalperson.person.Person;\n\n', '', path + ' Person import')
text = replace_once(
    text,
    '/**\n * Final user-facing result returned by {@link Person#chatAsync(String)}.\n */',
    '/** Final user-facing dialogue result produced by application orchestration. */',
    path + ' javadoc',
)
write(path, text)

# Test-only builder for the unified evaluator contract.
write(
    'src/test/java/com/laishengkai/digitalperson/support/StateEffectTestFixtures.java',
    '''package com.laishengkai.digitalperson.support;

import com.laishengkai.digitalperson.state.EventStateImpact;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEffectDraft;
import com.laishengkai.digitalperson.state.StateEffectType;
import com.laishengkai.digitalperson.state.StateTransition;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Test-only builders for unified state-effect evaluator results. */
public final class StateEffectTestFixtures {
    private StateEffectTestFixtures() {
    }

    public static EventStateImpact eventBoundImpact(StateTransition... transitions) {
        Map<StateEffectType, List<StateTransition>> grouped = new EnumMap<>(StateEffectType.class);
        for (StateTransition transition : List.of(transitions)) {
            StateTransition value = Objects.requireNonNull(transition, "transition cannot be null");
            grouped.computeIfAbsent(typeOf(value.dimension()), ignored -> new ArrayList<>())
                    .add(value);
        }
        return new EventStateImpact(grouped.entrySet().stream()
                .map(entry -> StateEffectDraft.eventBound(
                        entry.getKey(),
                        "Test event-bound effect",
                        entry.getValue()
                ))
                .toList());
    }

    private static StateEffectType typeOf(StateDimension dimension) {
        return switch (dimension) {
            case VALENCE, ENERGY, TENSION -> StateEffectType.EMOTIONAL;
            case FOCUS, MENTAL_LOAD, MOTIVATION -> StateEffectType.COGNITIVE;
            case FATIGUE, SLEEPINESS, HUNGER -> StateEffectType.PHYSICAL;
            case LONELINESS, SOCIAL_NEED -> StateEffectType.SOCIAL;
        };
    }
}
''',
)

# Migrate tests from transition-only evaluators to unified effect evaluators.
path = 'src/test/java/com/laishengkai/digitalperson/application/UpdatePersonStateServiceTest.java'
text = read(path)
text = text.replace(
    'import com.laishengkai.digitalperson.state.StateTransitionEvaluator;\n',
    'import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;\n',
)
text = text.replace('import java.util.List;\n', '')
text = text.replace(
    'import static org.junit.jupiter.api.Assertions.assertEquals;\n',
    'import static com.laishengkai.digitalperson.support.StateEffectTestFixtures.eventBoundImpact;\n'
    'import static org.junit.jupiter.api.Assertions.assertEquals;\n',
)
text = text.replace(
    'StateTransitionEvaluator evaluator = context -> CompletableFuture.completedFuture(\n'
    '                List.of(new StateTransition(StateDimension.HUNGER, -1.0))\n'
    '        );',
    'EventStateImpactEvaluator evaluator = context -> CompletableFuture.completedFuture(\n'
    '                eventBoundImpact(new StateTransition(StateDimension.HUNGER, -1.0))\n'
    '        );',
)
text = text.replace(
    'StateTransitionEvaluator evaluator = context -> CompletableFuture.failedFuture(',
    'EventStateImpactEvaluator evaluator = context -> CompletableFuture.failedFuture(',
)
write(path, text)

path = 'src/test/java/com/laishengkai/digitalperson/application/PersonEventCommandServiceTest.java'
text = read(path)
text = text.replace(
    'import com.laishengkai.digitalperson.state.StateDimension;\n',
    'import com.laishengkai.digitalperson.state.EventStateImpact;\n'
    'import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;\n'
    'import com.laishengkai.digitalperson.state.StateDimension;\n',
)
text = text.replace(
    'import static org.junit.jupiter.api.Assertions.assertEquals;\n',
    'import static com.laishengkai.digitalperson.support.StateEffectTestFixtures.eventBoundImpact;\n'
    'import static org.junit.jupiter.api.Assertions.assertEquals;\n',
)
text = text.replace('CompletableFuture<List<StateTransition>> evaluation = new CompletableFuture<>();', 'CompletableFuture<EventStateImpact> evaluation = new CompletableFuture<>();')
text = text.replace('List.of(new StateTransition(StateDimension.HUNGER, -1.0))', 'eventBoundImpact(new StateTransition(StateDimension.HUNGER, -1.0))')
text = text.replace('List.of(new StateTransition(StateDimension.ENERGY, 1.0))', 'eventBoundImpact(new StateTransition(StateDimension.ENERGY, 1.0))')
text = text.replace('CompletableFuture.completedFuture(List.of())', 'CompletableFuture.completedFuture(EventStateImpact.none())')
text = text.replace('com.laishengkai.digitalperson.state.StateTransitionEvaluator evaluator', 'EventStateImpactEvaluator evaluator')
write(path, text)

path = 'src/test/java/com/laishengkai/digitalperson/application/StateUpdateConsistencyRegressionTest.java'
text = read(path)
text = text.replace(
    'import com.laishengkai.digitalperson.state.StateTransitionEvaluator;\n',
    'import com.laishengkai.digitalperson.state.EventStateImpact;\n'
    'import com.laishengkai.digitalperson.state.EventStateImpactEvaluator;\n',
)
text = text.replace('import java.util.List;\n', '')
text = text.replace(
    'import static org.junit.jupiter.api.Assertions.assertEquals;\n',
    'import static com.laishengkai.digitalperson.support.StateEffectTestFixtures.eventBoundImpact;\n'
    'import static org.junit.jupiter.api.Assertions.assertEquals;\n',
)
text = text.replace('StateTransitionEvaluator evaluator', 'EventStateImpactEvaluator evaluator')
text = text.replace('CompletableFuture<List<StateTransition>> eatingEvaluation', 'CompletableFuture<EventStateImpact> eatingEvaluation')
text = text.replace('CompletableFuture<List<StateTransition>> restEvaluation', 'CompletableFuture<EventStateImpact> restEvaluation')
text = text.replace('List.of(new StateTransition(StateDimension.HUNGER, -1.0))', 'eventBoundImpact(new StateTransition(StateDimension.HUNGER, -1.0))')
text = text.replace('List.of(new StateTransition(StateDimension.ENERGY, 1.0))', 'eventBoundImpact(new StateTransition(StateDimension.ENERGY, 1.0))')
write(path, text)

path = 'src/test/java/com/laishengkai/digitalperson/infrastructure/state/StateTransitionEvaluationSpringWiringTest.java'
text = read(path)
text = text.replace('import com.laishengkai.digitalperson.state.StateTransitionEvaluator;\n', '')
text = text.replace('                    assertThat(context).hasSingleBean(StateTransitionEvaluator.class);\n', '')
text = text.replace('                    assertThat(context).doesNotHaveBean(StateTransitionEvaluator.class);\n', '')
write(path, text)

# The richer diagnostic and contrast endpoints supersede this transition-only smoke endpoint.
for path in [
    'src/main/java/com/laishengkai/digitalperson/state/StateTransitionEvaluator.java',
    'src/main/java/com/laishengkai/digitalperson/web/StateEvaluationTestController.java',
    'src/test/java/com/laishengkai/digitalperson/web/StateEvaluationTestControllerTest.java',
    'src/test/java/com/laishengkai/digitalperson/web/StateEvaluationTestControllerSpringWiringTest.java',
]:
    (root / path).unlink()

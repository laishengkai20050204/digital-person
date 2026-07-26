package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionContext;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionModel;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionPlan;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Assembles provider-neutral context and invokes the pure activity decision model. */
final class PersonActivityDecisionModelRunner {
    private final PersonActivityDecisionContextAssembler contextAssembler;
    private final PersonActivityDecisionModel model;

    PersonActivityDecisionModelRunner(
            PersonActivityDecisionContextAssembler contextAssembler,
            PersonActivityDecisionModel model
    ) {
        this.contextAssembler = Objects.requireNonNull(
                contextAssembler,
                "contextAssembler cannot be null"
        );
        this.model = Objects.requireNonNull(model, "model cannot be null");
    }

    CompletionStage<PersonActivityDecisionPlan> decide(
            Person person,
            PersonStateSnapshot state,
            StateEvolutionContext evolution,
            String observation,
            Instant now,
            ActivityDecisionDeadline deadline
    ) {
        deadline.checkpoint("activity context source loading");
        CompletionStage<PersonActivityDecisionContext> contextStage =
                Objects.requireNonNull(
                        contextAssembler.assemble(
                                person,
                                state,
                                evolution,
                                observation,
                                now
                        ),
                        "activityContextAssembler stage cannot be null"
                );
        return deadline.guard(
                contextStage,
                "activity context source loading"
        ).thenCompose(context -> {
            deadline.checkpoint("activity context completion");
            PersonActivityDecisionContext safeContext = Objects.requireNonNull(
                    context,
                    "assembled activity context cannot be null"
            );
            deadline.checkpoint("activity model invocation");
            CompletionStage<PersonActivityDecisionPlan> planStage =
                    Objects.requireNonNull(
                            model.decide(safeContext),
                            "activityDecisionModel stage cannot be null"
                    );
            return deadline.guard(
                    planStage,
                    "activity model invocation"
            ).thenApply(plan -> {
                deadline.checkpoint("activity model response");
                return Objects.requireNonNull(
                        plan,
                        "activityDecisionModel result cannot be null"
                );
            });
        });
    }
}

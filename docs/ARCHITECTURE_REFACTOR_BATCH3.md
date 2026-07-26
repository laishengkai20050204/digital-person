# Architecture Refactor Batch 3

This batch is an equivalence refactor. It does not change HTTP APIs, database schemas, prompts, model settings, or state-evolution coefficients.

## Agent execution

`DefaultAgentExecutor` now owns only the model/tool loop. Monotonic budgeting, tool registration, call validation, tool execution, and usage accumulation are isolated in package-private collaborators with focused tests.

## Activity decisions

`PersonActivityDecisionService` remains the transaction coordinator. Deadline handling, context/model invocation, plan validation, and aggregate timeline application are isolated so each responsibility can be tested and changed independently.

## Package dependency direction

`StateEvaluationContext` and `EventStateImpactEvaluator` are application-layer model ports rather than state-domain types. `StateUpdater` accepts a stable person key instead of importing `PersonId`. The `state` package therefore no longer depends on person, conversation, memory, model-context, or personality packages.

ArchUnit now requires all top-level project packages to remain free of cycles.

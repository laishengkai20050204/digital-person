package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.DialogueActivityReactionService;
import com.laishengkai.digitalperson.person.PersonId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Flushes pending dialogue evidence before commands that display activity information. */
@Component
@Primary
@ConditionalOnProperty(
        prefix = "digital-person.openai-compat",
        name = "enabled",
        havingValue = "true"
)
public final class RefreshingWechatSlashCommandHandler implements WechatSlashCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            RefreshingWechatSlashCommandHandler.class
    );
    private static final String COMMAND_NAMESPACE = "#dp";
    private static final Duration DEFAULT_REFRESH_TIMEOUT = Duration.ofSeconds(90);
    private static final String PENDING_NOTICE =
            "\n\n提示：活动判断仍在处理中，本次显示可能稍有延迟。";
    private static final String FAILED_NOTICE =
            "\n\n提示：活动刷新失败，本次显示最近已保存的状态。";

    private final WechatSlashCommandService delegate;
    private final ActivityReviewRefresher activityReviewRefresher;
    private final Duration refreshTimeout;

    public RefreshingWechatSlashCommandHandler(
            WechatSlashCommandService delegate,
            ObjectProvider<DialogueActivityReactionService> reactionServices
    ) {
        this(
                delegate,
                personId -> Optional.ofNullable(reactionServices.getIfAvailable())
                        .map(service -> service.flushPendingAndAwait(personId))
                        .orElseGet(() -> CompletableFuture.completedFuture(false)),
                DEFAULT_REFRESH_TIMEOUT
        );
    }

    RefreshingWechatSlashCommandHandler(
            WechatSlashCommandService delegate,
            ActivityReviewRefresher activityReviewRefresher,
            Duration refreshTimeout
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.activityReviewRefresher = Objects.requireNonNull(
                activityReviewRefresher,
                "activityReviewRefresher cannot be null"
        );
        this.refreshTimeout = requirePositive(refreshTimeout, "refreshTimeout");
    }

    @Override
    public Optional<CommandResult> handle(PersonId personId, String message) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        String requestedMessage = Objects.requireNonNull(message, "message cannot be null");
        RefreshOutcome outcome = requiresActivityRefresh(requestedMessage)
                ? refreshActivity(requestedPersonId)
                : RefreshOutcome.NOT_REQUIRED;

        return delegate.handle(requestedPersonId, requestedMessage)
                .map(result -> appendNotice(result, outcome));
    }

    private RefreshOutcome refreshActivity(PersonId personId) {
        try {
            CompletionStage<Boolean> stage = Objects.requireNonNull(
                    activityReviewRefresher.flushAndAwait(personId),
                    "activity refresh stage cannot be null"
            );
            stage.toCompletableFuture().get(
                    refreshTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            return RefreshOutcome.COMPLETED;
        } catch (TimeoutException error) {
            LOGGER.info(
                    "Activity command refresh timed out: personId={}, timeoutMs={}",
                    personId,
                    refreshTimeout.toMillis()
            );
            return RefreshOutcome.PENDING;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            LOGGER.info("Activity command refresh interrupted: personId={}", personId);
            return RefreshOutcome.PENDING;
        } catch (ExecutionException | RuntimeException error) {
            LOGGER.warn("Activity command refresh failed: personId={}", personId, error);
            return RefreshOutcome.FAILED;
        }
    }

    private static CommandResult appendNotice(
            CommandResult result,
            RefreshOutcome outcome
    ) {
        String notice = switch (outcome) {
            case PENDING -> PENDING_NOTICE;
            case FAILED -> FAILED_NOTICE;
            case NOT_REQUIRED, COMPLETED -> "";
        };
        if (notice.isEmpty()) {
            return result;
        }
        return new CommandResult(result.content() + notice, result.occurredAt());
    }

    private static boolean requiresActivityRefresh(String message) {
        String normalized = message.strip();
        if (!normalized.regionMatches(
                true,
                0,
                COMMAND_NAMESPACE,
                0,
                COMMAND_NAMESPACE.length()
        )) {
            return false;
        }
        if (normalized.length() == COMMAND_NAMESPACE.length()
                || !Character.isWhitespace(normalized.charAt(COMMAND_NAMESPACE.length()))) {
            return false;
        }
        String command = normalized.substring(COMMAND_NAMESPACE.length())
                .strip()
                .toLowerCase(Locale.ROOT);
        return "activity".equals(command)
                || "activity24h".equals(command)
                || "status".equals(command);
    }

    private static Duration requirePositive(Duration value, String name) {
        Duration safeValue = Objects.requireNonNull(value, name + " cannot be null");
        if (safeValue.isZero() || safeValue.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return safeValue;
    }

    @FunctionalInterface
    interface ActivityReviewRefresher {
        CompletionStage<Boolean> flushAndAwait(PersonId personId);
    }

    private enum RefreshOutcome {
        NOT_REQUIRED,
        COMPLETED,
        PENDING,
        FAILED
    }
}

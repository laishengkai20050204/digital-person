package com.laishengkai.digitalperson.infrastructure.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Periodically extends one database lease while an activity decision is in flight. */
public final class PersonActivityLeaseHeartbeat {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            PersonActivityLeaseHeartbeat.class
    );

    private final PersonActivityScheduleRepository scheduleRepository;
    private final ActivitySchedulerProperties properties;
    private final Clock clock;
    private final TaskScheduler taskScheduler;

    public PersonActivityLeaseHeartbeat(
            PersonActivityScheduleRepository scheduleRepository,
            ActivitySchedulerProperties properties,
            Clock clock,
            TaskScheduler taskScheduler
    ) {
        this.scheduleRepository = Objects.requireNonNull(
                scheduleRepository,
                "scheduleRepository cannot be null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.taskScheduler = Objects.requireNonNull(
                taskScheduler,
                "taskScheduler cannot be null"
        );
    }

    public LeaseHandle start(PersonActivityScheduleLease lease) {
        PersonActivityScheduleLease claim = Objects.requireNonNull(
                lease,
                "lease cannot be null"
        );
        AtomicBoolean leaseOwned = new AtomicBoolean(true);
        Instant firstRenewalAt = clock.instant().plus(properties.leaseRenewalInterval());
        ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(
                () -> renew(claim, leaseOwned),
                firstRenewalAt,
                properties.leaseRenewalInterval()
        );
        if (future == null) {
            throw new IllegalStateException("activity lease heartbeat was not scheduled");
        }
        return new LeaseHandle(claim, leaseOwned, future);
    }

    private void renew(
            PersonActivityScheduleLease lease,
            AtomicBoolean leaseOwned
    ) {
        if (!leaseOwned.get()) {
            return;
        }
        Instant renewedAt = clock.instant();
        Instant newLeaseUntil = renewedAt.plus(properties.leaseDuration());
        try {
            boolean renewed = scheduleRepository.renewLease(
                    lease,
                    newLeaseUntil,
                    renewedAt
            );
            if (!renewed) {
                leaseOwned.set(false);
                LOGGER.warn(
                        "Activity schedule lease heartbeat lost ownership: personId={}, leaseToken={}",
                        lease.personId(),
                        lease.leaseToken()
                );
                return;
            }
            LOGGER.debug(
                    "Renewed activity schedule lease: personId={}, leaseUntil={}",
                    lease.personId(),
                    newLeaseUntil
            );
        } catch (RuntimeException error) {
            LOGGER.error(
                    "Activity schedule lease heartbeat failed: personId={}, leaseToken={}",
                    lease.personId(),
                    lease.leaseToken(),
                    error
            );
        }
    }

    public static final class LeaseHandle implements AutoCloseable {
        private final PersonActivityScheduleLease lease;
        private final AtomicBoolean leaseOwned;
        private final ScheduledFuture<?> future;
        private final AtomicBoolean closed = new AtomicBoolean();

        private LeaseHandle(
                PersonActivityScheduleLease lease,
                AtomicBoolean leaseOwned,
                ScheduledFuture<?> future
        ) {
            this.lease = Objects.requireNonNull(lease, "lease cannot be null");
            this.leaseOwned = Objects.requireNonNull(
                    leaseOwned,
                    "leaseOwned cannot be null"
            );
            this.future = Objects.requireNonNull(future, "future cannot be null");
        }

        public PersonActivityScheduleLease lease() {
            return lease;
        }

        public boolean leaseOwned() {
            return leaseOwned.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                future.cancel(false);
            }
        }
    }
}

package com.mycorp.distributedlock.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record BackendBehavior(
    Set<LockMode> lockModes,
    FencingSemantics fencing,
    Set<LeaseSemantics> leaseSemantics,
    SessionSemantics session,
    WaitSemantics waitSemantics,
    FairnessSemantics fairness,
    OwnershipLossSemantics ownershipLoss,
    BackendCostModel costModel
) {

    public BackendBehavior {
        lockModes = immutableEnumSet(lockModes, "lockModes");
        fencing = Objects.requireNonNull(fencing, "fencing");
        leaseSemantics = immutableEnumSet(leaseSemantics, "leaseSemantics");
        session = Objects.requireNonNull(session, "session");
        waitSemantics = Objects.requireNonNull(waitSemantics, "waitSemantics");
        fairness = Objects.requireNonNull(fairness, "fairness");
        ownershipLoss = Objects.requireNonNull(ownershipLoss, "ownershipLoss");
        costModel = Objects.requireNonNull(costModel, "costModel");
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean supportsLockMode(LockMode mode) {
        return lockModes.contains(Objects.requireNonNull(mode, "mode"));
    }

    public boolean supportsLeaseSemantics(LeaseSemantics semantics) {
        return leaseSemantics.contains(Objects.requireNonNull(semantics, "semantics"));
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(Set<E> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
    }

    public static final class Builder {
        private Set<LockMode> lockModes;
        private FencingSemantics fencing;
        private Set<LeaseSemantics> leaseSemantics;
        private SessionSemantics session;
        private WaitSemantics wait;
        private FairnessSemantics fairness;
        private OwnershipLossSemantics ownershipLoss;
        private BackendCostModel costModel;

        public Builder lockModes(Set<LockMode> lockModes) {
            this.lockModes = lockModes;
            return this;
        }

        public Builder fencing(FencingSemantics fencing) {
            this.fencing = fencing;
            return this;
        }

        public Builder leaseSemantics(Set<LeaseSemantics> leaseSemantics) {
            this.leaseSemantics = leaseSemantics;
            return this;
        }

        public Builder session(SessionSemantics session) {
            this.session = session;
            return this;
        }

        public Builder wait(WaitSemantics wait) {
            return waitSemantics(wait);
        }

        public Builder waitSemantics(WaitSemantics waitSemantics) {
            this.wait = waitSemantics;
            return this;
        }

        public Builder fairness(FairnessSemantics fairness) {
            this.fairness = fairness;
            return this;
        }

        public Builder ownershipLoss(OwnershipLossSemantics ownershipLoss) {
            this.ownershipLoss = ownershipLoss;
            return this;
        }

        public Builder costModel(BackendCostModel costModel) {
            this.costModel = costModel;
            return this;
        }

        public BackendBehavior build() {
            return new BackendBehavior(
                    lockModes, fencing, leaseSemantics, session, wait, fairness, ownershipLoss, costModel);
        }
    }
}

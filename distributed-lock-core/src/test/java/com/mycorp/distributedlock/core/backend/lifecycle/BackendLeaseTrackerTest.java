package com.mycorp.distributedlock.core.backend.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendLeaseTrackerTest {

    @Test
    void shouldRegisterSnapshotAndRemoveLeasesById() {
        BackendLeaseTracker<String> tracker = new BackendLeaseTracker<>();

        tracker.register("first", "lease-one");
        tracker.register("second", "lease-two");

        assertThat(tracker.snapshot()).containsExactlyInAnyOrder("lease-one", "lease-two");
        assertThat(tracker.remove("first")).isTrue();
        assertThat(tracker.snapshot()).containsExactly("lease-two");
    }

    @Test
    void shouldRejectDuplicateLeaseIds() {
        BackendLeaseTracker<String> tracker = new BackendLeaseTracker<>();

        tracker.register("duplicate", "lease-one");

        assertThatThrownBy(() -> tracker.register("duplicate", "lease-two"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate backend lease id");
    }

    @Test
    void shouldAggregateCloseFailures() {
        BackendLeaseTracker<Runnable> tracker = new BackendLeaseTracker<>();
        RuntimeException firstFailure = new RuntimeException("first failure");
        RuntimeException secondFailure = new RuntimeException("second failure");
        tracker.register("first", () -> {
            throw firstFailure;
        });
        tracker.register("second", () -> {
            throw secondFailure;
        });

        RuntimeException failure = tracker.releaseAll(null, Runnable::run);

        assertThat(List.of(firstFailure, secondFailure)).contains(failure);
        RuntimeException suppressed = failure == firstFailure ? secondFailure : firstFailure;
        assertThat(failure.getSuppressed()).containsExactly(suppressed);
    }
}

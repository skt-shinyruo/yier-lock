package com.mycorp.distributedlock.testkit;

import com.mycorp.distributedlock.api.LeasePolicy;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.WaitPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class LeasePolicyContract extends LockClientContract {

    @Test
    void threeArgumentRequestShouldUseBackendDefaultLeasePolicy() {
        assertThat(request("inventory:lease-policy", LockMode.MUTEX, WaitPolicy.tryOnce()).leasePolicy())
            .isEqualTo(LeasePolicy.backendDefault());
    }
}

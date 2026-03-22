package com.mycorp.distributedlock.benchmarks;

public final class BenchmarkKeys {

    private BenchmarkKeys() {
    }

    public static String unique(String suite, String backend, long id) {
        return "bench:%s:%s:unique:%d".formatted(suite, backend, id);
    }

    public static String shared(String suite, String backend) {
        return "bench:%s:%s:shared".formatted(suite, backend);
    }
}

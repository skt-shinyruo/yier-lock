package com.mycorp.distributedlock.benchmarks;

import com.mycorp.distributedlock.benchmarks.support.SpringBenchmarkEnvironment;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class SpringStarterBenchmark {

    private SpringBenchmarkEnvironment environment;

    @Setup(Level.Trial)
    public void setUp() {
        environment = SpringBenchmarkEnvironment.start();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (environment != null) {
            environment.close();
        }
    }

    @Benchmark
    public void annotatedPath(Blackhole blackhole) {
        String id = BenchmarkKeys.unique("spring-annotated", "redis", Thread.currentThread().getId());
        blackhole.consume(environment.annotatedService().annotated(id));
    }

    @Benchmark
    public void programmaticPath(Blackhole blackhole) throws InterruptedException {
        String id = BenchmarkKeys.unique("spring-programmatic", "redis", Thread.currentThread().getId());
        blackhole.consume(environment.programmaticService().programmatic(id));
    }
}

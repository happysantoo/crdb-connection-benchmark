package com.crdb.benchmark.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects and manages benchmark metrics using Micrometer
 * Tracks latency, throughput, errors, and connection pool statistics
 * Stores metrics in memory for analysis and reporting
 */
public class MetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(MetricsCollector.class);
    
    private final MeterRegistry registry;
    private final Map<String, io.micrometer.core.instrument.Timer> operationTimers;
    private final Map<String, Counter> errorCounters;
    private final Map<String, AtomicLong> successCounters;
    private final Map<String, Gauge> poolGauges;
    private final io.micrometer.core.instrument.Timer globalLatency;
    private final Counter globalOperations;
    private final Counter globalErrors;
    
    public MetricsCollector() {
        this(new SimpleMeterRegistry());
    }
    
    public MetricsCollector(MeterRegistry registry) {
        this.registry = registry;
        this.operationTimers = new ConcurrentHashMap<>();
        this.errorCounters = new ConcurrentHashMap<>();
        this.successCounters = new ConcurrentHashMap<>();
        this.poolGauges = new ConcurrentHashMap<>();
        
        // Global metrics
        this.globalLatency = io.micrometer.core.instrument.Timer.builder("benchmark.operation.latency")
            .description("Global operation latency")
            .publishPercentiles(0.5, 0.75, 0.90, 0.95, 0.99, 0.999)
            .register(registry);
        
        this.globalOperations = Counter.builder("benchmark.operations.total")
            .description("Total number of operations")
            .register(registry);
        
        this.globalErrors = Counter.builder("benchmark.errors.total")
            .description("Total number of errors")
            .register(registry);
        
        logger.info("Initialized metrics collector with in-memory registry");
    }
    
    /**
     * Record an operation execution
     */
    public void recordOperation(String operationType, Duration duration, boolean success) {
        // Record global metrics
        globalLatency.record(duration);
        globalOperations.increment();
        
        // Record per-operation-type metrics
        io.micrometer.core.instrument.Timer timer = operationTimers.computeIfAbsent(operationType, 
            type -> io.micrometer.core.instrument.Timer.builder("benchmark.operation.latency")
                .tag("type", type)
                .description("Operation latency by type")
                .publishPercentiles(0.5, 0.75, 0.90, 0.95, 0.99, 0.999)
                .register(registry));
        
        timer.record(duration);
        
        if (success) {
            successCounters.computeIfAbsent(operationType, type -> new AtomicLong(0))
                .incrementAndGet();
        }
    }
    
    /**
     * Record an error
     */
    public void recordError(Exception e) {
        globalErrors.increment();
        
        String errorType = e.getClass().getSimpleName();
        Counter counter = errorCounters.computeIfAbsent(errorType,
            type -> Counter.builder("benchmark.errors")
                .tag("type", type)
                .description("Errors by type")
                .register(registry));
        
        counter.increment();
        
        logger.debug("Recorded error: {}", errorType);
    }
    
    /**
     * Record connection pool metrics
     */
    public void recordPoolMetrics(String regionName, int active, int idle, int total, int awaiting) {
        // Active connections
        Gauge.builder("benchmark.pool.active", () -> active)
            .tag("region", regionName)
            .description("Active connections in pool")
            .register(registry);
        
        // Idle connections
        Gauge.builder("benchmark.pool.idle", () -> idle)
            .tag("region", regionName)
            .description("Idle connections in pool")
            .register(registry);
        
        // Total connections
        Gauge.builder("benchmark.pool.total", () -> total)
            .tag("region", regionName)
            .description("Total connections in pool")
            .register(registry);
        
        // Threads awaiting connection
        Gauge.builder("benchmark.pool.awaiting", () -> awaiting)
            .tag("region", regionName)
            .description("Threads awaiting connection")
            .register(registry);
    }
    
    /**
     * Get current metrics snapshot
     */
    public MetricsSnapshot getSnapshot() {
        Map<String, OperationMetrics> operationMetrics = new HashMap<>();
        
        for (Map.Entry<String, io.micrometer.core.instrument.Timer> entry : operationTimers.entrySet()) {
            String opType = entry.getKey();
            io.micrometer.core.instrument.Timer timer = entry.getValue();
            
            long count = timer.count();
            double meanLatency = timer.mean(TimeUnit.MILLISECONDS);
            double maxLatency = timer.max(TimeUnit.MILLISECONDS);
            
            // Get percentiles
            HistogramSnapshot snapshot = timer.takeSnapshot();
            Map<Double, Double> percentiles = new HashMap<>();
            for (ValueAtPercentile vap : snapshot.percentileValues()) {
                percentiles.put(vap.percentile(), vap.value(TimeUnit.MILLISECONDS));
            }
            
            long successCount = successCounters.getOrDefault(opType, new AtomicLong(0)).get();
            
            operationMetrics.put(opType, new OperationMetrics(
                count,
                successCount,
                meanLatency,
                maxLatency,
                percentiles
            ));
        }
        
        // Error metrics
        Map<String, Long> errorMetrics = new HashMap<>();
        for (Map.Entry<String, Counter> entry : errorCounters.entrySet()) {
            errorMetrics.put(entry.getKey(), (long) entry.getValue().count());
        }
        
        return new MetricsSnapshot(
            globalOperations.count(),
            globalErrors.count(),
            globalLatency.mean(TimeUnit.MILLISECONDS),
            operationMetrics,
            errorMetrics
        );
    }
    
    /**
     * Get summary statistics
     */
    public SummaryStatistics getSummaryStatistics() {
        HistogramSnapshot snapshot = globalLatency.takeSnapshot();
        
        Map<Double, Double> percentiles = new HashMap<>();
        for (ValueAtPercentile vap : snapshot.percentileValues()) {
            percentiles.put(vap.percentile(), vap.value(TimeUnit.MILLISECONDS));
        }
        
        long totalOps = (long) globalOperations.count();
        long totalErrors = (long) globalErrors.count();
        double errorRate = totalOps > 0 ? (totalErrors / (double) totalOps) * 100 : 0;
        
        return new SummaryStatistics(
            totalOps,
            totalErrors,
            errorRate,
            globalLatency.mean(TimeUnit.MILLISECONDS),
            globalLatency.max(TimeUnit.MILLISECONDS),
            percentiles
        );
    }
    
    /**
     * Reset all metrics
     */
    public void reset() {
        logger.info("Resetting metrics collector");
        registry.clear();
        operationTimers.clear();
        errorCounters.clear();
        successCounters.clear();
        poolGauges.clear();
    }
    
    /**
     * Get the underlying meter registry
     */
    public MeterRegistry getRegistry() {
        return registry;
    }
    
    /**
     * Snapshot of metrics at a point in time
     */
    public record MetricsSnapshot(
        double totalOperations,
        double totalErrors,
        double meanLatency,
        Map<String, OperationMetrics> operationMetrics,
        Map<String, Long> errorMetrics
    ) {}
    
    /**
     * Metrics for a specific operation type
     */
    public record OperationMetrics(
        long count,
        long successCount,
        double meanLatency,
        double maxLatency,
        Map<Double, Double> percentiles
    ) {
        public double getPercentile(double p) {
            return percentiles.getOrDefault(p, 0.0);
        }
    }
    
    /**
     * Summary statistics for reporting
     */
    public record SummaryStatistics(
        long totalOperations,
        long totalErrors,
        double errorRatePercent,
        double meanLatencyMs,
        double maxLatencyMs,
        Map<Double, Double> latencyPercentiles
    ) {
        public double getLatencyP50() {
            return latencyPercentiles.getOrDefault(0.5, 0.0);
        }
        
        public double getLatencyP90() {
            return latencyPercentiles.getOrDefault(0.90, 0.0);
        }
        
        public double getLatencyP95() {
            return latencyPercentiles.getOrDefault(0.95, 0.0);
        }
        
        public double getLatencyP99() {
            return latencyPercentiles.getOrDefault(0.99, 0.0);
        }
        
        public double getLatencyP999() {
            return latencyPercentiles.getOrDefault(0.999, 0.0);
        }
        
        @Override
        public String toString() {
            return String.format("""
                Total Operations: %d
                Total Errors: %d (%.2f%%)
                Mean Latency: %.2f ms
                P50 Latency: %.2f ms
                P90 Latency: %.2f ms
                P95 Latency: %.2f ms
                P99 Latency: %.2f ms
                P99.9 Latency: %.2f ms
                Max Latency: %.2f ms
                """,
                totalOperations, totalErrors, errorRatePercent,
                meanLatencyMs,
                getLatencyP50(), getLatencyP90(), getLatencyP95(),
                getLatencyP99(), getLatencyP999(),
                maxLatencyMs
            );
        }
    }
}

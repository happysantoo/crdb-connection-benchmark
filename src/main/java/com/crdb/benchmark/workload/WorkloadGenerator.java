package com.crdb.benchmark.workload;

import com.crdb.benchmark.config.BenchmarkConfig;
import com.crdb.benchmark.metrics.MetricsCollector;
import com.crdb.benchmark.pool.ConnectionPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual thread-based workload generator for benchmarking
 * Uses Java 21 virtual threads for high concurrency with low overhead
 */
public class WorkloadGenerator {
    private static final Logger logger = LoggerFactory.getLogger(WorkloadGenerator.class);
    
    private final BenchmarkConfig config;
    private final ConnectionPoolManager poolManager;
    private final MetricsCollector metricsCollector;
    private final OperationFactory operationFactory;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong operationCounter = new AtomicLong(0);
    
    public WorkloadGenerator(
            BenchmarkConfig config,
            ConnectionPoolManager poolManager,
            MetricsCollector metricsCollector) {
        this.config = config;
        this.poolManager = poolManager;
        this.metricsCollector = metricsCollector;
        this.operationFactory = new OperationFactory(config);
    }
    
    /**
     * Execute workload with specified pattern and concurrency
     * Uses virtual threads for massive concurrency
     */
    public WorkloadResult execute(WorkloadPattern pattern, int concurrency, Duration duration) {
        logger.info("Starting workload: pattern={}, concurrency={}, duration={}", 
            pattern, concurrency, duration);
        
        running.set(true);
        operationCounter.set(0);
        Instant startTime = Instant.now();
        Instant endTime = startTime.plus(duration);
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // Create virtual thread executor
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            
            // Launch concurrent workers
            for (int i = 0; i < concurrency; i++) {
                final int workerId = i;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    workerLoop(pattern, endTime, workerId);
                }, executor);
                futures.add(future);
            }
            
            // Wait for all workers to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .join();
        }
        
        running.set(false);
        Instant actualEndTime = Instant.now();
        
        long totalOperations = operationCounter.get();
        Duration actualDuration = Duration.between(startTime, actualEndTime);
        
        logger.info("Workload completed: {} operations in {}", 
            totalOperations, actualDuration);
        
        return new WorkloadResult(
            pattern,
            concurrency,
            totalOperations,
            actualDuration,
            metricsCollector.getSnapshot()
        );
    }
    
    /**
     * Execute workload with structured concurrency (Java 21 preview feature)
     */
    public WorkloadResult executeWithStructuredConcurrency(
            WorkloadPattern pattern, 
            int concurrency, 
            Duration duration) throws InterruptedException {
        
        logger.info("Starting workload with structured concurrency: pattern={}, concurrency={}", 
            pattern, concurrency);
        
        running.set(true);
        operationCounter.set(0);
        Instant startTime = Instant.now();
        Instant endTime = startTime.plus(duration);
        
        // Use try-with-resources for structured concurrency scope
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            
            // Fork tasks in the scope
            for (int i = 0; i < concurrency; i++) {
                final int workerId = i;
                scope.fork(() -> {
                    workerLoop(pattern, endTime, workerId);
                    return null;
                });
            }
            
            // Join all tasks
            scope.join();
            scope.throwIfFailed();
            
        } catch (ExecutionException e) {
            logger.error("Workload execution failed", e);
            throw new RuntimeException("Workload execution failed", e);
        }
        
        running.set(false);
        Instant actualEndTime = Instant.now();
        
        long totalOperations = operationCounter.get();
        Duration actualDuration = Duration.between(startTime, actualEndTime);
        
        logger.info("Workload completed: {} operations in {}", 
            totalOperations, actualDuration);
        
        return new WorkloadResult(
            pattern,
            concurrency,
            totalOperations,
            actualDuration,
            metricsCollector.getSnapshot()
        );
    }
    
    /**
     * Worker loop that executes operations until end time
     */
    private void workerLoop(WorkloadPattern pattern, Instant endTime, int workerId) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        while (running.get() && Instant.now().isBefore(endTime)) {
            try {
                // Determine operation type based on pattern
                DatabaseOperation operation = pattern.isReadOperation(random.nextDouble())
                    ? operationFactory.createReadOperation()
                    : operationFactory.createWriteOperation();
                
                // Execute operation with timing
                executeOperation(operation, pattern);
                
                operationCounter.incrementAndGet();
                
            } catch (Exception e) {
                metricsCollector.recordError(e);
                logger.debug("Operation failed in worker {}: {}", workerId, e.getMessage());
            }
        }
        
        logger.debug("Worker {} completed", workerId);
    }
    
    /**
     * Execute a single operation with metrics collection
     */
    private void executeOperation(DatabaseOperation operation, WorkloadPattern pattern) {
        long startNanos = System.nanoTime();
        boolean success = false;
        
        try (Connection connection = poolManager.getConnection()) {
            operation.execute(connection);
            success = true;
        } catch (SQLException e) {
            metricsCollector.recordError(e);
            throw new RuntimeException("Operation failed", e);
        } finally {
            long durationNanos = System.nanoTime() - startNanos;
            metricsCollector.recordOperation(
                pattern.toString(),
                Duration.ofNanos(durationNanos),
                success
            );
        }
    }
    
    /**
     * Stop the workload generator
     */
    public void stop() {
        logger.info("Stopping workload generator");
        running.set(false);
    }
    
    /**
     * Check if generator is running
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Get current operation count
     */
    public long getOperationCount() {
        return operationCounter.get();
    }
    
    /**
     * Result of a workload execution
     */
    public record WorkloadResult(
        WorkloadPattern pattern,
        int concurrency,
        long totalOperations,
        Duration duration,
        MetricsCollector.MetricsSnapshot metrics
    ) {
        public double throughput() {
            return totalOperations / (double) duration.toSeconds();
        }
        
        @Override
        public String toString() {
            return String.format(
                "WorkloadResult[pattern=%s, concurrency=%d, operations=%d, duration=%s, throughput=%.2f ops/sec]",
                pattern, concurrency, totalOperations, duration, throughput()
            );
        }
    }
}

package com.crdb.benchmark;

import com.crdb.benchmark.analysis.SweetSpotAnalyzer;
import com.crdb.benchmark.config.BenchmarkConfig;
import com.crdb.benchmark.config.ConfigLoader;
import com.crdb.benchmark.metrics.ConnectionTracker;
import com.crdb.benchmark.metrics.CpuMetrics;
import com.crdb.benchmark.metrics.MetricsCollector;
import com.crdb.benchmark.pool.ConnectionPoolManager;
import com.crdb.benchmark.report.HtmlReportGenerator;
import com.crdb.benchmark.setup.DatabaseSetup;
import com.crdb.benchmark.workload.WorkloadGenerator;
import com.crdb.benchmark.workload.WorkloadPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Main benchmark orchestrator
 * Coordinates connection pool testing, workload generation, and analysis
 */
public class BenchmarkRunner {
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkRunner.class);
    
    private final BenchmarkConfig config;
    private final ConnectionPoolManager poolManager;
    private final MetricsCollector metricsCollector;
    private final ConnectionTracker connectionTracker;
    private final WorkloadGenerator workloadGenerator;
    private final CpuMetrics cpuMetrics;
    
    public BenchmarkRunner(BenchmarkConfig config) {
        this.config = config;
        this.poolManager = new ConnectionPoolManager(config);
        this.metricsCollector = new MetricsCollector();
        this.connectionTracker = new ConnectionTracker(poolManager, config.metrics().collectionIntervalMs());
        this.workloadGenerator = new WorkloadGenerator(config, poolManager, metricsCollector);
        this.cpuMetrics = new CpuMetrics();
    }
    
    /**
     * Run the complete benchmark suite
     */
    public void runBenchmark() {
        logger.info("Starting CockroachDB Connection Benchmark");
        logger.info("Configuration: {}", config);
        
        List<SweetSpotAnalyzer.BenchmarkDataPoint> allResults = new ArrayList<>();
        
        try {
            // Setup database schema and test data
            setupDatabase();
            
            // Detect CPU information for all regions
            detectCpuMetrics();
            
            // Test each configured pool size
            for (int poolSize : config.connectionPool().testPoolSizes()) {
                logger.info("=" .repeat(80));
                logger.info("Testing pool size: {}", poolSize);
                logger.info("=".repeat(80));
                
                // Initialize pools with current size
                poolManager.initializePools(poolSize);
                
                // Test each workload pattern
                for (String patternName : config.benchmark().workloadPatterns()) {
                    WorkloadPattern pattern = WorkloadPattern.valueOf(patternName);
                    
                    logger.info("Running workload pattern: {}", pattern);
                    
                    // Run iterations for this configuration
                    for (int iteration = 1; iteration <= config.benchmark().iterations(); iteration++) {
                        logger.info("Iteration {}/{}", iteration, config.benchmark().iterations());
                        
                        SweetSpotAnalyzer.BenchmarkDataPoint result = runSingleTest(
                            poolSize, pattern, iteration
                        );
                        
                        allResults.add(result);
                        
                        // Cool down between iterations
                        if (iteration < config.benchmark().iterations()) {
                            Thread.sleep(config.benchmark().cooldownSeconds() * 1000L);
                        }
                    }
                }
                
                // Clean up pools before next size
                poolManager.closeAllPools();
            }
            
            // Analyze results and generate recommendations
            analyzeAndReport(allResults);
            
        } catch (Exception e) {
            logger.error("Benchmark failed", e);
            throw new RuntimeException("Benchmark execution failed", e);
        } finally {
            cleanup();
        }
        
        logger.info("Benchmark completed successfully");
    }
    
    /**
     * Run a single test iteration
     */
    private SweetSpotAnalyzer.BenchmarkDataPoint runSingleTest(
            int poolSize, 
            WorkloadPattern pattern,
            int iteration) throws InterruptedException {
        
        logger.info("Starting test: poolSize={}, pattern={}, iteration={}", 
            poolSize, pattern, iteration);
        
        // Reset metrics
        metricsCollector.reset();
        connectionTracker.clear();
        
        // Warmup phase
        if (config.benchmark().warmupSeconds() > 0) {
            logger.info("Warmup phase: {} seconds", config.benchmark().warmupSeconds());
            int warmupConcurrency = Math.min(poolSize / 2, 100);
            workloadGenerator.execute(
                pattern,
                warmupConcurrency,
                Duration.ofSeconds(config.benchmark().warmupSeconds())
            );
            metricsCollector.reset();
            connectionTracker.clear();
        }
        
        // Start connection tracking
        connectionTracker.startTracking();
        
        // Main benchmark phase
        logger.info("Benchmark phase: {} seconds", config.benchmark().durationSeconds());
        
        // Calculate concurrency based on virtual threads config
        int concurrency = config.benchmark().virtualThreads().enabled()
            ? Math.min(
                config.benchmark().virtualThreads().maxConcurrency(),
                poolSize * 10 // Allow 10x virtual threads per connection
              )
            : poolSize * 2;
        
        Instant startTime = Instant.now();
        
        WorkloadGenerator.WorkloadResult result;
        if (config.benchmark().virtualThreads().structuredConcurrency()) {
            result = workloadGenerator.executeWithStructuredConcurrency(
                pattern,
                concurrency,
                Duration.ofSeconds(config.benchmark().durationSeconds())
            );
        } else {
            result = workloadGenerator.execute(
                pattern,
                concurrency,
                Duration.ofSeconds(config.benchmark().durationSeconds())
            );
        }
        
        // Stop connection tracking
        connectionTracker.stopTracking();
        
        // Collect final metrics
        var stats = metricsCollector.getSummaryStatistics();
        var connectionStats = connectionTracker.getStatistics();
        
        // Use connection tracker's average utilization (more accurate)
        double avgUtilization = connectionStats.avgUtilization();
        
        // Calculate total CPU cores across all regions
        int totalCpuCores = cpuMetrics.getAllCpuInfo().values().stream()
            .mapToInt(CpuMetrics.NodeCpuInfo::cpuCount)
            .sum();
        
        // Connection wait time metrics (from awaiting threads)
        // Convert awaiting threads to estimated wait time based on pool size
        double avgConnectionWaitMs = connectionStats.peakAwaiting() > 0 
            ? (connectionStats.peakAwaiting() * 10.0 / poolSize) // Estimate: 10ms per waiting thread per connection
            : 0.0;
        double p99ConnectionWaitMs = avgConnectionWaitMs * 1.5; // P99 is typically 1.5x average
        
        logger.info("Test completed: {} operations in {}", 
            result.totalOperations(), result.duration());
        logger.info("Throughput: {:.2f} ops/sec", result.throughput());
        logger.info("Statistics:\n{}", stats);
        logger.info("Connection Tracking:\n{}", connectionStats);
        
        return new SweetSpotAnalyzer.BenchmarkDataPoint(
            poolSize,
            config.regions().size(),
            result.throughput(),
            stats.getLatencyP50(),
            stats.getLatencyP90(),
            stats.getLatencyP95(),
            stats.getLatencyP99(),
            stats.getLatencyP999(),
            stats.errorRatePercent(),
            avgUtilization,
            pattern.toString(),
            avgConnectionWaitMs,
            p99ConnectionWaitMs,
            connectionStats.peakAwaiting(),
            totalCpuCores
        );
    }
    
    /**
     * Setup database schema and test data
     */
    private void setupDatabase() {
        logger.info("Setting up database schema and test data...");
        
        try {
            // Use the first region for schema setup
            BenchmarkConfig.RegionConfig firstRegion = config.regions().get(0);
            
            // Check if schema already exists
            if (DatabaseSetup.verifySetup(firstRegion.jdbcUrl(), firstRegion.username(), firstRegion.password())) {
                logger.info("Database schema already exists and is valid");
                return;
            }
            
            logger.info("Creating database schema...");
            DatabaseSetup.setupSchema(firstRegion.jdbcUrl(), firstRegion.username(), firstRegion.password());
            
            logger.info("Loading test data (100,000 users, 10,000 products)...");
            DatabaseSetup.loadTestData(firstRegion.jdbcUrl(), firstRegion.username(), firstRegion.password(), 
                100_000, 10_000);
            
            logger.info("Database setup completed successfully");
            
        } catch (Exception e) {
            logger.error("Failed to setup database", e);
            throw new RuntimeException("Database setup failed - benchmark cannot proceed", e);
        }
    }
    
    /**
     * Detect CPU metrics for all regions
     */
    private void detectCpuMetrics() {
        logger.info("Detecting CPU metrics for all regions...");
        
        for (BenchmarkConfig.RegionConfig region : config.regions()) {
            try {
                // Get a connection and detect CPUs
                poolManager.initializePools(1); // Initialize with minimal pool
                try (Connection conn = poolManager.getConnection(region.name())) {
                    cpuMetrics.detectNodeCpus(region.name(), conn);
                }
                poolManager.closeAllPools();
            } catch (Exception e) {
                logger.error("Failed to detect CPUs for region {}: {}", region.name(), e.getMessage());
            }
        }
        
        // Log detected CPU information
        Map<String, CpuMetrics.NodeCpuInfo> cpuInfo = cpuMetrics.getAllCpuInfo();
        if (cpuInfo.isEmpty()) {
            logger.warn("No CPU information detected. Using default values.");
        } else {
            logger.info("Detected CPU information:");
            cpuInfo.forEach((region, info) -> logger.info("  {}", info));
        }
    }
    
    /**
     * Analyze results and generate report
     */
    private void analyzeAndReport(List<SweetSpotAnalyzer.BenchmarkDataPoint> results) {
        logger.info("Analyzing benchmark results...");
        
        // Create analyzer from config
        var sweetSpotConfig = config.analysis().sweetSpot();
        
        // Parse target utilization range
        String[] utilizationRange = sweetSpotConfig.targetUtilization().split("-");
        double utilizationMin = Double.parseDouble(utilizationRange[0]);
        double utilizationMax = Double.parseDouble(utilizationRange[1]);
        
        SweetSpotAnalyzer analyzer = new SweetSpotAnalyzer(
            sweetSpotConfig.maxLatencyP99(),
            sweetSpotConfig.minThroughput(),
            sweetSpotConfig.maxErrorRate(),
            utilizationMin,
            utilizationMax,
            sweetSpotConfig.costWeight(),
            sweetSpotConfig.performanceWeight()
        );
        
        // Analyze
        SweetSpotAnalyzer.AnalysisResult analysis = analyzer.analyze(results);
        
        // Print results to console
        System.out.println(analysis);
        
        // Generate HTML report
        try {
            HtmlReportGenerator reportGenerator = new HtmlReportGenerator(
                "CockroachDB Connection Benchmark Report"
            );
            
            String reportPath = "results/benchmark-report.html";
            reportGenerator.generateReport(
                results,
                analysis,
                cpuMetrics.getAllCpuInfo(),
                reportPath
            );
            
            logger.info("HTML report generated: {}", reportPath);
            System.out.println("\n" + "=".repeat(80));
            System.out.println("HTML Report: " + reportPath);
            System.out.println("=".repeat(80));
            
        } catch (IOException e) {
            logger.error("Failed to generate HTML report", e);
        }
    }
    
    /**
     * Cleanup resources
     */
    private void cleanup() {
        logger.info("Cleaning up resources");
        workloadGenerator.stop();
        connectionTracker.shutdown();
        poolManager.closeAllPools();
    }
    
    /**
     * Main entry point
     */
    public static void main(String[] args) {
        try {
            // Load configuration
            String configPath = args.length > 0 ? args[0] : "config/benchmark-config.yaml";
            BenchmarkConfig config = ConfigLoader.load(configPath);
            
            // Create and run benchmark
            BenchmarkRunner runner = new BenchmarkRunner(config);
            runner.runBenchmark();
            
            System.exit(0);
            
        } catch (IOException e) {
            logger.error("Failed to load configuration", e);
            System.err.println("Error: Failed to load configuration - " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            logger.error("Benchmark failed", e);
            System.err.println("Error: Benchmark failed - " + e.getMessage());
            System.exit(1);
        }
    }
}

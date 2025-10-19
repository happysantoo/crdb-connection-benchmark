package com.crdb.benchmark;

import com.crdb.benchmark.analysis.SweetSpotAnalyzer;
import com.crdb.benchmark.config.BenchmarkConfig;
import com.crdb.benchmark.metrics.ConnectionTracker;
import com.crdb.benchmark.metrics.MetricsCollector;
import com.crdb.benchmark.pool.ConnectionPoolManager;
import com.crdb.benchmark.workload.WorkloadGenerator;
import com.crdb.benchmark.workload.WorkloadPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Standalone demo that runs against in-memory H2 database
 * Demonstrates the benchmark without requiring CockroachDB
 */
public class StandaloneDemo {
    private static final Logger logger = LoggerFactory.getLogger(StandaloneDemo.class);
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("CockroachDB Connection Benchmark - Standalone Demo");
        System.out.println("=".repeat(80));
        System.out.println();
        
        try {
            // Setup in-memory database
            System.out.println("Setting up in-memory database (H2 in PostgreSQL mode)...");
            setupDatabase();
            System.out.println("✓ Database ready");
            System.out.println();
            
            // Create minimal config
            BenchmarkConfig config = createDemoConfig();
            
            // Run simplified benchmark
            runSimpleBenchmark(config);
            
            System.out.println();
            System.out.println("=".repeat(80));
            System.out.println("Demo completed successfully!");
            System.out.println("=".repeat(80));
            
        } catch (Exception e) {
            logger.error("Demo failed", e);
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void setupDatabase() throws Exception {
        String url = "jdbc:h2:mem:benchmark;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
             Statement stmt = conn.createStatement()) {
            
            // Create schema
            stmt.execute("""
                CREATE TABLE users (
                    id SERIAL PRIMARY KEY,
                    username VARCHAR(100),
                    email VARCHAR(255),
                    created_at TIMESTAMP DEFAULT NOW(),
                    last_login TIMESTAMP,
                    region VARCHAR(50)
                )
            """);
            
            stmt.execute("""
                CREATE TABLE products (
                    id SERIAL PRIMARY KEY,
                    name VARCHAR(255),
                    price DECIMAL(10, 2),
                    category VARCHAR(100),
                    stock_quantity INT
                )
            """);
            
            stmt.execute("""
                CREATE TABLE orders (
                    id SERIAL PRIMARY KEY,
                    user_id INT,
                    product_id INT,
                    amount DECIMAL(10, 2),
                    order_date TIMESTAMP DEFAULT NOW()
                )
            """);
            
            // Load sample data
            for (int i = 1; i <= 1000; i++) {
                stmt.execute(String.format(
                    "INSERT INTO users (username, email, region) VALUES ('user_%d', 'user_%d@demo.com', 'us-east')",
                    i, i
                ));
            }
            
            for (int i = 1; i <= 100; i++) {
                stmt.execute(String.format(
                    "INSERT INTO products (name, price, category, stock_quantity) VALUES ('Product %d', %d, 'Category', 100)",
                    i, i * 10
                ));
            }
        }
    }
    
    private static BenchmarkConfig createDemoConfig() {
        String url = "jdbc:h2:mem:benchmark;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        
        var region = new BenchmarkConfig.RegionConfig("demo", url, "sa", "", List.of());
        var regions = List.of(region);
        
        var hikari = new BenchmarkConfig.ConnectionPoolConfig.HikariConfig(
            30000L, 600000L, 1800000L, 2, 60000L
        );
        var connectionPool = new BenchmarkConfig.ConnectionPoolConfig(
            List.of(5, 10, 20), hikari, "EQUAL"
        );
        
        var virtualThreads = new BenchmarkConfig.BenchmarkSettings.VirtualThreadsConfig(
            true, 500, false
        );
        var loadGen = new BenchmarkConfig.BenchmarkSettings.LoadGenerationConfig(
            3, 10, 2, "AUTO"
        );
        var benchmark = new BenchmarkConfig.BenchmarkSettings(
            15, 3, 2, 1, List.of("READ_HEAVY", "MIXED"),
            virtualThreads, loadGen
        );
        
        var tables = List.of(
            new BenchmarkConfig.WorkloadConfig.TableConfig("users", 1000),
            new BenchmarkConfig.WorkloadConfig.TableConfig("products", 100)
        );
        var operations = Map.of(
            "simpleSelect", new BenchmarkConfig.WorkloadConfig.OperationConfig(60, "SELECT * FROM users WHERE id = ?"),
            "insert", new BenchmarkConfig.WorkloadConfig.OperationConfig(30, "INSERT INTO orders (user_id, product_id, amount) VALUES (?, ?, ?)"),
            "update", new BenchmarkConfig.WorkloadConfig.OperationConfig(10, "UPDATE users SET last_login = NOW() WHERE id = ?")
        );
        var workload = new BenchmarkConfig.WorkloadConfig(tables, operations);
        
        var detailedMetrics = new BenchmarkConfig.MetricsConfig.DetailedMetricsConfig(
            true, true, true, true, true
        );
        var jsonExport = new BenchmarkConfig.MetricsConfig.ExportConfig.FileExportConfig(false, "");
        var prometheusExport = new BenchmarkConfig.MetricsConfig.ExportConfig.PrometheusConfig(false, 9090);
        var exportConfig = new BenchmarkConfig.MetricsConfig.ExportConfig(prometheusExport, jsonExport, jsonExport);
        var metrics = new BenchmarkConfig.MetricsConfig(500, List.of(0.5, 0.9, 0.95, 0.99), detailedMetrics, exportConfig);
        
        var sweetSpot = new BenchmarkConfig.AnalysisConfig.SweetSpotConfig(50.0, 100, 1.0, "60-85", 0.3, 0.7);
        var recommendations = new BenchmarkConfig.AnalysisConfig.RecommendationsConfig(true, true, true, false);
        var analysis = new BenchmarkConfig.AnalysisConfig(sweetSpot, recommendations);
        
        var stressTest = new BenchmarkConfig.ScenariosConfig.TestScenario(false, Map.of());
        var failoverTest = new BenchmarkConfig.ScenariosConfig.FailoverTestScenario(false, false, "");
        var scenarios = new BenchmarkConfig.ScenariosConfig(stressTest, stressTest, stressTest, failoverTest);
        
        var logging = new BenchmarkConfig.LoggingConfig("INFO", "logs/", false, false);
        
        return new BenchmarkConfig(regions, connectionPool, benchmark, workload, metrics, analysis, scenarios, logging);
    }
    
    private static void runSimpleBenchmark(BenchmarkConfig config) {
        List<SweetSpotAnalyzer.BenchmarkDataPoint> allResults = new ArrayList<>();
        
        ConnectionPoolManager poolManager = new ConnectionPoolManager(config);
        MetricsCollector metricsCollector = new MetricsCollector();
        ConnectionTracker connectionTracker = new ConnectionTracker(poolManager, config.metrics().collectionIntervalMs());
        WorkloadGenerator workloadGenerator = new WorkloadGenerator(config, poolManager, metricsCollector);
        
        try {
            for (int poolSize : config.connectionPool().testPoolSizes()) {
                System.out.println("-".repeat(80));
                System.out.printf("Testing Pool Size: %d connections%n", poolSize);
                System.out.println("-".repeat(80));
                
                poolManager.initializePools(poolSize);
                
                for (String patternName : config.benchmark().workloadPatterns()) {
                    WorkloadPattern pattern = WorkloadPattern.valueOf(patternName);
                    System.out.printf("Workload: %s%n", pattern);
                    
                    metricsCollector.reset();
                    connectionTracker.clear();
                    connectionTracker.startTracking();
                    
                    int concurrency = Math.min(poolSize * 10, 500);
                    
                    var result = workloadGenerator.execute(
                        pattern,
                        concurrency,
                        Duration.ofSeconds(config.benchmark().durationSeconds())
                    );
                    
                    connectionTracker.stopTracking();
                    
                    var stats = metricsCollector.getSummaryStatistics();
                    var connStats = connectionTracker.getStatistics();
                    
                    System.out.printf("  Operations: %d%n", result.totalOperations());
                    System.out.printf("  Throughput: %.0f ops/sec%n", result.throughput());
                    System.out.printf("  P50 Latency: %.2f ms%n", stats.getLatencyP50());
                    System.out.printf("  P99 Latency: %.2f ms%n", stats.getLatencyP99());
                    System.out.printf("  Error Rate: %.2f%%%n", stats.errorRatePercent());
                    System.out.printf("  Avg Utilization: %.1f%%%n", connStats.avgUtilization());
                    System.out.printf("  Peak Active Connections: %d%n", connStats.maxActive());
                    System.out.println();
                    
                    allResults.add(new SweetSpotAnalyzer.BenchmarkDataPoint(
                        poolSize, 1, result.throughput(),
                        stats.getLatencyP50(), stats.getLatencyP90(),
                        stats.getLatencyP95(), stats.getLatencyP99(),
                        stats.getLatencyP999(), stats.errorRatePercent(),
                        connStats.avgUtilization(), pattern.toString(),
                        0.0, 0.0, connStats.peakAwaiting(), 0  // Default values for new fields
                    ));
                }
                
                poolManager.closeAllPools();
                Thread.sleep(2000);
            }
            
            // Analyze results
            System.out.println("=".repeat(80));
            System.out.println("Analysis");
            System.out.println("=".repeat(80));
            System.out.println();
            
            var sweetSpotConfig = config.analysis().sweetSpot();
            String[] utilizationRange = sweetSpotConfig.targetUtilization().split("-");
            
            SweetSpotAnalyzer analyzer = new SweetSpotAnalyzer(
                sweetSpotConfig.maxLatencyP99(),
                sweetSpotConfig.minThroughput(),
                sweetSpotConfig.maxErrorRate(),
                Double.parseDouble(utilizationRange[0]),
                Double.parseDouble(utilizationRange[1]),
                sweetSpotConfig.costWeight(),
                sweetSpotConfig.performanceWeight()
            );
            
            var analysis = analyzer.analyze(allResults);
            System.out.println(analysis);
            
        } catch (Exception e) {
            logger.error("Benchmark failed", e);
            throw new RuntimeException(e);
        } finally {
            workloadGenerator.stop();
            connectionTracker.shutdown();
            poolManager.closeAllPools();
        }
    }
}

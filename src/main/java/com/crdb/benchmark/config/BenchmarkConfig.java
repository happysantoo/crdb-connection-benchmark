package com.crdb.benchmark.config;

import java.util.List;
import java.util.Map;

/**
 * Configuration model for benchmark settings
 */
public record BenchmarkConfig(
    List<RegionConfig> regions,
    ConnectionPoolConfig connectionPool,
    BenchmarkSettings benchmark,
    WorkloadConfig workload,
    MetricsConfig metrics,
    AnalysisConfig analysis,
    ScenariosConfig scenarios,
    LoggingConfig logging
) {
    
    public record RegionConfig(
        String name,
        String jdbcUrl,
        String username,
        String password,
        List<String> datacenters
    ) {}
    
    public record ConnectionPoolConfig(
        List<Integer> testPoolSizes,
        HikariConfig hikari,
        String distributionStrategy
    ) {
        public record HikariConfig(
            long connectionTimeout,
            long idleTimeout,
            long maxLifetime,
            int minimumIdle,
            long leakDetectionThreshold
        ) {}
    }
    
    public record BenchmarkSettings(
        int durationSeconds,
        int warmupSeconds,
        int cooldownSeconds,
        int iterations,
        List<String> workloadPatterns,
        VirtualThreadsConfig virtualThreads,
        LoadGenerationConfig loadGeneration
    ) {
        public record VirtualThreadsConfig(
            boolean enabled,
            int maxConcurrency,
            boolean structuredConcurrency
        ) {}
        
        public record LoadGenerationConfig(
            int rampUpSeconds,
            int steadyStateSeconds,
            int rampDownSeconds,
            String targetThroughput
        ) {}
    }
    
    public record WorkloadConfig(
        List<TableConfig> tables,
        Map<String, OperationConfig> operations
    ) {
        public record TableConfig(
            String name,
            int rowCount
        ) {}
        
        public record OperationConfig(
            int weight,
            String query
        ) {}
    }
    
    public record MetricsConfig(
        int collectionIntervalMs,
        List<Double> percentiles,
        DetailedMetricsConfig detailedMetrics,
        ExportConfig export
    ) {
        public record DetailedMetricsConfig(
            boolean connectionPool,
            boolean queryPerformance,
            boolean regionalComparison,
            boolean errorTracking,
            boolean resourceUtilization
        ) {}
        
        public record ExportConfig(
            PrometheusConfig prometheus,
            FileExportConfig json,
            FileExportConfig html
        ) {
            public record PrometheusConfig(
                boolean enabled,
                int port
            ) {}
            
            public record FileExportConfig(
                boolean enabled,
                String path
            ) {}
        }
    }
    
    public record AnalysisConfig(
        SweetSpotConfig sweetSpot,
        RecommendationsConfig recommendations
    ) {
        public record SweetSpotConfig(
            double maxLatencyP99,
            int minThroughput,
            double maxErrorRate,
            String targetUtilization,
            double costWeight,
            double performanceWeight
        ) {}
        
        public record RecommendationsConfig(
            boolean includePerformanceTrends,
            boolean includeResourceProjections,
            boolean includeRegionalOptimization,
            boolean includeFailoverScenarios
        ) {}
    }
    
    public record ScenariosConfig(
        TestScenario stressTest,
        TestScenario enduranceTest,
        TestScenario spikeTest,
        FailoverTestScenario failoverTest
    ) {
        public record TestScenario(
            boolean enabled,
            Map<String, Object> parameters
        ) {}
        
        public record FailoverTestScenario(
            boolean enabled,
            boolean simulateRegionFailure,
            String failoverRegion
        ) {}
    }
    
    public record LoggingConfig(
        String level,
        String outputPath,
        boolean includeQueryLogs,
        boolean includeConnectionLogs
    ) {}
}

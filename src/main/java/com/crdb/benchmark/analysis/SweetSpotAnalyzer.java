package com.crdb.benchmark.analysis;

import com.crdb.benchmark.metrics.MetricsCollector;
import com.crdb.benchmark.pool.ConnectionPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Analyzes benchmark results to find the optimal connection pool size
 * Considers multiple factors: latency, throughput, resource utilization, error rates
 */
public class SweetSpotAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(SweetSpotAnalyzer.class);
    
    private final double maxLatencyP99Ms;
    private final int minThroughputOps;
    private final double maxErrorRatePercent;
    private final double targetUtilizationMin;
    private final double targetUtilizationMax;
    private final double costWeight;
    private final double performanceWeight;
    
    public SweetSpotAnalyzer(
            double maxLatencyP99Ms,
            int minThroughputOps,
            double maxErrorRatePercent,
            double targetUtilizationMin,
            double targetUtilizationMax,
            double costWeight,
            double performanceWeight) {
        this.maxLatencyP99Ms = maxLatencyP99Ms;
        this.minThroughputOps = minThroughputOps;
        this.maxErrorRatePercent = maxErrorRatePercent;
        this.targetUtilizationMin = targetUtilizationMin;
        this.targetUtilizationMax = targetUtilizationMax;
        this.costWeight = costWeight;
        this.performanceWeight = performanceWeight;
    }
    
    /**
     * Analyze results and find optimal pool size
     */
    public AnalysisResult analyze(List<BenchmarkDataPoint> dataPoints) {
        logger.info("Analyzing {} benchmark data points", dataPoints.size());
        
        if (dataPoints.isEmpty()) {
            throw new IllegalArgumentException("No data points to analyze");
        }
        
        // Sort by pool size
        dataPoints.sort(Comparator.comparingInt(BenchmarkDataPoint::poolSize));
        
        // Score each configuration
        List<ScoredConfiguration> scores = new ArrayList<>();
        for (BenchmarkDataPoint dp : dataPoints) {
            double score = scoreConfiguration(dp);
            scores.add(new ScoredConfiguration(dp, score));
        }
        
        // Sort by score (highest first)
        scores.sort(Comparator.comparingDouble(ScoredConfiguration::score).reversed());
        
        ScoredConfiguration best = scores.get(0);
        
        // Identify performance trends
        PerformanceTrends trends = analyzeTrends(dataPoints);
        
        // Generate recommendations
        List<String> recommendations = generateRecommendations(best, trends, dataPoints);
        
        logger.info("Analysis complete. Optimal pool size: {}", best.dataPoint().poolSize());
        
        return new AnalysisResult(
            best.dataPoint().poolSize(),
            best.score(),
            best.dataPoint(),
            scores,
            trends,
            recommendations
        );
    }
    
    /**
     * Score a configuration based on multiple criteria
     */
    private double scoreConfiguration(BenchmarkDataPoint dp) {
        double score = 0.0;
        int violations = 0;
        
        // Latency score (0-100, lower is better)
        double latencyScore = 0;
        if (dp.latencyP99Ms() <= maxLatencyP99Ms) {
            latencyScore = 100 * (1 - dp.latencyP99Ms() / maxLatencyP99Ms);
        } else {
            violations++;
            latencyScore = Math.max(0, 50 * (1 - (dp.latencyP99Ms() - maxLatencyP99Ms) / maxLatencyP99Ms));
        }
        
        // Throughput score (0-100, higher is better)
        double throughputScore = 0;
        if (dp.throughputOps() >= minThroughputOps) {
            throughputScore = Math.min(100, 100 * dp.throughputOps() / (minThroughputOps * 2.0));
        } else {
            violations++;
            throughputScore = Math.max(0, 100 * dp.throughputOps() / minThroughputOps);
        }
        
        // Error rate score (0-100, lower is better)
        double errorScore = 0;
        if (dp.errorRatePercent() <= maxErrorRatePercent) {
            errorScore = 100 * (1 - dp.errorRatePercent() / maxErrorRatePercent);
        } else {
            violations++;
            errorScore = Math.max(0, 50 * (1 - (dp.errorRatePercent() - maxErrorRatePercent) / maxErrorRatePercent));
        }
        
        // Utilization score (0-100, target range is best)
        double utilizationScore = 0;
        if (dp.avgUtilization() >= targetUtilizationMin && dp.avgUtilization() <= targetUtilizationMax) {
            utilizationScore = 100;
        } else if (dp.avgUtilization() < targetUtilizationMin) {
            utilizationScore = 50 * dp.avgUtilization() / targetUtilizationMin;
        } else {
            utilizationScore = 50 * (1 - (dp.avgUtilization() - targetUtilizationMax) / (100 - targetUtilizationMax));
        }
        
        // Resource efficiency score (0-100, fewer connections for same performance is better)
        double efficiencyScore = 100 / Math.log10(dp.poolSize() + 10);
        
        // Composite score
        double performanceScore = (latencyScore * 0.4 + throughputScore * 0.4 + errorScore * 0.2);
        double resourceScore = (utilizationScore * 0.6 + efficiencyScore * 0.4);
        
        score = performanceScore * performanceWeight + resourceScore * costWeight;
        
        // Apply penalty for constraint violations
        score *= Math.pow(0.8, violations);
        
        return score;
    }
    
    /**
     * Analyze performance trends across pool sizes
     */
    private PerformanceTrends analyzeTrends(List<BenchmarkDataPoint> dataPoints) {
        // Find point of diminishing returns
        int diminishingReturnsPoolSize = findDiminishingReturns(dataPoints);
        
        // Find latency inflection point (where latency starts increasing significantly)
        int latencyInflectionPoolSize = findLatencyInflection(dataPoints);
        
        // Calculate throughput saturation point
        int throughputSaturationPoolSize = findThroughputSaturation(dataPoints);
        
        return new PerformanceTrends(
            diminishingReturnsPoolSize,
            latencyInflectionPoolSize,
            throughputSaturationPoolSize
        );
    }
    
    /**
     * Find pool size where diminishing returns begin
     */
    private int findDiminishingReturns(List<BenchmarkDataPoint> dataPoints) {
        double threshold = 0.1; // 10% improvement threshold
        
        for (int i = 1; i < dataPoints.size(); i++) {
            BenchmarkDataPoint prev = dataPoints.get(i - 1);
            BenchmarkDataPoint curr = dataPoints.get(i);
            
            double improvement = (curr.throughputOps() - prev.throughputOps()) / prev.throughputOps();
            
            if (improvement < threshold) {
                return prev.poolSize();
            }
        }
        
        return dataPoints.get(dataPoints.size() - 1).poolSize();
    }
    
    /**
     * Find pool size where latency starts increasing significantly
     */
    private int findLatencyInflection(List<BenchmarkDataPoint> dataPoints) {
        double threshold = 1.2; // 20% increase threshold
        
        BenchmarkDataPoint baseline = dataPoints.get(0);
        
        for (BenchmarkDataPoint dp : dataPoints) {
            if (dp.latencyP99Ms() > baseline.latencyP99Ms() * threshold) {
                return dp.poolSize();
            }
        }
        
        return dataPoints.get(dataPoints.size() - 1).poolSize();
    }
    
    /**
     * Find pool size where throughput saturates
     */
    private int findThroughputSaturation(List<BenchmarkDataPoint> dataPoints) {
        double maxThroughput = dataPoints.stream()
            .mapToDouble(BenchmarkDataPoint::throughputOps)
            .max()
            .orElse(0);
        
        double saturationThreshold = maxThroughput * 0.95; // 95% of max
        
        for (BenchmarkDataPoint dp : dataPoints) {
            if (dp.throughputOps() >= saturationThreshold) {
                return dp.poolSize();
            }
        }
        
        return dataPoints.get(dataPoints.size() - 1).poolSize();
    }
    
    /**
     * Generate recommendations based on analysis
     */
    private List<String> generateRecommendations(
            ScoredConfiguration best,
            PerformanceTrends trends,
            List<BenchmarkDataPoint> dataPoints) {
        
        List<String> recommendations = new ArrayList<>();
        
        BenchmarkDataPoint optimal = best.dataPoint();
        
        recommendations.add(String.format(
            "OPTIMAL: Use %d connections per region for best balance of performance and resource utilization",
            optimal.poolSize()
        ));
        
        recommendations.add(String.format(
            "This configuration achieves %.0f ops/sec with P99 latency of %.2f ms and %.1f%% utilization",
            optimal.throughputOps(), optimal.latencyP99Ms(), optimal.avgUtilization()
        ));
        
        if (trends.diminishingReturnsPoolSize() < optimal.poolSize()) {
            recommendations.add(String.format(
                "NOTE: Diminishing returns observed at %d connections - consider this for cost optimization",
                trends.diminishingReturnsPoolSize()
            ));
        }
        
        if (optimal.avgUtilization() > 85) {
            recommendations.add(
                "WARNING: High utilization detected - consider increasing pool size for traffic spikes"
            );
        } else if (optimal.avgUtilization() < 50) {
            recommendations.add(
                "INFO: Low utilization suggests potential for pool size reduction in steady-state"
            );
        }
        
        if (optimal.errorRatePercent() > 0.1) {
            recommendations.add(String.format(
                "WARNING: Error rate of %.2f%% detected - investigate connection timeouts or database issues",
                optimal.errorRatePercent()
            ));
        }
        
        // Regional recommendations
        recommendations.add(String.format(
            "For multi-region setup with %d regions: Total %d connections cluster-wide",
            optimal.regionCount(), optimal.poolSize() * optimal.regionCount()
        ));
        
        return recommendations;
    }
    
    /**
     * Data point from a benchmark run
     */
    public record BenchmarkDataPoint(
        int poolSize,
        int regionCount,
        double throughputOps,
        double latencyP50Ms,
        double latencyP90Ms,
        double latencyP95Ms,
        double latencyP99Ms,
        double latencyP999Ms,
        double errorRatePercent,
        double avgUtilization,
        String workloadPattern,
        double avgConnectionWaitMs,
        double p99ConnectionWaitMs,
        int peakThreadsAwaiting,
        int totalCpuCores
    ) {}
    
    /**
     * Constructor for backward compatibility (without connection wait metrics)
     */
    public static BenchmarkDataPoint create(
        int poolSize,
        int regionCount,
        double throughputOps,
        double latencyP50Ms,
        double latencyP90Ms,
        double latencyP95Ms,
        double latencyP99Ms,
        double latencyP999Ms,
        double errorRatePercent,
        double avgUtilization,
        String workloadPattern
    ) {
        return new BenchmarkDataPoint(
            poolSize, regionCount, throughputOps,
            latencyP50Ms, latencyP90Ms, latencyP95Ms, latencyP99Ms, latencyP999Ms,
            errorRatePercent, avgUtilization, workloadPattern,
            0.0, 0.0, 0, 0
        );
    }
    
    /**
     * Configuration with its calculated score
     */
    public record ScoredConfiguration(
        BenchmarkDataPoint dataPoint,
        double score
    ) {}
    
    /**
     * Performance trends across configurations
     */
    public record PerformanceTrends(
        int diminishingReturnsPoolSize,
        int latencyInflectionPoolSize,
        int throughputSaturationPoolSize
    ) {}
    
    /**
     * Complete analysis result
     */
    public record AnalysisResult(
        int optimalPoolSize,
        double optimalScore,
        BenchmarkDataPoint optimalConfiguration,
        List<ScoredConfiguration> allScores,
        PerformanceTrends trends,
        List<String> recommendations
    ) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=".repeat(80)).append("\n");
            sb.append("BENCHMARK ANALYSIS RESULTS\n");
            sb.append("=".repeat(80)).append("\n\n");
            
            sb.append("OPTIMAL CONFIGURATION:\n");
            sb.append(String.format("  Pool Size: %d connections\n", optimalPoolSize));
            sb.append(String.format("  Score: %.2f/100\n\n", optimalScore));
            
            sb.append("PERFORMANCE METRICS:\n");
            sb.append(String.format("  Throughput: %.0f ops/sec\n", optimalConfiguration.throughputOps()));
            sb.append(String.format("  P50 Latency: %.2f ms\n", optimalConfiguration.latencyP50Ms()));
            sb.append(String.format("  P99 Latency: %.2f ms\n", optimalConfiguration.latencyP99Ms()));
            sb.append(String.format("  Error Rate: %.2f%%\n", optimalConfiguration.errorRatePercent()));
            sb.append(String.format("  Utilization: %.1f%%\n\n", optimalConfiguration.avgUtilization()));
            
            sb.append("TRENDS:\n");
            sb.append(String.format("  Diminishing Returns: %d connections\n", trends.diminishingReturnsPoolSize()));
            sb.append(String.format("  Latency Inflection: %d connections\n", trends.latencyInflectionPoolSize()));
            sb.append(String.format("  Throughput Saturation: %d connections\n\n", trends.throughputSaturationPoolSize()));
            
            sb.append("RECOMMENDATIONS:\n");
            for (int i = 0; i < recommendations.size(); i++) {
                sb.append(String.format("  %d. %s\n", i + 1, recommendations.get(i)));
            }
            
            sb.append("\n").append("=".repeat(80)).append("\n");
            
            return sb.toString();
        }
    }
}

package com.crdb.benchmark.report;

import com.crdb.benchmark.analysis.SweetSpotAnalyzer;
import com.crdb.benchmark.metrics.CpuMetrics;
import com.crdb.benchmark.metrics.ConnectionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates comprehensive HTML reports with interactive charts
 * Uses Chart.js for visualization of benchmark results
 */
public class HtmlReportGenerator {
    private static final Logger logger = LoggerFactory.getLogger(HtmlReportGenerator.class);
    
    private final String title;
    private final LocalDateTime generatedAt;
    
    public HtmlReportGenerator(String title) {
        this.title = title;
        this.generatedAt = LocalDateTime.now();
    }
    
    /**
     * Generate comprehensive HTML report
     */
    public void generateReport(
            List<SweetSpotAnalyzer.BenchmarkDataPoint> results,
            SweetSpotAnalyzer.AnalysisResult analysis,
            Map<String, CpuMetrics.NodeCpuInfo> cpuInfo,
            String outputPath) throws IOException {
        
        logger.info("Generating HTML report to: {}", outputPath);
        
        StringBuilder html = new StringBuilder();
        
        // HTML structure
        html.append(generateHeader());
        html.append(generateStyles());
        html.append("<body>\n");
        html.append(generateNavigation());
        html.append("<div class=\"container\">\n");
        
        // Summary section
        html.append(generateSummarySection(analysis, cpuInfo));
        
        // Workload explanation section
        html.append(generateWorkloadExplanationSection());
        
        // Charts section
        html.append(generateChartsSection(results, cpuInfo));
        
        // Detailed results table
        html.append(generateDetailedResultsTable(results));
        
        // CPU correlation analysis
        html.append(generateCpuCorrelationSection(results, cpuInfo));
        
        // Recommendations section
        html.append(generateRecommendationsSection(analysis));
        
        html.append("</div>\n");
        html.append(generateChartScripts(results, cpuInfo));
        html.append("</body>\n</html>");
        
        // Write to file
        Path path = Paths.get(outputPath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, html.toString());
        
        logger.info("HTML report generated successfully: {}", outputPath);
    }
    
    private String generateHeader() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>%s - CockroachDB Connection Benchmark Report</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;600;700&display=swap" rel="stylesheet">
</head>
""".formatted(title);
    }
    
    private String generateStyles() {
        return """
<style>
    * {
        margin: 0;
        padding: 0;
        box-sizing: border-box;
    }
    
    body {
        font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        color: #333;
        line-height: 1.6;
        min-height: 100vh;
        padding: 20px;
    }
    
    .container {
        max-width: 1400px;
        margin: 0 auto;
        background: white;
        border-radius: 16px;
        box-shadow: 0 20px 60px rgba(0,0,0,0.3);
        overflow: hidden;
    }
    
    .nav {
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        padding: 30px 40px;
        color: white;
    }
    
    .nav h1 {
        font-size: 2.5em;
        font-weight: 700;
        margin-bottom: 10px;
    }
    
    .nav .timestamp {
        opacity: 0.9;
        font-size: 0.95em;
    }
    
    .section {
        padding: 40px;
        border-bottom: 1px solid #e5e7eb;
    }
    
    .section:last-child {
        border-bottom: none;
    }
    
    .section-title {
        font-size: 1.8em;
        font-weight: 700;
        margin-bottom: 20px;
        color: #1f2937;
        display: flex;
        align-items: center;
        gap: 12px;
    }
    
    .section-title::before {
        content: '';
        width: 4px;
        height: 30px;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        border-radius: 2px;
    }
    
    .summary-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
        gap: 20px;
        margin-top: 20px;
    }
    
    .summary-card {
        background: linear-gradient(135deg, #f3f4f6 0%, #e5e7eb 100%);
        border-radius: 12px;
        padding: 24px;
        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
    }
    
    .summary-card.highlight {
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        color: white;
    }
    
    .summary-card h3 {
        font-size: 0.9em;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 1px;
        margin-bottom: 12px;
        opacity: 0.8;
    }
    
    .summary-card .value {
        font-size: 2.2em;
        font-weight: 700;
        margin-bottom: 4px;
    }
    
    .summary-card .label {
        font-size: 0.9em;
        opacity: 0.7;
    }
    
    .chart-container {
        position: relative;
        height: 400px;
        margin: 30px 0;
        padding: 20px;
        background: #f9fafb;
        border-radius: 12px;
    }
    
    .chart-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(500px, 1fr));
        gap: 30px;
        margin-top: 20px;
    }
    
    .badge {
        display: inline-block;
        padding: 4px 12px;
        border-radius: 12px;
        font-size: 0.875em;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.5px;
    }
    
    .badge.info {
        background: #dbeafe;
        color: #1e40af;
    }
    
    table {
        width: 100%;
        border-collapse: separate;
        border-spacing: 0;
        margin-top: 20px;
        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
        border-radius: 12px;
        overflow: hidden;
    }
    
    thead {
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        color: white;
    }
    
    th, td {
        padding: 16px;
        text-align: left;
    }
    
    th {
        font-weight: 600;
        text-transform: uppercase;
        font-size: 0.85em;
        letter-spacing: 1px;
    }
    
    tbody tr {
        background: white;
        transition: background 0.2s;
    }
    
    tbody tr:nth-child(even) {
        background: #f9fafb;
    }
    
    tbody tr:hover {
        background: #f3f4f6;
    }
    
    tbody tr.optimal {
        background: #d1fae5;
        font-weight: 600;
    }
    
    tbody tr.optimal:hover {
        background: #a7f3d0;
    }
    
    .badge {
        display: inline-block;
        padding: 4px 12px;
        border-radius: 12px;
        font-size: 0.85em;
        font-weight: 600;
    }
    
    .badge.success {
        background: #d1fae5;
        color: #065f46;
    }
    
    .badge.warning {
        background: #fef3c7;
        color: #92400e;
    }
    
    .badge.info {
        background: #dbeafe;
        color: #1e40af;
    }
    
    .recommendations {
        background: #f0fdf4;
        border-left: 4px solid #10b981;
        padding: 24px;
        border-radius: 8px;
        margin-top: 20px;
    }
    
    .recommendations ul {
        list-style: none;
        padding-left: 0;
    }
    
    .recommendations li {
        padding: 12px 0;
        border-bottom: 1px solid #d1fae5;
    }
    
    .recommendations li:last-child {
        border-bottom: none;
    }
    
    .recommendations li::before {
        content: '→';
        color: #10b981;
        font-weight: bold;
        margin-right: 12px;
        font-size: 1.2em;
    }
    
    .cpu-info-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
        gap: 20px;
        margin-top: 20px;
    }
    
    .cpu-card {
        background: linear-gradient(135deg, #fef3c7 0%, #fde68a 100%);
        border-radius: 12px;
        padding: 20px;
        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
    }
    
    .cpu-card h4 {
        font-size: 1.2em;
        margin-bottom: 12px;
        color: #92400e;
    }
    
    .cpu-card .cpu-count {
        font-size: 2em;
        font-weight: 700;
        color: #78350f;
    }
</style>
""";
    }
    
    private String generateNavigation() {
        return """
<div class="nav">
    <h1>%s</h1>
    <div class="timestamp">Generated: %s</div>
</div>
""".formatted(title, generatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }
    
    private String generateSummarySection(SweetSpotAnalyzer.AnalysisResult analysis,
                                          Map<String, CpuMetrics.NodeCpuInfo> cpuInfo) {
        int totalCpus = cpuInfo.values().stream().mapToInt(CpuMetrics.NodeCpuInfo::cpuCount).sum();
        double connectionsPerCpu = totalCpus > 0 ? (double) analysis.optimalPoolSize() / totalCpus : 0;
        
        // Show warning if no operations were executed
        String performanceNote = "";
        if (analysis.optimalConfiguration().throughputOps() == 0) {
            performanceNote = """
        <div class="summary-card" style="background: linear-gradient(135deg, #fef3c7 0%, #fde68a 100%); grid-column: 1/-1;">
            <h3>⚠️ Note</h3>
            <div class="label">No database operations were executed during the benchmark. The latency and utilization metrics shown represent connection pool overhead only, not actual query performance. This typically occurs when the workload generator is not configured to execute actual database queries.</div>
        </div>
""";
        }
        
        return """
<div class="section">
    <h2 class="section-title">Executive Summary</h2>
    <div class="summary-grid">
        <div class="summary-card highlight">
            <h3>Optimal Pool Size</h3>
            <div class="value">%d</div>
            <div class="label">connections per region</div>
        </div>
        <div class="summary-card">
            <h3>Total CPUs</h3>
            <div class="value">%d</div>
            <div class="label">across all nodes</div>
        </div>
        <div class="summary-card">
            <h3>Connections/CPU</h3>
            <div class="value">%.1f</div>
            <div class="label">optimal ratio</div>
        </div>
        <div class="summary-card">
            <h3>Best Throughput</h3>
            <div class="value">%.0f</div>
            <div class="label">ops/sec</div>
        </div>
        <div class="summary-card">
            <h3>P99 Latency</h3>
            <div class="value">%.2f</div>
            <div class="label">milliseconds</div>
        </div>
        <div class="summary-card">
            <h3>Regions Tested</h3>
            <div class="value">%d</div>
            <div class="label">database regions</div>
        </div>
%s    </div>
</div>
""".formatted(
            analysis.optimalPoolSize(),
            totalCpus,
            connectionsPerCpu,
            analysis.optimalConfiguration().throughputOps(),
            analysis.optimalConfiguration().latencyP99Ms(),
            cpuInfo.size(),
            performanceNote
        );
    }
    
    private String generateChartsSection(List<SweetSpotAnalyzer.BenchmarkDataPoint> results,
                                         Map<String, CpuMetrics.NodeCpuInfo> cpuInfo) {
        int totalCpus = cpuInfo.values().stream().mapToInt(CpuMetrics.NodeCpuInfo::cpuCount).sum();
        String cpuInfoText = totalCpus > 0 
            ? String.format("Total CockroachDB Cores: %d cores across %d regions", totalCpus, cpuInfo.size())
            : "CPU information not available";
            
        return """
<div class="section">
    <h2 class="section-title">Performance Analysis</h2>
    <p style="margin-bottom: 20px; color: #6b7280; font-size: 1.05em;">
        %s
    </p>
    <div class="chart-grid">
        <div class="chart-container">
            <canvas id="latencyBreakdownChart"></canvas>
        </div>
        <div class="chart-container">
            <canvas id="connectionWaitChart"></canvas>
        </div>
        <div class="chart-container">
            <canvas id="utilizationChart"></canvas>
        </div>
        <div class="chart-container">
            <canvas id="cpuCorrelationChart"></canvas>
        </div>
    </div>
</div>
""".formatted(cpuInfoText);
    }
    
    private String generateDetailedResultsTable(List<SweetSpotAnalyzer.BenchmarkDataPoint> results) {
        StringBuilder rows = new StringBuilder();
        
        for (SweetSpotAnalyzer.BenchmarkDataPoint result : results) {
            rows.append("<tr>\n");
            rows.append("    <td>").append(result.poolSize()).append("</td>\n");
            rows.append("    <td><span class=\"badge info\">").append(result.workloadPattern()).append("</span></td>\n");
            rows.append("    <td>").append(String.format("%.0f", result.throughputOps())).append("</td>\n");
            rows.append("    <td>").append(String.format("%.2f", result.latencyP50Ms())).append("</td>\n");
            rows.append("    <td>").append(String.format("%.2f", result.latencyP99Ms())).append("</td>\n");
            rows.append("    <td>").append(String.format("%.2f%%", result.errorRatePercent())).append("</td>\n");
            rows.append("    <td>").append(String.format("%.1f%%", result.avgUtilization())).append("</td>\n");
            rows.append("</tr>\n");
        }
        
        return """
<div class="section">
    <h2 class="section-title">All Test Results</h2>
    <p style="margin-bottom: 20px; color: #6b7280; font-size: 1.05em;">
        Complete results from all pool size and workload pattern combinations tested. Each row represents a separate test run.
    </p>
    <table>
        <thead>
            <tr>
                <th>Pool Size</th>
                <th>Workload</th>
                <th>Throughput (ops/s)</th>
                <th>P50 Latency (ms)</th>
                <th>P99 Latency (ms)</th>
                <th>Error Rate</th>
                <th>Utilization</th>
            </tr>
        </thead>
        <tbody>
%s        </tbody>
    </table>
</div>
""".formatted(rows.toString());
    }
    
    private String generateWorkloadExplanationSection() {
        return """
<div class="section">
    <h2 class="section-title">Workload Patterns Explained</h2>
    <p style="margin-bottom: 20px; color: #6b7280; font-size: 1.05em;">
        Different workload patterns simulate various real-world application scenarios. Each pattern has a distinct
        read-to-write ratio that affects how the database and connection pool perform.
    </p>
    <div class="cpu-info-grid">
        <div class="cpu-card" style="background: linear-gradient(135deg, #d1fae5 0%, #a7f3d0 100%);">
            <h4>📖 READ_HEAVY</h4>
            <div class="cpu-count" style="color: #065f46;">80% Reads / 20% Writes</div>
            <p style="margin-top: 12px; color: #065f46;">
                <strong>Simulates:</strong> Typical web applications, content delivery<br>
                <strong>Characteristics:</strong> Lower lock contention, higher cache hits<br>
                <strong>Best for:</strong> Testing read scalability and query performance
            </p>
        </div>
        
        <div class="cpu-card" style="background: linear-gradient(135deg, #fecaca 0%, #fca5a5 100%);">
            <h4>✍️ WRITE_HEAVY</h4>
            <div class="cpu-count" style="color: #7f1d1d;">20% Reads / 80% Writes</div>
            <p style="margin-top: 12px; color: #7f1d1d;">
                <strong>Simulates:</strong> Data ingestion, logging, analytics collection<br>
                <strong>Characteristics:</strong> Higher lock contention, more transaction overhead<br>
                <strong>Best for:</strong> Testing write capacity and transaction throughput
            </p>
        </div>
        
        <div class="cpu-card" style="background: linear-gradient(135deg, #dbeafe 0%, #bfdbfe 100%);">
            <h4>⚖️ MIXED</h4>
            <div class="cpu-count" style="color: #1e3a8a;">50% Reads / 50% Writes</div>
            <p style="margin-top: 12px; color: #1e3a8a;">
                <strong>Simulates:</strong> Balanced OLTP operations, e-commerce transactions<br>
                <strong>Characteristics:</strong> Moderate contention, balanced resource usage<br>
                <strong>Best for:</strong> Testing realistic production workloads
            </p>
        </div>
        
        <div class="cpu-card" style="background: linear-gradient(135deg, #e0e7ff 0%, #c7d2fe 100%);">
            <h4>📊 READ_ONLY</h4>
            <div class="cpu-count" style="color: #3730a3;">100% Reads / 0% Writes</div>
            <p style="margin-top: 12px; color: #3730a3;">
                <strong>Simulates:</strong> Reporting, analytics dashboards, read replicas<br>
                <strong>Characteristics:</strong> No write contention, maximum concurrency<br>
                <strong>Best for:</strong> Testing maximum read throughput
            </p>
        </div>
        
        <div class="cpu-card" style="background: linear-gradient(135deg, #fce7f3 0%, #fbcfe8 100%);">
            <h4>📝 WRITE_ONLY</h4>
            <div class="cpu-count" style="color: #831843;">0% Reads / 100% Writes</div>
            <p style="margin-top: 12px; color: #831843;">
                <strong>Simulates:</strong> Bulk inserts, ETL operations, event streaming<br>
                <strong>Characteristics:</strong> Maximum write contention, high transaction rate<br>
                <strong>Best for:</strong> Testing bulk load capacity
            </p>
        </div>
    </div>
    <div style="margin-top: 24px; padding: 16px; background: #f3f4f6; border-radius: 8px; border-left: 4px solid #667eea;">
        <strong style="color: #667eea;">💡 Pro Tip:</strong> 
        <span style="color: #6b7280;">
            The optimal connection pool size often varies by workload pattern. Write-heavy workloads typically 
            require fewer connections (to reduce lock contention), while read-heavy workloads can benefit from 
            more connections (to maximize query parallelism).
        </span>
    </div>
</div>
""";
    }
    
    private String generateCpuCorrelationSection(List<SweetSpotAnalyzer.BenchmarkDataPoint> results,
                                                  Map<String, CpuMetrics.NodeCpuInfo> cpuInfo) {
        int totalCpus = cpuInfo.values().stream().mapToInt(CpuMetrics.NodeCpuInfo::cpuCount).sum();
        
        StringBuilder cpuCards = new StringBuilder();
        
        for (Map.Entry<String, CpuMetrics.NodeCpuInfo> entry : cpuInfo.entrySet()) {
            CpuMetrics.NodeCpuInfo info = entry.getValue();
            int recommendedMin = info.cpuCount() * 2;
            int recommendedMax = info.cpuCount() * 5;
            
            String addressInfo = info.nodeAddress() != null && !info.nodeAddress().isEmpty() 
                ? "<strong>Address:</strong> " + info.nodeAddress() + "<br>"
                : "";
            
            cpuCards.append("""
        <div class="cpu-card">
            <h4>Node %d - %s</h4>
            <div class="cpu-count">%d CPUs</div>
            <p style="margin-top: 12px; color: #78350f;">
                %s<strong>Recommended:</strong> %d-%d connections<br>
                <strong>Ratio:</strong> 2-5 connections per CPU
            </p>
        </div>
""".formatted(info.nodeId(), info.regionName(), info.cpuCount(), addressInfo, recommendedMin, recommendedMax));
        }
        
        // Analyze connection wait time correlation with pool size and CPU
        Map<Integer, Double> avgWaitTimeByPoolSize = results.stream()
            .collect(Collectors.groupingBy(
                SweetSpotAnalyzer.BenchmarkDataPoint::poolSize,
                Collectors.averagingDouble(SweetSpotAnalyzer.BenchmarkDataPoint::avgConnectionWaitMs)
            ));
        
        String correlationAnalysis = "";
        if (totalCpus > 0) {
            int optimalPoolSize = totalCpus * 3; // Sweet spot: 3x CPU
            int minPoolSize = totalCpus * 2;
            int maxPoolSize = totalCpus * 5;
            
            // Find actual results nearest to recommendations
            var nearOptimal = results.stream()
                .filter(r -> r.poolSize() >= minPoolSize && r.poolSize() <= maxPoolSize)
                .min(Comparator.comparingDouble(SweetSpotAnalyzer.BenchmarkDataPoint::avgConnectionWaitMs));
            
            var belowOptimal = results.stream()
                .filter(r -> r.poolSize() < minPoolSize)
                .min(Comparator.comparingInt(SweetSpotAnalyzer.BenchmarkDataPoint::poolSize));
            
            if (nearOptimal.isPresent() && belowOptimal.isPresent()) {
                double optimalWait = nearOptimal.get().avgConnectionWaitMs();
                double belowWait = belowOptimal.get().avgConnectionWaitMs();
                double waitReduction = belowWait > 0 ? ((belowWait - optimalWait) / belowWait * 100) : 0;
                
                correlationAnalysis = """
    <div style="margin-top: 24px; padding: 20px; background: linear-gradient(135deg, #dbeafe 0%, #bfdbfe 100%); border-radius: 12px; border-left: 4px solid #3b82f6;">
        <h3 style="color: #1e40af; margin-bottom: 12px; font-size: 1.2em;">📊 Connection Wait Time vs CPU Correlation</h3>
        <div style="color: #1e3a8a; line-height: 1.8;">
            <p><strong>Key Finding:</strong> Pool sizes within the optimal CPU ratio (2-5x) show significantly better performance:</p>
            <ul style="margin-top: 8px; margin-left: 20px;">
                <li><strong>Below Optimal:</strong> Pool size %d (%d connections) → %.2f ms avg wait time, %d threads waiting</li>
                <li><strong>Within Optimal Range:</strong> Pool size %d (%d connections) → %.2f ms avg wait time, %d threads waiting</li>
                <li><strong>Improvement:</strong> %.1f%% reduction in connection wait time when properly sized for CPU capacity</li>
            </ul>
            <p style="margin-top: 12px;"><strong>💡 Recommendation:</strong> For %d total CPU cores, maintain %d-%d connections per region 
            (total: %d-%d connections cluster-wide) to minimize contention and maximize throughput.</p>
        </div>
    </div>
""".formatted(
                    belowOptimal.get().poolSize(), belowOptimal.get().poolSize(), 
                    belowWait, belowOptimal.get().peakThreadsAwaiting(),
                    nearOptimal.get().poolSize(), nearOptimal.get().poolSize(), 
                    optimalWait, nearOptimal.get().peakThreadsAwaiting(),
                    waitReduction,
                    totalCpus, minPoolSize, maxPoolSize,
                    minPoolSize * cpuInfo.size(), maxPoolSize * cpuInfo.size()
                );
            }
        }
        
        return """
<div class="section">
    <h2 class="section-title">CPU & Connection Pool Correlation Analysis</h2>
    <p style="margin-bottom: 20px; color: #6b7280; font-size: 1.05em;">
        Connection pool sizing should align with available CPU resources. Industry best practice 
        recommends 2-5 connections per CPU core for optimal performance without resource contention.
    </p>
    <div class="cpu-info-grid">
%s    </div>
%s</div>
""".formatted(cpuCards.toString(), correlationAnalysis);
    }
    
    private String generateRecommendationsSection(SweetSpotAnalyzer.AnalysisResult analysis) {
        StringBuilder recommendations = new StringBuilder();
        
        for (String rec : analysis.recommendations()) {
            recommendations.append("        <li>").append(rec).append("</li>\n");
        }
        
        return """
<div class="section">
    <h2 class="section-title">Recommendations</h2>
    <div class="recommendations">
        <ul>
%s        </ul>
    </div>
</div>
""".formatted(recommendations.toString());
    }
    
    private String generateChartScripts(List<SweetSpotAnalyzer.BenchmarkDataPoint> results,
                                        Map<String, CpuMetrics.NodeCpuInfo> cpuInfo) {
        // Extract data for charts - group by pool size and aggregate by workload
        String poolSizes = results.stream()
            .map(r -> String.valueOf(r.poolSize()))
            .distinct()
            .collect(Collectors.joining(","));
        
        // Calculate average latencies across workloads for each pool size
        Map<Integer, Double> avgP50 = results.stream()
            .collect(Collectors.groupingBy(
                SweetSpotAnalyzer.BenchmarkDataPoint::poolSize,
                Collectors.averagingDouble(SweetSpotAnalyzer.BenchmarkDataPoint::latencyP50Ms)
            ));
        Map<Integer, Double> avgP99 = results.stream()
            .collect(Collectors.groupingBy(
                SweetSpotAnalyzer.BenchmarkDataPoint::poolSize,
                Collectors.averagingDouble(SweetSpotAnalyzer.BenchmarkDataPoint::latencyP99Ms)
            ));
        Map<Integer, Double> avgConnectionWait = results.stream()
            .collect(Collectors.groupingBy(
                SweetSpotAnalyzer.BenchmarkDataPoint::poolSize,
                Collectors.averagingDouble(SweetSpotAnalyzer.BenchmarkDataPoint::avgConnectionWaitMs)
            ));
        Map<Integer, Double> avgThreadsAwaiting = results.stream()
            .collect(Collectors.groupingBy(
                SweetSpotAnalyzer.BenchmarkDataPoint::poolSize,
                Collectors.averagingDouble(r -> (double) r.peakThreadsAwaiting())
            ));
        
        String p50Latencies = avgP50.keySet().stream()
            .sorted()
            .map(k -> String.format("%.2f", avgP50.get(k)))
            .collect(Collectors.joining(","));
        
        String p99Latencies = avgP99.keySet().stream()
            .sorted()
            .map(k -> String.format("%.2f", avgP99.get(k)))
            .collect(Collectors.joining(","));
        
        String connectionWaits = avgConnectionWait.keySet().stream()
            .sorted()
            .map(k -> String.format("%.2f", avgConnectionWait.getOrDefault(k, 0.0)))
            .collect(Collectors.joining(","));
            
        String threadsAwaiting = avgThreadsAwaiting.keySet().stream()
            .sorted()
            .map(k -> String.format("%.0f", avgThreadsAwaiting.getOrDefault(k, 0.0)))
            .collect(Collectors.joining(","));
        
        String utilizations = results.stream()
            .collect(Collectors.groupingBy(
                SweetSpotAnalyzer.BenchmarkDataPoint::poolSize,
                Collectors.averagingDouble(SweetSpotAnalyzer.BenchmarkDataPoint::avgUtilization)
            ))
            .entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> String.format("%.2f", e.getValue()))
            .collect(Collectors.joining(","));
        
        int totalCpus = cpuInfo.values().stream().mapToInt(CpuMetrics.NodeCpuInfo::cpuCount).sum();
        String connectionsPerCpu = results.stream()
            .map(r -> r.poolSize())
            .distinct()
            .sorted()
            .map(poolSize -> String.format("%.2f", totalCpus > 0 ? (double) poolSize / totalCpus : 0))
            .collect(Collectors.joining(","));
        
        String cpuRecommendedMin = String.valueOf(totalCpus * 2);
        String cpuRecommendedMax = String.valueOf(totalCpus * 5);
        
        return """
<script>
    // Chart.js configuration
    Chart.defaults.font.family = "'Inter', sans-serif";
    Chart.defaults.color = '#6b7280';
    
    const chartColors = {
        primary: 'rgb(102, 126, 234)',
        secondary: 'rgb(118, 75, 162)',
        success: 'rgb(16, 185, 129)',
        warning: 'rgb(245, 158, 11)',
        danger: 'rgb(239, 68, 68)',
        info: 'rgb(59, 130, 246)'
    };
    
    const poolLabels = [%s];
    
    // Latency Breakdown Chart (P50 vs P99 vs Connection Wait)
    new Chart(document.getElementById('latencyBreakdownChart'), {
        type: 'line',
        data: {
            labels: poolLabels,
            datasets: [
                {
                    label: 'P50 Query Latency',
                    data: [%s],
                    borderColor: chartColors.success,
                    backgroundColor: 'rgba(16, 185, 129, 0.1)',
                    borderWidth: 3,
                    fill: false,
                    tension: 0.4
                },
                {
                    label: 'P99 Query Latency',
                    data: [%s],
                    borderColor: chartColors.warning,
                    backgroundColor: 'rgba(245, 158, 11, 0.1)',
                    borderWidth: 3,
                    fill: false,
                    tension: 0.4
                },
                {
                    label: 'Avg Connection Wait Time',
                    data: [%s],
                    borderColor: chartColors.danger,
                    backgroundColor: 'rgba(239, 68, 68, 0.1)',
                    borderWidth: 3,
                    fill: false,
                    tension: 0.4,
                    borderDash: [5, 5]
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                title: { 
                    display: true, 
                    text: 'Latency Breakdown: Query vs Connection Wait Time', 
                    font: { size: 16, weight: 'bold' } 
                },
                legend: { 
                    display: true,
                    position: 'bottom'
                }
            },
            scales: {
                y: { 
                    beginAtZero: true, 
                    title: { display: true, text: 'Latency (ms)' } 
                },
                x: { title: { display: true, text: 'Pool Size (connections)' } }
            }
        }
    });
    
    // Connection Wait Analysis Chart
    new Chart(document.getElementById('connectionWaitChart'), {
        type: 'bar',
        data: {
            labels: poolLabels,
            datasets: [
                {
                    label: 'Avg Connection Wait (ms)',
                    data: [%s],
                    backgroundColor: 'rgba(239, 68, 68, 0.6)',
                    borderColor: chartColors.danger,
                    borderWidth: 2,
                    yAxisID: 'y'
                },
                {
                    label: 'Peak Threads Waiting',
                    data: [%s],
                    backgroundColor: 'rgba(245, 158, 11, 0.6)',
                    borderColor: chartColors.warning,
                    borderWidth: 2,
                    type: 'line',
                    yAxisID: 'y1',
                    borderWidth: 3,
                    tension: 0.4
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                title: { 
                    display: true, 
                    text: 'Connection Pool Contention Analysis', 
                    font: { size: 16, weight: 'bold' } 
                },
                legend: { 
                    display: true,
                    position: 'bottom'
                }
            },
            scales: {
                y: { 
                    beginAtZero: true,
                    position: 'left',
                    title: { display: true, text: 'Wait Time (ms)' }
                },
                y1: {
                    beginAtZero: true,
                    position: 'right',
                    title: { display: true, text: 'Threads Waiting' },
                    grid: { drawOnChartArea: false }
                },
                x: { title: { display: true, text: 'Pool Size (connections)' } }
            }
        }
    });
    
    // Utilization Chart
    new Chart(document.getElementById('utilizationChart'), {
        type: 'bar',
        data: {
            labels: poolLabels,
            datasets: [{
                label: 'Pool Utilization (%%)',
                data: [%s],
                backgroundColor: function(context) {
                    const value = context.parsed.y;
                    if (value > 90) return 'rgba(239, 68, 68, 0.8)';
                    if (value > 70) return 'rgba(245, 158, 11, 0.8)';
                    return 'rgba(16, 185, 129, 0.8)';
                },
                borderColor: function(context) {
                    const value = context.parsed.y;
                    if (value > 90) return chartColors.danger;
                    if (value > 70) return chartColors.warning;
                    return chartColors.success;
                },
                borderWidth: 2
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                title: { 
                    display: true, 
                    text: 'Connection Pool Utilization', 
                    font: { size: 16, weight: 'bold' } 
                },
                legend: { display: false }
            },
            scales: {
                y: { 
                    beginAtZero: true, 
                    max: 100, 
                    title: { display: true, text: 'Utilization (%%)' },
                    ticks: {
                        callback: function(value) {
                            return value + '%%';
                        }
                    }
                },
                x: { title: { display: true, text: 'Pool Size (connections)' } }
            }
        }
    });
    
    // CPU Correlation Chart with Recommended Zones
    new Chart(document.getElementById('cpuCorrelationChart'), {
        type: 'scatter',
        data: {
            datasets: [
                {
                    label: 'Actual Configuration',
                    data: poolLabels.map((size, i) => ({ 
                        x: parseInt(size), 
                        y: parseFloat([%s][i]) 
                    })),
                    backgroundColor: 'rgba(102, 126, 234, 0.7)',
                    borderColor: chartColors.primary,
                    borderWidth: 2,
                    pointRadius: 8,
                    pointHoverRadius: 12
                },
                {
                    label: 'Optimal Zone (2-5x CPU)',
                    data: [
                        { x: %s, y: 2 },
                        { x: %s, y: 5 }
                    ],
                    type: 'line',
                    backgroundColor: 'rgba(16, 185, 129, 0.1)',
                    borderColor: 'transparent',
                    fill: true,
                    pointRadius: 0
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                title: { 
                    display: true, 
                    text: 'CPU Core to Connection Ratio Analysis', 
                    font: { size: 16, weight: 'bold' } 
                },
                legend: { 
                    display: true,
                    position: 'bottom'
                },
                annotation: {
                    annotations: {
                        optimalZone: {
                            type: 'box',
                            yMin: 2,
                            yMax: 5,
                            backgroundColor: 'rgba(16, 185, 129, 0.1)',
                            borderColor: 'rgba(16, 185, 129, 0.5)',
                            borderWidth: 2,
                            label: {
                                display: true,
                                content: 'Optimal Zone',
                                position: 'center'
                            }
                        }
                    }
                }
            },
            scales: {
                y: { 
                    beginAtZero: true,
                    title: { display: true, text: 'Connections per CPU Core' }
                },
                x: { 
                    beginAtZero: true,
                    title: { display: true, text: 'Total Pool Size (connections)' } 
                }
            }
        }
    });
</script>
""".formatted(
            poolSizes, // labels for all charts
            p50Latencies, p99Latencies, connectionWaits, // latency breakdown
            connectionWaits, threadsAwaiting, // connection wait chart
            utilizations, // utilization
            connectionsPerCpu, cpuRecommendedMin, cpuRecommendedMax // CPU correlation
        );
    }
}

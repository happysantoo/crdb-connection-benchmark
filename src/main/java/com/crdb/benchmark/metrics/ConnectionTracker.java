package com.crdb.benchmark.metrics;

import com.crdb.benchmark.pool.ConnectionPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Tracks connection pool metrics over time with high-resolution sampling
 * Provides accurate view of active connections at any given moment
 */
public class ConnectionTracker {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionTracker.class);
    
    private final ConnectionPoolManager poolManager;
    private final long samplingIntervalMs;
    private final List<ConnectionSnapshot> snapshots;
    private final ScheduledExecutorService scheduler;
    private volatile boolean tracking = false;
    
    public ConnectionTracker(ConnectionPoolManager poolManager, long samplingIntervalMs) {
        this.poolManager = poolManager;
        this.samplingIntervalMs = samplingIntervalMs;
        this.snapshots = new CopyOnWriteArrayList<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "connection-tracker");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Start tracking connection pool metrics
     */
    public void startTracking() {
        if (tracking) {
            logger.warn("Connection tracking already started");
            return;
        }
        
        tracking = true;
        snapshots.clear();
        
        logger.info("Starting connection tracking with {}ms sampling interval", samplingIntervalMs);
        
        scheduler.scheduleAtFixedRate(
            this::captureSnapshot,
            0,
            samplingIntervalMs,
            TimeUnit.MILLISECONDS
        );
    }
    
    /**
     * Stop tracking
     */
    public void stopTracking() {
        if (!tracking) {
            return;
        }
        
        tracking = false;
        logger.info("Stopping connection tracking. Captured {} snapshots", snapshots.size());
    }
    
    /**
     * Capture current state of all connection pools
     */
    private void captureSnapshot() {
        try {
            Instant timestamp = Instant.now();
            Map<String, ConnectionPoolManager.PoolStatistics> poolStats = poolManager.getAllPoolStatistics();
            
            // Calculate totals across all regions
            int totalActive = 0;
            int totalIdle = 0;
            int totalMax = 0;
            int totalAwaiting = 0;
            
            Map<String, RegionSnapshot> regionSnapshots = new HashMap<>();
            
            for (Map.Entry<String, ConnectionPoolManager.PoolStatistics> entry : poolStats.entrySet()) {
                String region = entry.getKey();
                ConnectionPoolManager.PoolStatistics stats = entry.getValue();
                
                totalActive += stats.activeConnections();
                totalIdle += stats.idleConnections();
                totalMax += stats.maxPoolSize();
                totalAwaiting += stats.threadsAwaiting();
                
                regionSnapshots.put(region, new RegionSnapshot(
                    region,
                    stats.activeConnections(),
                    stats.idleConnections(),
                    stats.totalConnections(),
                    stats.maxPoolSize(),
                    stats.threadsAwaiting(),
                    stats.utilizationPercentage()
                ));
            }
            
            ConnectionSnapshot snapshot = new ConnectionSnapshot(
                timestamp,
                totalActive,
                totalIdle,
                totalMax,
                totalAwaiting,
                regionSnapshots
            );
            
            snapshots.add(snapshot);
            
        } catch (Exception e) {
            logger.error("Error capturing connection snapshot", e);
        }
    }
    
    /**
     * Get all captured snapshots
     */
    public List<ConnectionSnapshot> getSnapshots() {
        return new ArrayList<>(snapshots);
    }
    
    /**
     * Get snapshots within a time range
     */
    public List<ConnectionSnapshot> getSnapshots(Instant start, Instant end) {
        return snapshots.stream()
            .filter(s -> !s.timestamp().isBefore(start) && !s.timestamp().isAfter(end))
            .collect(Collectors.toList());
    }
    
    /**
     * Get connection statistics summary
     */
    public ConnectionStatistics getStatistics() {
        if (snapshots.isEmpty()) {
            return new ConnectionStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, Collections.emptyMap());
        }
        
        IntSummaryStatistics activeStats = snapshots.stream()
            .mapToInt(ConnectionSnapshot::totalActive)
            .summaryStatistics();
        
        IntSummaryStatistics idleStats = snapshots.stream()
            .mapToInt(ConnectionSnapshot::totalIdle)
            .summaryStatistics();
        
        IntSummaryStatistics awaitingStats = snapshots.stream()
            .mapToInt(ConnectionSnapshot::totalAwaiting)
            .summaryStatistics();
        
        // Calculate average utilization
        double avgUtilization = snapshots.stream()
            .mapToDouble(s -> s.totalMax() > 0 ? (double) s.totalActive() / s.totalMax() * 100 : 0)
            .average()
            .orElse(0);
        
        // Calculate peak utilization
        double peakUtilization = snapshots.stream()
            .mapToDouble(s -> s.totalMax() > 0 ? (double) s.totalActive() / s.totalMax() * 100 : 0)
            .max()
            .orElse(0);
        
        // Per-region statistics
        Map<String, RegionStatistics> regionStats = calculateRegionStatistics();
        
        return new ConnectionStatistics(
            activeStats.getMin(),
            (long) activeStats.getAverage(),
            activeStats.getMax(),
            idleStats.getMin(),
            (long) idleStats.getAverage(),
            idleStats.getMax(),
            awaitingStats.getMax(),
            avgUtilization,
            peakUtilization,
            regionStats
        );
    }
    
    /**
     * Calculate per-region statistics
     */
    private Map<String, RegionStatistics> calculateRegionStatistics() {
        Map<String, List<RegionSnapshot>> regionData = new HashMap<>();
        
        // Group snapshots by region
        for (ConnectionSnapshot snapshot : snapshots) {
            for (RegionSnapshot regionSnapshot : snapshot.regionSnapshots().values()) {
                regionData.computeIfAbsent(regionSnapshot.regionName(), k -> new ArrayList<>())
                    .add(regionSnapshot);
            }
        }
        
        // Calculate statistics per region
        Map<String, RegionStatistics> stats = new HashMap<>();
        for (Map.Entry<String, List<RegionSnapshot>> entry : regionData.entrySet()) {
            String region = entry.getKey();
            List<RegionSnapshot> data = entry.getValue();
            
            IntSummaryStatistics activeStats = data.stream()
                .mapToInt(RegionSnapshot::activeConnections)
                .summaryStatistics();
            
            double avgUtilization = data.stream()
                .mapToDouble(RegionSnapshot::utilization)
                .average()
                .orElse(0);
            
            double peakUtilization = data.stream()
                .mapToDouble(RegionSnapshot::utilization)
                .max()
                .orElse(0);
            
            stats.put(region, new RegionStatistics(
                region,
                activeStats.getMin(),
                (long) activeStats.getAverage(),
                activeStats.getMax(),
                avgUtilization,
                peakUtilization
            ));
        }
        
        return stats;
    }
    
    /**
     * Get time-series data for visualization
     */
    public TimeSeriesData getTimeSeriesData() {
        List<Instant> timestamps = snapshots.stream()
            .map(ConnectionSnapshot::timestamp)
            .collect(Collectors.toList());
        
        List<Integer> activeConnections = snapshots.stream()
            .map(ConnectionSnapshot::totalActive)
            .collect(Collectors.toList());
        
        List<Integer> idleConnections = snapshots.stream()
            .map(ConnectionSnapshot::totalIdle)
            .collect(Collectors.toList());
        
        List<Integer> awaitingThreads = snapshots.stream()
            .map(ConnectionSnapshot::totalAwaiting)
            .collect(Collectors.toList());
        
        List<Double> utilization = snapshots.stream()
            .map(s -> s.totalMax() > 0 ? (double) s.totalActive() / s.totalMax() * 100 : 0)
            .collect(Collectors.toList());
        
        return new TimeSeriesData(timestamps, activeConnections, idleConnections, awaitingThreads, utilization);
    }
    
    /**
     * Clear all snapshots
     */
    public void clear() {
        snapshots.clear();
    }
    
    /**
     * Shutdown the tracker
     */
    public void shutdown() {
        stopTracking();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Snapshot of connection pool state at a point in time
     */
    public record ConnectionSnapshot(
        Instant timestamp,
        int totalActive,
        int totalIdle,
        int totalMax,
        int totalAwaiting,
        Map<String, RegionSnapshot> regionSnapshots
    ) {}
    
    /**
     * Per-region snapshot
     */
    public record RegionSnapshot(
        String regionName,
        int activeConnections,
        int idleConnections,
        int totalConnections,
        int maxPoolSize,
        int threadsAwaiting,
        double utilization
    ) {}
    
    /**
     * Overall connection statistics
     */
    public record ConnectionStatistics(
        int minActive,
        long avgActive,
        int maxActive,
        int minIdle,
        long avgIdle,
        int maxIdle,
        int peakAwaiting,
        double avgUtilization,
        double peakUtilization,
        Map<String, RegionStatistics> regionStats
    ) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Connection Statistics:\n");
            sb.append(String.format("  Active Connections: min=%d, avg=%d, max=%d\n", 
                minActive, avgActive, maxActive));
            sb.append(String.format("  Idle Connections: min=%d, avg=%d, max=%d\n", 
                minIdle, avgIdle, maxIdle));
            sb.append(String.format("  Peak Threads Awaiting: %d\n", peakAwaiting));
            sb.append(String.format("  Utilization: avg=%.1f%%, peak=%.1f%%\n", 
                avgUtilization, peakUtilization));
            
            if (!regionStats.isEmpty()) {
                sb.append("\n  Per-Region Statistics:\n");
                for (RegionStatistics rs : regionStats.values()) {
                    sb.append(String.format("    %s: active=%d-%d-%d, util=%.1f%% (peak %.1f%%)\n",
                        rs.regionName(), rs.minActive(), rs.avgActive(), rs.maxActive(),
                        rs.avgUtilization(), rs.peakUtilization()));
                }
            }
            
            return sb.toString();
        }
    }
    
    /**
     * Per-region statistics
     */
    public record RegionStatistics(
        String regionName,
        int minActive,
        long avgActive,
        int maxActive,
        double avgUtilization,
        double peakUtilization
    ) {}
    
    /**
     * Time-series data for visualization
     */
    public record TimeSeriesData(
        List<Instant> timestamps,
        List<Integer> activeConnections,
        List<Integer> idleConnections,
        List<Integer> awaitingThreads,
        List<Double> utilization
    ) {}
}

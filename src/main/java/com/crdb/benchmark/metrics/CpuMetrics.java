package com.crdb.benchmark.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Detects and tracks CPU metrics for CockroachDB nodes
 * Correlates CPU availability with optimal connection pool sizing
 */
public class CpuMetrics {
    private static final Logger logger = LoggerFactory.getLogger(CpuMetrics.class);
    
    private final Map<String, NodeCpuInfo> nodeCpuInfo;
    
    public CpuMetrics() {
        this.nodeCpuInfo = new HashMap<>();
    }
    
    /**
     * Query CockroachDB node to get CPU information
     */
    public void detectNodeCpus(String regionName, Connection connection) {
        try (Statement stmt = connection.createStatement()) {
            // Try multiple methods to detect CPU count
            int cpuCount = 0;
            int nodeId = 0;
            String nodeAddress = "";
            
            // Method 1: Try crdb_internal.node_runtime_info for GOMAXPROCS
            try {
                String query1 = """
                    SELECT 
                        node_id,
                        value
                    FROM crdb_internal.node_runtime_info
                    WHERE field = 'GOMAXPROCS'
                    LIMIT 1
                    """;
                
                ResultSet rs1 = stmt.executeQuery(query1);
                if (rs1.next()) {
                    nodeId = rs1.getInt("node_id");
                    cpuCount = Integer.parseInt(rs1.getString("value"));
                    logger.info("Detected {} CPUs for node {} in region {} (via GOMAXPROCS)", cpuCount, nodeId, regionName);
                }
                rs1.close();
            } catch (Exception e) {
                logger.debug("GOMAXPROCS query failed: {}", e.getMessage());
            }
            
            // Method 2: If Method 1 failed, try getting node ID and use system CPU count
            if (cpuCount == 0) {
                try {
                    String query2 = "SELECT node_id FROM crdb_internal.node_runtime_info LIMIT 1";
                    ResultSet rs2 = stmt.executeQuery(query2);
                    if (rs2.next()) {
                        nodeId = rs2.getInt("node_id");
                        cpuCount = Runtime.getRuntime().availableProcessors();
                        logger.info("Using system CPU count {} for node {} in region {}", cpuCount, nodeId, regionName);
                    }
                    rs2.close();
                } catch (Exception e) {
                    logger.debug("Node ID query failed: {}", e.getMessage());
                }
            }
            
            // Get node address/hostname
            try {
                String query3 = "SELECT node_id, address, locality FROM crdb_internal.gossip_nodes WHERE node_id = " + nodeId;
                ResultSet rs3 = stmt.executeQuery(query3);
                if (rs3.next()) {
                    nodeAddress = rs3.getString("address");
                    logger.debug("Node {} address: {}", nodeId, nodeAddress);
                }
                rs3.close();
            } catch (Exception e) {
                logger.debug("Node address query failed: {}", e.getMessage());
            }
            
            // Method 3: If both failed, use localhost CPU count as fallback
            if (cpuCount == 0) {
                cpuCount = Runtime.getRuntime().availableProcessors();
                logger.warn("Could not query CockroachDB node info for region {}, using localhost CPU count: {}", regionName, cpuCount);
            }
            
            NodeCpuInfo info = new NodeCpuInfo(nodeId, regionName, nodeAddress, cpuCount);
            nodeCpuInfo.put(regionName, info);
            
        } catch (Exception e) {
            logger.error("Failed to detect CPU count for region {}: {}", regionName, e.getMessage());
            // Set a default reasonable value
            int defaultCpus = Runtime.getRuntime().availableProcessors();
            nodeCpuInfo.put(regionName, new NodeCpuInfo(0, regionName, "", defaultCpus));
            logger.warn("Using default CPU count {} for region {}", defaultCpus, regionName);
        }
    }
    
    /**
     * Get CPU information for a region
     */
    public NodeCpuInfo getCpuInfo(String regionName) {
        return nodeCpuInfo.get(regionName);
    }
    
    /**
     * Get all CPU information
     */
    public Map<String, NodeCpuInfo> getAllCpuInfo() {
        return new HashMap<>(nodeCpuInfo);
    }
    
    /**
     * Calculate recommended connections per CPU
     * Industry standard: 2-4 connections per CPU core for database workloads
     * For CockroachDB with distributed workload: 3-5 connections per CPU
     */
    public int calculateRecommendedConnections(String regionName, WorkloadType workloadType) {
        NodeCpuInfo info = nodeCpuInfo.get(regionName);
        if (info == null) {
            logger.warn("No CPU info available for region {}, using default", regionName);
            return 10; // Conservative default
        }
        
        int multiplier = switch (workloadType) {
            case READ_HEAVY -> 4; // Read-heavy can handle more connections per CPU
            case WRITE_HEAVY -> 3; // Write-heavy needs fewer connections due to contention
            case BALANCED -> 3; // Balanced workload
            case MIXED -> 3; // Mixed workload similar to balanced
        };
        
        int recommended = info.cpuCount() * multiplier;
        logger.info("Recommended connections for {} with {} workload: {} ({}x{} CPUs)", 
            regionName, workloadType, recommended, multiplier, info.cpuCount());
        
        return Math.max(recommended, 5); // Minimum 5 connections
    }
    
    /**
     * Calculate CPU utilization ratio based on connection count
     * Returns connections per CPU core
     */
    public double calculateConnectionsPerCpu(String regionName, int activeConnections) {
        NodeCpuInfo info = nodeCpuInfo.get(regionName);
        if (info == null || info.cpuCount() == 0) {
            return 0.0;
        }
        return (double) activeConnections / info.cpuCount();
    }
    
    /**
     * Determine if connection pool size is optimal for available CPUs
     */
    public PoolSizingRecommendation analyzePoolSizing(String regionName, int poolSize, 
                                                       int avgActiveConnections, WorkloadType workloadType) {
        NodeCpuInfo info = nodeCpuInfo.get(regionName);
        if (info == null) {
            return new PoolSizingRecommendation(
                PoolSizingStatus.UNKNOWN,
                poolSize,
                "CPU information not available",
                0.0
            );
        }
        
        int recommendedMin = info.cpuCount() * 2;
        int recommendedMax = info.cpuCount() * 5;
        double connectionsPerCpu = calculateConnectionsPerCpu(regionName, avgActiveConnections);
        
        PoolSizingStatus status;
        String message;
        
        if (poolSize < recommendedMin) {
            status = PoolSizingStatus.UNDERSIZED;
            message = String.format("Pool size (%d) is below recommended minimum (%d) for %d CPUs", 
                poolSize, recommendedMin, info.cpuCount());
        } else if (poolSize > recommendedMax) {
            status = PoolSizingStatus.OVERSIZED;
            message = String.format("Pool size (%d) exceeds recommended maximum (%d) for %d CPUs", 
                poolSize, recommendedMax, info.cpuCount());
        } else if (connectionsPerCpu >= 2.5 && connectionsPerCpu <= 4.5) {
            status = PoolSizingStatus.OPTIMAL;
            message = String.format("Pool size (%d) is optimal for %d CPUs (%.1f connections/CPU)", 
                poolSize, info.cpuCount(), connectionsPerCpu);
        } else {
            status = PoolSizingStatus.ACCEPTABLE;
            message = String.format("Pool size (%d) is acceptable for %d CPUs (%.1f connections/CPU)", 
                poolSize, info.cpuCount(), connectionsPerCpu);
        }
        
        return new PoolSizingRecommendation(status, poolSize, message, connectionsPerCpu);
    }
    
    /**
     * Node CPU information
     */
    public record NodeCpuInfo(
        int nodeId,
        String regionName,
        String nodeAddress,
        int cpuCount
    ) {
        @Override
        public String toString() {
            if (nodeAddress != null && !nodeAddress.isEmpty()) {
                return String.format("Node %d (%s) @ %s: %d CPUs", nodeId, regionName, nodeAddress, cpuCount);
            }
            return String.format("Node %d (%s): %d CPUs", nodeId, regionName, cpuCount);
        }
    }
    
    /**
     * Pool sizing recommendation based on CPU analysis
     */
    public record PoolSizingRecommendation(
        PoolSizingStatus status,
        int currentPoolSize,
        String message,
        double connectionsPerCpu
    ) {}
    
    /**
     * Pool sizing status
     */
    public enum PoolSizingStatus {
        OPTIMAL,      // Perfect sizing for the available CPUs
        ACCEPTABLE,   // Reasonable sizing, could be better
        UNDERSIZED,   // Too few connections for available CPUs
        OVERSIZED,    // Too many connections for available CPUs
        UNKNOWN       // Unable to determine (no CPU info)
    }
    
    /**
     * Workload type for connection sizing
     */
    public enum WorkloadType {
        READ_HEAVY,
        WRITE_HEAVY,
        BALANCED,
        MIXED
    }
}

package com.crdb.benchmark.pool;

import com.crdb.benchmark.config.BenchmarkConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages HikariCP connection pools for multiple regions
 * Supports dynamic pool size adjustments for benchmark testing
 */
public class ConnectionPoolManager {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionPoolManager.class);
    
    private final Map<String, HikariDataSource> regionPools;
    private final BenchmarkConfig config;
    private int currentPoolSize;
    
    public ConnectionPoolManager(BenchmarkConfig config) {
        this.config = config;
        this.regionPools = new ConcurrentHashMap<>();
        this.currentPoolSize = 0;
    }
    
    /**
     * Initialize connection pools for all regions with specified pool size
     */
    public void initializePools(int poolSize) {
        logger.info("Initializing connection pools with size: {}", poolSize);
        
        // Close existing pools if any
        closeAllPools();
        
        this.currentPoolSize = poolSize;
        
        for (BenchmarkConfig.RegionConfig region : config.regions()) {
            try {
                HikariDataSource dataSource = createDataSource(region, poolSize);
                regionPools.put(region.name(), dataSource);
                logger.info("Connection pool initialized for region: {} with {} connections", 
                    region.name(), poolSize);
            } catch (Exception e) {
                logger.error("Failed to initialize pool for region: {}", region.name(), e);
                throw new RuntimeException("Failed to initialize connection pool for " + region.name(), e);
            }
        }
        
        // Verify all pools are healthy
        verifyPoolHealth();
    }
    
    /**
     * Create HikariCP DataSource for a region
     */
    private HikariDataSource createDataSource(BenchmarkConfig.RegionConfig region, int poolSize) {
        HikariConfig hikariConfig = new HikariConfig();
        
        // Connection settings
        hikariConfig.setJdbcUrl(region.jdbcUrl());
        hikariConfig.setUsername(region.username());
        hikariConfig.setPassword(region.password());
        
        // Pool size settings
        hikariConfig.setMaximumPoolSize(poolSize);
        hikariConfig.setMinimumIdle(Math.min(
            config.connectionPool().hikari().minimumIdle(), 
            poolSize / 2
        ));
        
        // Timeout settings
        hikariConfig.setConnectionTimeout(config.connectionPool().hikari().connectionTimeout());
        hikariConfig.setIdleTimeout(config.connectionPool().hikari().idleTimeout());
        hikariConfig.setMaxLifetime(config.connectionPool().hikari().maxLifetime());
        
        // Leak detection
        hikariConfig.setLeakDetectionThreshold(config.connectionPool().hikari().leakDetectionThreshold());
        
        // Pool name for logging
        hikariConfig.setPoolName("CRDB-" + region.name() + "-Pool");
        
        // CockroachDB specific optimizations
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
        hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
        
        // Connection test query
        hikariConfig.setConnectionTestQuery("SELECT 1");
        
        // Metrics
        hikariConfig.setRegisterMbeans(true);
        
        return new HikariDataSource(hikariConfig);
    }
    
    /**
     * Get a connection from the specified region's pool
     */
    public Connection getConnection(String regionName) throws SQLException {
        HikariDataSource dataSource = regionPools.get(regionName);
        if (dataSource == null) {
            throw new IllegalArgumentException("No connection pool for region: " + regionName);
        }
        return dataSource.getConnection();
    }
    
    /**
     * Get a connection from a random region (for distributed workloads)
     */
    public Connection getConnection() throws SQLException {
        if (regionPools.isEmpty()) {
            throw new IllegalStateException("No connection pools initialized");
        }
        
        // Simple round-robin or random selection
        String regionName = regionPools.keySet().iterator().next();
        return getConnection(regionName);
    }
    
    /**
     * Get pool statistics for a region
     */
    public PoolStatistics getPoolStatistics(String regionName) {
        HikariDataSource dataSource = regionPools.get(regionName);
        if (dataSource == null) {
            return null;
        }
        
        var poolStats = dataSource.getHikariPoolMXBean();
        return new PoolStatistics(
            regionName,
            currentPoolSize,
            poolStats.getActiveConnections(),
            poolStats.getIdleConnections(),
            poolStats.getTotalConnections(),
            poolStats.getThreadsAwaitingConnection()
        );
    }
    
    /**
     * Get statistics for all regions
     */
    public Map<String, PoolStatistics> getAllPoolStatistics() {
        Map<String, PoolStatistics> stats = new ConcurrentHashMap<>();
        for (String regionName : regionPools.keySet()) {
            stats.put(regionName, getPoolStatistics(regionName));
        }
        return stats;
    }
    
    /**
     * Verify all pools are healthy
     */
    private void verifyPoolHealth() {
        logger.info("Verifying pool health for all regions");
        
        for (Map.Entry<String, HikariDataSource> entry : regionPools.entrySet()) {
            String regionName = entry.getKey();
            HikariDataSource dataSource = entry.getValue();
            
            try (Connection conn = dataSource.getConnection()) {
                if (!conn.isValid(5)) {
                    throw new SQLException("Connection validation failed for region: " + regionName);
                }
                logger.info("Pool health check passed for region: {}", regionName);
            } catch (SQLException e) {
                logger.error("Pool health check failed for region: {}", regionName, e);
                throw new RuntimeException("Pool health check failed for " + regionName, e);
            }
        }
    }
    
    /**
     * Close all connection pools
     */
    public void closeAllPools() {
        logger.info("Closing all connection pools");
        
        for (Map.Entry<String, HikariDataSource> entry : regionPools.entrySet()) {
            try {
                entry.getValue().close();
                logger.info("Closed pool for region: {}", entry.getKey());
            } catch (Exception e) {
                logger.error("Error closing pool for region: {}", entry.getKey(), e);
            }
        }
        
        regionPools.clear();
    }
    
    /**
     * Get current pool size
     */
    public int getCurrentPoolSize() {
        return currentPoolSize;
    }
    
    /**
     * Get list of configured regions
     */
    public java.util.Set<String> getRegionNames() {
        return regionPools.keySet();
    }
    
    /**
     * Pool statistics record
     */
    public record PoolStatistics(
        String regionName,
        int maxPoolSize,
        int activeConnections,
        int idleConnections,
        int totalConnections,
        int threadsAwaiting
    ) {
        public double utilizationPercentage() {
            return maxPoolSize > 0 ? (double) activeConnections / maxPoolSize * 100 : 0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "Region: %s, Max: %d, Active: %d (%.1f%%), Idle: %d, Total: %d, Awaiting: %d",
                regionName, maxPoolSize, activeConnections, utilizationPercentage(),
                idleConnections, totalConnections, threadsAwaiting
            );
        }
    }
}

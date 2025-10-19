package com.crdb.benchmark.workload;

/**
 * Defines different workload patterns for benchmark testing
 */
public enum WorkloadPattern {
    /**
     * Read-heavy workload: 80% reads, 20% writes
     * Simulates typical web application traffic
     */
    READ_HEAVY(0.8, 0.2),
    
    /**
     * Write-heavy workload: 20% reads, 80% writes
     * Simulates data ingestion or logging scenarios
     */
    WRITE_HEAVY(0.2, 0.8),
    
    /**
     * Mixed workload: 50% reads, 50% writes
     * Simulates balanced OLTP operations
     */
    MIXED(0.5, 0.5),
    
    /**
     * Read-only workload: 100% reads
     * Simulates reporting or analytics queries
     */
    READ_ONLY(1.0, 0.0),
    
    /**
     * Write-only workload: 100% writes
     * Simulates bulk insert operations
     */
    WRITE_ONLY(0.0, 1.0);
    
    private final double readRatio;
    private final double writeRatio;
    
    WorkloadPattern(double readRatio, double writeRatio) {
        this.readRatio = readRatio;
        this.writeRatio = writeRatio;
    }
    
    public double getReadRatio() {
        return readRatio;
    }
    
    public double getWriteRatio() {
        return writeRatio;
    }
    
    public boolean isReadOperation(double random) {
        return random < readRatio;
    }
}

package com.crdb.benchmark.workload;

import com.crdb.benchmark.config.BenchmarkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Factory for creating different types of database operations
 * Based on workload configuration
 */
public class OperationFactory {
    private static final Logger logger = LoggerFactory.getLogger(OperationFactory.class);
    private final BenchmarkConfig config;
    private final Random random = new Random();
    
    public OperationFactory(BenchmarkConfig config) {
        this.config = config;
    }
    
    /**
     * Create a random read operation based on workload configuration
     */
    public DatabaseOperation createReadOperation() {
        // Always use actual database tables (users, products, orders)
        // Ignore config queries that reference non-existent tables
        return this::executeSimpleSelect;
    }
    
    /**
     * Create a random write operation based on workload configuration
     */
    public DatabaseOperation createWriteOperation() {
        // Always use actual database tables (users, products, orders)
        // Ignore config queries that reference non-existent tables
        return this::executeSimpleInsert;
    }
    
    /**
     * Create operation from configuration entry
     */
    private DatabaseOperation createOperationFromConfig(
            String operationName, 
            BenchmarkConfig.WorkloadConfig.OperationConfig config) {
        
        String query = config.query();
        
        return connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(query)) {
                // Set parameters based on query
                int paramCount = countParameters(query);
                for (int i = 1; i <= paramCount; i++) {
                    setRandomParameter(stmt, i);
                }
                
                if (query.trim().toUpperCase().startsWith("SELECT")) {
                    try (ResultSet rs = stmt.executeQuery()) {
                        int count = 0;
                        while (rs.next()) {
                            count++;
                        }
                        return count;
                    }
                } else {
                    return stmt.executeUpdate();
                }
            }
        };
    }
    
    /**
     * Simple SELECT operation for benchmarking
     * Queries users table with IDs 1-100,000 (actual data range)
     */
    private int executeSimpleSelect(Connection connection) throws SQLException {
        String query = "SELECT * FROM users WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, ThreadLocalRandom.current().nextInt(1, 100001));
            try (ResultSet rs = stmt.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                }
                return count;
            }
        }
    }
    
    /**
     * Simple INSERT operation for benchmarking
     * Inserts into orders with valid user_id (1-100,000) and product_id (1-10,000)
     */
    private int executeSimpleInsert(Connection connection) throws SQLException {
        String query = "INSERT INTO orders (user_id, product_id, amount, order_date) VALUES (?, ?, ?, NOW())";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, ThreadLocalRandom.current().nextInt(1, 100001));  // Valid user_id range
            stmt.setInt(2, ThreadLocalRandom.current().nextInt(1, 10001));   // Valid product_id range
            stmt.setDouble(3, ThreadLocalRandom.current().nextDouble(10.0, 1000.0));
            return stmt.executeUpdate();
        }
    }
    
    /**
     * Simple UPDATE operation for benchmarking
     * Updates users with IDs 1-100,000 (actual data range)
     */
    private int executeSimpleUpdate(Connection connection) throws SQLException {
        String query = "UPDATE users SET last_login = NOW() WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, ThreadLocalRandom.current().nextInt(1, 100001));
            return stmt.executeUpdate();
        }
    }
    
    /**
     * Complex SELECT with JOIN operation
     */
    private int executeComplexSelect(Connection connection) throws SQLException {
        String query = """
            SELECT u.id, u.username, COUNT(o.id) as order_count, SUM(o.amount) as total_amount
            FROM users u
            LEFT JOIN orders o ON u.id = o.user_id
            WHERE u.created_at > NOW() - INTERVAL '90 days'
            GROUP BY u.id, u.username
            HAVING COUNT(o.id) > 0
            LIMIT 100
        """;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            int count = 0;
            while (rs.next()) {
                count++;
            }
            return count;
        }
    }
    
    /**
     * Batch INSERT operation
     * Inserts 100 orders with valid foreign key references
     */
    private int executeBatchInsert(Connection connection) throws SQLException {
        String query = "INSERT INTO orders (user_id, product_id, amount, order_date) VALUES (?, ?, ?, NOW())";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            int batchSize = 100;
            for (int i = 0; i < batchSize; i++) {
                stmt.setInt(1, ThreadLocalRandom.current().nextInt(1, 100001));  // Valid user_id range
                stmt.setInt(2, ThreadLocalRandom.current().nextInt(1, 10001));   // Valid product_id range
                stmt.setDouble(3, ThreadLocalRandom.current().nextDouble(10.0, 1000.0));
                stmt.addBatch();
            }
            int[] results = stmt.executeBatch();
            return results.length;
        }
    }
    
    /**
     * Count parameters in a SQL query
     */
    private int countParameters(String query) {
        int count = 0;
        for (char c : query.toCharArray()) {
            if (c == '?') {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Set random parameter value for prepared statement
     */
    private void setRandomParameter(PreparedStatement stmt, int index) throws SQLException {
        // Randomly choose parameter type
        int type = ThreadLocalRandom.current().nextInt(3);
        switch (type) {
            case 0 -> stmt.setInt(index, ThreadLocalRandom.current().nextInt(1, 1000000));
            case 1 -> stmt.setString(index, UUID.randomUUID().toString());
            case 2 -> stmt.setDouble(index, ThreadLocalRandom.current().nextDouble(1.0, 10000.0));
        }
    }
}

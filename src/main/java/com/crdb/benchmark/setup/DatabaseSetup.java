package com.crdb.benchmark.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Database setup utility to create required tables and test data
 * Run this before executing benchmarks
 */
public class DatabaseSetup {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseSetup.class);
    
    /**
     * Create benchmark database schema
     */
    public static void setupSchema(String jdbcUrl, String username, String password) 
            throws SQLException {
        logger.info("Setting up database schema");
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {
            
            // Drop existing tables
            logger.info("Dropping existing tables if they exist");
            stmt.execute("DROP TABLE IF EXISTS orders CASCADE");
            stmt.execute("DROP TABLE IF EXISTS products CASCADE");
            stmt.execute("DROP TABLE IF EXISTS users CASCADE");
            
            // Create users table
            logger.info("Creating users table");
            stmt.execute("""
                CREATE TABLE users (
                    id INT PRIMARY KEY,
                    username VARCHAR(100) NOT NULL,
                    email VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP DEFAULT NOW(),
                    last_login TIMESTAMP,
                    region VARCHAR(50),
                    INDEX idx_username (username),
                    INDEX idx_region (region),
                    INDEX idx_created_at (created_at)
                )
            """);
            
            // Create products table
            logger.info("Creating products table");
            stmt.execute("""
                CREATE TABLE products (
                    id INT PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    price DECIMAL(10, 2) NOT NULL,
                    category VARCHAR(100),
                    stock_quantity INT DEFAULT 0,
                    created_at TIMESTAMP DEFAULT NOW(),
                    INDEX idx_category (category)
                )
            """);
            
            // Create orders table
            logger.info("Creating orders table");
            stmt.execute("""
                CREATE TABLE orders (
                    id SERIAL PRIMARY KEY,
                    user_id INT NOT NULL,
                    product_id INT NOT NULL,
                    amount DECIMAL(10, 2) NOT NULL,
                    order_date TIMESTAMP DEFAULT NOW(),
                    status VARCHAR(50) DEFAULT 'pending',
                    FOREIGN KEY (user_id) REFERENCES users(id),
                    FOREIGN KEY (product_id) REFERENCES products(id),
                    INDEX idx_user_id (user_id),
                    INDEX idx_product_id (product_id),
                    INDEX idx_order_date (order_date),
                    INDEX idx_status (status)
                )
            """);
            
            logger.info("Schema created successfully");
        }
    }
    
    /**
     * Load test data into tables
     */
    public static void loadTestData(String jdbcUrl, String username, String password,
                                    int userCount, int productCount) throws SQLException {
        logger.info("Loading test data: {} users, {} products", userCount, productCount);
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {
            
            // Insert users
            logger.info("Inserting {} users", userCount);
            stmt.execute(String.format("""
                INSERT INTO users (id, username, email, region, created_at)
                SELECT 
                    g.i,
                    'user_' || g.i,
                    'user_' || g.i || '@example.com',
                    CASE (g.i %% 3)
                        WHEN 0 THEN 'us-east-1'
                        WHEN 1 THEN 'us-west-2'
                        ELSE 'eu-west-1'
                    END,
                    NOW() - (g.i || ' days')::INTERVAL
                FROM generate_series(1, %d) AS g(i)
            """, userCount));
            
            // Insert products
            logger.info("Inserting {} products", productCount);
            stmt.execute(String.format("""
                INSERT INTO products (id, name, price, category, stock_quantity)
                SELECT 
                    g.i,
                    'Product ' || g.i,
                    (RANDOM() * 1000)::DECIMAL(10, 2),
                    CASE (g.i %% 5)
                        WHEN 0 THEN 'Electronics'
                        WHEN 1 THEN 'Books'
                        WHEN 2 THEN 'Clothing'
                        WHEN 3 THEN 'Food'
                        ELSE 'Other'
                    END,
                    (RANDOM() * 1000)::INT
                FROM generate_series(1, %d) AS g(i)
            """, productCount));
            
            logger.info("Test data loaded successfully");
        }
    }
    
    /**
     * Verify database setup
     * Checks that tables exist, have data, and ID ranges are correct
     */
    public static boolean verifySetup(String jdbcUrl, String username, String password) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {
            
            // Check users table
            var rs = stmt.executeQuery("SELECT COUNT(*) as cnt, MIN(id) as min_id, MAX(id) as max_id FROM users");
            rs.next();
            int userCount = rs.getInt("cnt");
            int minUserId = rs.getInt("min_id");
            int maxUserId = rs.getInt("max_id");
            
            // Check products table
            rs = stmt.executeQuery("SELECT COUNT(*) as cnt, MIN(id) as min_id, MAX(id) as max_id FROM products");
            rs.next();
            int productCount = rs.getInt("cnt");
            int minProductId = rs.getInt("min_id");
            int maxProductId = rs.getInt("max_id");
            
            logger.info("Database verification:");
            logger.info("  Users: {} rows (IDs: {} - {})", userCount, minUserId, maxUserId);
            logger.info("  Products: {} rows (IDs: {} - {})", productCount, minProductId, maxProductId);
            
            // Verify ID ranges are sequential starting from 1 (required for OperationFactory)
            boolean valid = userCount > 0 && productCount > 0 
                         && minUserId == 1 && maxUserId == userCount
                         && minProductId == 1 && maxProductId == productCount;
            
            if (!valid) {
                logger.error("Invalid ID ranges! Expected users: 1-{}, products: 1-{}", 
                           userCount, productCount);
                logger.error("Actual users: {}-{}, products: {}-{}", 
                           minUserId, maxUserId, minProductId, maxProductId);
            }
            
            return valid;
            
        } catch (SQLException e) {
            logger.error("Database verification failed", e);
            return false;
        }
    }
    
    /**
     * Main method for standalone execution
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: DatabaseSetup <jdbcUrl> <username> <password> [userCount] [productCount]");
            System.exit(1);
        }
        
        String jdbcUrl = args[0];
        String username = args[1];
        String password = args[2];
        int userCount = args.length > 3 ? Integer.parseInt(args[3]) : 1000000;
        int productCount = args.length > 4 ? Integer.parseInt(args[4]) : 100000;
        
        try {
            setupSchema(jdbcUrl, username, password);
            loadTestData(jdbcUrl, username, password, userCount, productCount);
            
            if (verifySetup(jdbcUrl, username, password)) {
                System.out.println("Database setup completed successfully");
            } else {
                System.err.println("Database setup verification failed");
                System.exit(1);
            }
            
        } catch (Exception e) {
            System.err.println("Database setup failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}

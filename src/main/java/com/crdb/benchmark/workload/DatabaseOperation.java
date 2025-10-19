package com.crdb.benchmark.workload;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Interface for database operations
 */
@FunctionalInterface
public interface DatabaseOperation {
    /**
     * Execute the database operation
     * 
     * @param connection Database connection to use
     * @return Operation result (e.g., number of rows affected, query result count)
     * @throws SQLException if operation fails
     */
    int execute(Connection connection) throws SQLException;
}

#!/bin/bash
# Quick start script for CockroachDB Connection Benchmark

set -e

echo "CockroachDB Connection Benchmark - Quick Start"
echo "=============================================="
echo ""

# Check for Java 21
echo "Checking Java version..."
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 21 ]; then
    echo "Error: Java 21 or higher is required"
    echo "Current version: $(java -version 2>&1 | head -1)"
    exit 1
fi
echo "✓ Java version OK"
echo ""

# Check for config file
if [ ! -f "config/benchmark-config.yaml" ]; then
    echo "Config file not found. Creating from template..."
    cp config/benchmark-config.template.yaml config/benchmark-config.yaml
    echo "✓ Created config/benchmark-config.yaml"
    echo ""
    echo "⚠️  Please edit config/benchmark-config.yaml with your CockroachDB connection details"
    echo ""
    read -p "Press Enter when ready to continue..."
fi

# Build project
echo "Building project with Gradle..."
./gradlew clean shadowJar
echo "✓ Build complete"
echo ""

# Setup database (optional)
read -p "Do you want to setup the database schema and test data? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "Setting up database..."
    echo "Please provide database connection details:"
    read -p "JDBC URL: " JDBC_URL
    read -p "Username: " DB_USER
    read -sp "Password: " DB_PASS
    echo ""
    
    java -cp build/libs/crdb-connection-benchmark-1.0.0.jar \
        com.crdb.benchmark.setup.DatabaseSetup \
        "$JDBC_URL" "$DB_USER" "$DB_PASS"
    
    echo "✓ Database setup complete"
    echo ""
fi

# Run benchmark
echo "Starting benchmark..."
echo ""
java -jar build/libs/crdb-connection-benchmark-1.0.0.jar config/benchmark-config.yaml

echo ""
echo "Benchmark completed!"
echo "Results are saved in the results/ directory"

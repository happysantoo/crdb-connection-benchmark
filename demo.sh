#!/bin/bash
# Demo script to run the benchmark against local CockroachDB cluster

set -e

echo "========================================"
echo "CockroachDB Connection Benchmark - DEMO"
echo "========================================"
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}Error: Docker is not running${NC}"
    echo "Please start Docker Desktop and try again"
    exit 1
fi

echo -e "${GREEN}✓ Docker is running${NC}"
echo ""

# Start CockroachDB cluster
echo "Starting CockroachDB cluster (3 nodes)..."
docker-compose up -d crdb-us-east crdb-us-west crdb-eu-west
echo ""

# Wait for cluster to be ready
echo "Waiting for cluster to be ready..."
sleep 10

# Initialize cluster
echo "Initializing cluster..."
docker-compose up crdb-init
echo ""

# Wait a bit more
sleep 5

# Check cluster status
echo "Checking cluster status..."
docker exec crdb-us-east cockroach node status --insecure || true
echo ""

# Create database schema and load test data
echo -e "${YELLOW}Setting up database schema and test data...${NC}"
echo ""

java -cp build/libs/crdb-connection-benchmark-1.0.0.jar \
    com.crdb.benchmark.setup.DatabaseSetup \
    "jdbc:postgresql://localhost:26257/benchmark?sslmode=disable" \
    "root" \
    "" \
    10000 \
    1000

echo ""
echo -e "${GREEN}✓ Database setup complete${NC}"
echo ""

# Start OpenTelemetry Collector
echo "Starting OpenTelemetry Collector..."
docker-compose up -d otel-collector
sleep 3
echo ""

# Run the benchmark
echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}Running Benchmark...${NC}"
echo -e "${YELLOW}========================================${NC}"
echo ""

java -jar build/libs/crdb-connection-benchmark-1.0.0.jar config/demo-config.yaml

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Benchmark Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# Show cluster info
echo "Cluster Information:"
echo "-------------------"
echo "• CockroachDB Admin UI: http://localhost:8080"
echo "• Node 1 (us-east): http://localhost:8080"
echo "• Node 2 (us-west): http://localhost:8081"
echo "• Node 3 (eu-west): http://localhost:8082"
echo "• OpenTelemetry Metrics: http://localhost:8888/metrics"
echo ""

# Show results location
echo "Results saved to: results/"
if [ -d "results" ]; then
    echo "Files created:"
    ls -lh results/ | grep -v total
fi
echo ""

# Offer to stop cluster
read -p "Do you want to stop the CockroachDB cluster? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "Stopping cluster..."
    docker-compose down
    echo -e "${GREEN}Cluster stopped${NC}"
else
    echo -e "${YELLOW}Cluster is still running. To stop it later, run:${NC}"
    echo "  docker-compose down"
    echo ""
    echo -e "${YELLOW}To remove all data, run:${NC}"
    echo "  docker-compose down -v"
fi

echo ""
echo "Demo complete!"

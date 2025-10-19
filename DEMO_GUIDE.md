# Demo Guide - CockroachDB Connection Benchmark

This guide shows you how to run the benchmark against a local CockroachDB cluster.

## Prerequisites

- Docker Desktop installed and running
- Java 21+ installed
- The benchmark JAR built (`./gradlew clean shadowJar`)

## Quick Start (Automated)

```bash
# Make sure Docker Desktop is running first!

# Run the complete demo
./demo.sh
```

This will:
1. Start a 3-node CockroachDB cluster (us-east, us-west, eu-west)
2. Initialize the cluster
3. Create database schema and load test data
4. Start OpenTelemetry Collector
5. Run the benchmark
6. Show results

## Manual Steps

If you prefer to run step-by-step:

### 1. Start CockroachDB Cluster

```bash
# Start the cluster
docker-compose up -d

# Wait for cluster to initialize (about 30 seconds)
sleep 30

# Check cluster status
docker exec crdb-us-east cockroach node status --insecure
```

### 2. Create Database Schema

```bash
java -cp build/libs/crdb-connection-benchmark-1.0.0.jar \
    com.crdb.benchmark.setup.DatabaseSetup \
    "jdbc:postgresql://localhost:26257/benchmark?sslmode=disable" \
    "root" \
    "" \
    10000 \
    1000
```

This creates:
- `users` table with 10,000 rows
- `products` table with 1,000 rows
- Appropriate indexes

### 3. Run the Benchmark

```bash
java -jar build/libs/crdb-connection-benchmark-1.0.0.jar config/demo-config.yaml
```

### 4. View Results

Results will be saved to `results/` directory with timestamps.

### 5. Access CockroachDB Admin UI

Open in your browser:
- **Node 1 (us-east)**: http://localhost:8080
- **Node 2 (us-west)**: http://localhost:8081  
- **Node 3 (eu-west)**: http://localhost:8082

### 6. View OpenTelemetry Metrics

OpenTelemetry Collector Prometheus endpoint:
- http://localhost:8888/metrics

### 7. Stop the Cluster

```bash
# Stop containers
docker-compose down

# Stop and remove all data
docker-compose down -v
```

## What the Benchmark Tests

The demo configuration (`config/demo-config.yaml`) tests:

**Pool Sizes**: 5, 10, 25, 50 connections

**Workload Patterns**:
- READ_HEAVY (80% reads, 20% writes)
- MIXED (50% reads, 50% writes)

**Operations**:
- Simple SELECT (50% weight)
- Complex JOIN queries (20% weight)
- INSERT operations (20% weight)
- UPDATE operations (10% weight)

**Duration**: 30 seconds per test (after 5s warmup)

**Metrics Collected**:
- Latency percentiles (P50, P90, P95, P99)
- Throughput (operations/second)
- Connection pool utilization
- Active/idle connection tracking over time
- Error rates

## Expected Results

The benchmark will identify the optimal connection pool size by:

1. **Testing each pool size** (5, 10, 25, 50) against the workload
2. **Measuring performance**: throughput, latency, utilization
3. **Tracking connections**: Real-time sampling every 500ms
4. **Analyzing trade-offs**: Performance vs. resource efficiency
5. **Recommending optimal size**: Based on multi-factor scoring

### Sample Output

```
================================================================================
BENCHMARK ANALYSIS RESULTS
================================================================================

OPTIMAL CONFIGURATION:
  Pool Size: 25 connections
  Score: 87.5/100

PERFORMANCE METRICS:
  Throughput: 3,245 ops/sec
  P50 Latency: 3.2 ms
  P99 Latency: 18.7 ms
  Error Rate: 0.02%
  Utilization: 72.3%

TRENDS:
  Diminishing Returns: 25 connections
  Latency Inflection: 50 connections
  Throughput Saturation: 25 connections

RECOMMENDATIONS:
  1. OPTIMAL: Use 25 connections per region for best balance
  2. This configuration achieves 3245 ops/sec with P99 latency of 18.7 ms
  3. Utilization is in healthy range - pool is not oversized
  4. For multi-region setup with 1 regions: Total 25 connections cluster-wide

Connection Statistics:
  Active Connections: min=8, avg=18, max=24
  Idle Connections: min=1, avg=7, max=17
  Peak Threads Awaiting: 0
  Utilization: avg=72.3%, peak=96.0%

  Per-Region Statistics:
    us-east: active=8-18-24, util=72.3% (peak 96.0%)
```

## Troubleshooting

### Docker not running
```bash
# Start Docker Desktop application first
# On macOS: open -a Docker
```

### Port conflicts
If ports 26257, 8080, etc. are in use:
```bash
# Find what's using the port
lsof -i :26257

# Or modify docker-compose.yaml to use different ports
```

### Connection refused
```bash
# Wait for cluster to fully initialize
sleep 30

# Check if containers are running
docker ps

# Check logs
docker logs crdb-us-east
```

### Out of memory
```bash
# CockroachDB needs memory. Increase Docker Desktop memory:
# Docker Desktop → Settings → Resources → Memory → 4GB+
```

## Architecture

```
┌─────────────────────────────────────────┐
│         Benchmark Application           │
│  (Java 21 + Virtual Threads)            │
│  - Connection Pool Manager (HikariCP)   │
│  - Workload Generator                   │
│  - Metrics Collector (Micrometer)       │
│  - Connection Tracker                   │
└───────────┬─────────────────────────────┘
            │
            │ JDBC connections
            │
┌───────────▼─────────────────────────────┐
│      CockroachDB Cluster (Docker)       │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐│
│  │ Node 1   │ │ Node 2   │ │ Node 3   ││
│  │ us-east  │ │ us-west  │ │ eu-west  ││
│  │ :26257   │ │ :26258   │ │ :26259   ││
│  └──────────┘ └──────────┘ └──────────┘│
└─────────────────────────────────────────┘
            │
            │ OTLP Metrics
            │
┌───────────▼─────────────────────────────┐
│    OpenTelemetry Collector :4318        │
│    Prometheus Endpoint :8888            │
└─────────────────────────────────────────┘
```

## Next Steps

After running the demo:

1. **Review the results** in the `results/` directory
2. **Check CockroachDB Admin UI** to see query performance
3. **View OpenTelemetry metrics** for detailed observability
4. **Adjust configuration** in `config/demo-config.yaml`:
   - Try different pool sizes
   - Change workload patterns
   - Adjust test duration
5. **Scale up**: Test with more data or longer duration
6. **Add regions**: Uncomment additional regions in docker-compose.yaml

## Files Created

- `docker-compose.yaml` - Multi-node CockroachDB cluster
- `otel-collector-config.yaml` - OpenTelemetry configuration
- `config/demo-config.yaml` - Demo benchmark configuration
- `demo.sh` - Automated demo script
- `DEMO_GUIDE.md` - This guide

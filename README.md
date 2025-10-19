# CockroachDB Connection Benchmark

A comprehensive Java 21 benchmarking tool for determining optimal connection pool sizes in multi-region CockroachDB clusters using virtual threads.

## Features

- **Java 21 Virtual Threads**: Leverage lightweight threads for massive concurrency
- **Multi-Region Support**: Test across multiple datacenters and regions
- **HikariCP Connection Pooling**: Industry-standard connection pool management
- **Comprehensive Metrics**: Latency percentiles, throughput, connection utilization, error rates
- **Real-time Connection Tracking**: High-resolution sampling of active connections over time
- **OpenTelemetry Integration**: Export metrics via OTLP for observability platforms
- **Multiple Workload Patterns**: Read-heavy, write-heavy, mixed OLTP scenarios
- **Sweet Spot Analysis**: Automated recommendations for optimal connection settings

## Prerequisites

- Java 21 or higher
- CockroachDB cluster (single or multi-region)
- Gradle 8.11+ (or use included wrapper)
- (Optional) OpenTelemetry Collector for metrics export

## Quick Start

1. **Clone and build**:
```bash
./gradlew clean shadowJar
```

2. **Configure your cluster**:
Edit `config/benchmark-config.yaml` with your CockroachDB connection details.

3. **Run benchmark**:
```bash
java -jar build/libs/crdb-connection-benchmark-1.0.0.jar
```

Or use the convenience script:
```bash
./run.sh
```

## Latest Dependencies (as of Oct 2025)

- **PostgreSQL JDBC Driver**: 42.7.4
- **HikariCP**: 6.2.1
- **Micrometer**: 1.14.2 (with OpenTelemetry registry)
- **OpenTelemetry SDK**: 1.44.1
- **Jackson**: 2.18.2
- **Logback**: 1.5.12
- **JUnit**: 5.11.3

## Configuration

The benchmark is configured via `config/benchmark-config.yaml`:

```yaml
regions:
  - name: us-east-1
    jdbcUrl: "jdbc:postgresql://us-east-cockroach:26257/benchmark?sslmode=require"
    username: "benchmark_user"
    password: "your_password"
  - name: eu-west-1
    jdbcUrl: "jdbc:postgresql://eu-west-cockroach:26257/benchmark?sslmode=require"
    username: "benchmark_user"
    password: "your_password"

benchmark:
  connectionPoolSizes: [10, 25, 50, 100, 200, 500]
  durationSeconds: 300
  warmupSeconds: 30
  workloadPatterns: [READ_HEAVY, WRITE_HEAVY, MIXED]
  virtualThreads: true
```

## Architecture

### Core Components

1. **ConnectionPoolManager**: Manages HikariCP pools per region
2. **WorkloadGenerator**: Creates realistic OLTP workloads using virtual threads
3. **MetricsCollector**: Captures performance metrics with Micrometer
4. **SweetSpotAnalyzer**: Analyzes results and provides recommendations
5. **BenchmarkRunner**: Orchestrates the entire benchmark process

### Workload Patterns

- **READ_HEAVY**: 80% reads, 20% writes
- **WRITE_HEAVY**: 20% reads, 80% writes
- **MIXED**: 50% reads, 50% writes
- **BATCH_INSERT**: Bulk insert operations
- **COMPLEX_QUERY**: Multi-table joins and aggregations

## Metrics Collected

- **Latency**: p50, p90, p95, p99, p99.9 percentiles
- **Throughput**: Operations per second
- **Connection Pool**: Active connections, idle connections, wait time
- **Connection Tracking**: Real-time sampling (configurable interval) of:
  - Active connections over time with timestamps
  - Idle connections over time
  - Threads awaiting connections
  - Utilization percentages per region
  - Min/avg/max statistics for entire test duration
- **Errors**: Error rate, retry count, timeout rate
- **Regional**: Per-region performance comparison
- **Resource**: CPU and memory utilization correlation

## OpenTelemetry Integration

Metrics are exported via OpenTelemetry Protocol (OTLP) to enable integration with observability platforms:

```yaml
# In config/benchmark-config.yaml
metrics:
  export:
    opentelemetry:
      enabled: true
      endpoint: "http://localhost:4318/v1/metrics"  # OTLP HTTP endpoint
```

Set environment variable to override:
```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="http://your-collector:4318/v1/metrics"
```

## Connection Tracking

The benchmark now includes high-resolution connection tracking that samples the pool state at configurable intervals (default 1000ms):

```yaml
metrics:
  collectionIntervalMs: 1000  # Sample every second
```

This provides:
- **Accurate time-series data**: See exactly how many connections were active at each moment
- **Utilization trends**: Identify if you're hitting capacity limits
- **Peak detection**: Find maximum concurrent connections during the test
- **Per-region breakdown**: Track each region's connection usage independently

## Output

Results are saved to `results/` directory:
- `benchmark-results-{timestamp}.json`: Raw metrics data
- `benchmark-report-{timestamp}.html`: Visual HTML report
- `recommendations-{timestamp}.txt`: Optimal configuration suggestions

## Example Results

```
=== BENCHMARK RESULTS ===
Region: us-east-1
Pool Size: 100
Workload: MIXED

Throughput: 15,432 ops/sec
Latency P50: 2.3ms
Latency P99: 12.7ms
Connection Utilization: 78%
Error Rate: 0.02%

RECOMMENDATION: Optimal pool size for us-east-1 is 100 connections
```

## Advanced Usage

### Custom Workload

Implement `WorkloadPattern` interface to create custom workload scenarios.

### Multi-Region Testing

Configure multiple regions to test cross-region performance and optimal distribution.

### Continuous Benchmarking

Integrate with CI/CD pipelines to track performance over time.

## Best Practices

1. **Run during off-peak hours** for production clusters
2. **Use dedicated test cluster** when possible
3. **Run multiple iterations** to ensure consistency
4. **Monitor cluster health** during benchmarks
5. **Consider regional latency** in multi-region setups

## License

MIT License

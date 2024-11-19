# Manetu Performance App

[![CircleCI](https://circleci.com/gh/manetu/data-loader/tree/master.svg?style=svg)](https://circleci.com/gh/manetu/data-loader/tree/master)

The Manetu Performance App is a command-line tool used to performance test and generate benchmarks for the Manetu Platform. This tool emulates the essential functions of a Manetu data connector intended to facilitate testing and benchmarking.

## Features

The tool supports testing several functions:

1. **create-vaults**: Create a vault for each user, based on a hash of the email address
2. **load-attributes**: Load attributes into the vault, based on the Person schema
3. **delete-attributes**: Delete all attributes from the vault
4. **delete-vaults**: Remove vaults from the system
5. **onboard**: Simultaneously create vaults and load attributes

## Installation

### Prerequisites
- JRE

```shell
sudo wget https://github.com/manetu/data-loader/releases/download/v2.0.1/manetu-data-loader -O /usr/local/bin/manetu-data-loader
sudo chmod +x /usr/local/bin/manetu-data-loader
```

## Building

### Prerequisites
- Leiningen

```shell
make
```

## Usage

### Single Operation Mode
Run a single operation on your data:

```shell
./target/bin/manetu-performance-app -u https://manetu.instance --token $MANETU_TOKEN -m create-vaults -c 16 -l debug sample-data/mock-data.json
```

### Benchmarking Mode
The tool supports automated benchmarking using a YAML configuration file and a sample data file.

```shell
./target/bin/manetu-performance-app -u https://manetu.instance --token $MANETU_TOKEN --config tests.yaml -l debug sample-data/mock-data.json
```

You can also run the test without a data file by supplying a `--count`, count will be the number of iterations for the test, e.g, passing through `--count 1000` will create 1000 vaults. Optionally you can supply a prefix as well with `--namespace`, only really needed if planning on running parallel tests in the same cluster.

```shell
./target/bin/manetu-performance-app -u https://manetu.instance --token $MANETU_TOKEN --config tests.yaml --count 1000 -l debug 
```

#### Configuration File Format (tests.yaml)
```yaml
tests:
  - create-vaults
  - load-attributes
  - delete-attributes
  - delete-vaults
concurrency:
  - 16
  - 32
  - 64
```

#### Results Output
Results are written to a JSON file (default: results.json) and csv file (default: results.csv) containing:
- Timestamp of the test run
- Results for each concurrency level including:
    - Success/failure counts
    - Latency statistics (min, mean, stddev, percentiles)
    - Total duration and operation rate

Example output structure:
```json
{
  "timestamp": "2024-11-18T15:33:20.057399Z",
  "results": [
    {
      "concurrency": 16,
      "tests": {
        "create-vaults": {
          "successes": 1,
          "failures": 0,
          "min": 597.462,
          "mean": 597.462,
          "stddev": 0.0,
          "p50": 597.462,
          "p90": 597.462,
          "p95": 597.462,
          "p99": 597.462,
          "max": 597.462,
          "total-duration": 614.26,
          "rate": 1.63,
          "count": 1.0
        }
        // ... other test results
      }
    }
    // ... results for other concurrency levels
  ]
}
```
```csv
Concurrency,Test,Successes,Failures,Min (ms),Mean (ms),Stddev,P50 (ms),P90 (ms),P95 (ms),P99 (ms),Max (ms),Total Duration (ms),Rate (ops/sec),Count
16,delete-attributes,10,0,339.874,490.867,87.237,485.209,583.287,583.412,583.412,583.412,599.432,16.68,10.0
16,delete-vaults,10,0,1714.654,1946.802,93.991,1974.262,2024.572,2037.481,2037.481,2037.481,2043.241,4.89,10.0
```

### Options
```
Options:
  -h, --help
  -v, --version                                                Print the version and exit
  -u, --url URL                                                The connection URL
  -i, --insecure         false                                 Disable TLS checks (dev only)
      --[no-]progress    true                                  Enable/disable progress output (default: enabled)
  -t, --token TOKEN                                            A personal access token
  -l, --log-level LEVEL  :info                                 Select the logging verbosity level from: [trace, debug, info, error]
      --fatal-errors     false                                 Any sub-operation failure is considered to be an application level failure
      --verbose-errors   false                                 Any sub-operation failure is logged as ERROR instead of TRACE
      --type TYPE        data-loader                           The type of data source this CLI represents
      --id ID            535CC6FC-EAF7-4CF3-BA97-24B2406674A7  The id of the data-source this CLI represents
      --class CLASS      global                                The schemaClass of the data-source this CLI represents
      --config CONFIG                                          Path to test configuration YAML file
  -c, --concurrency NUM  16                                    The number of parallel requests to issue
  -m, --mode MODE        :load-attributes                      Select the mode from: [query-attributes, load-attributes, onboard, delete-vaults, test-suite, delete-attributes, create-vaults]
  -d, --driver DRIVER    :graphql                              Select the driver from: [graphql]
      --csv-file FILE    results.csv                           Write the results to a CSV file
      --json-file FILE   results.json                          Write the results to a JSON file
      --count NUM                                              Number of test iterations
      --namespace NS                                           Namespace prefix for synthetic vault labels
```

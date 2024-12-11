# Manetu Benchmark Tool

[![CircleCI](https://circleci.com/gh/manetu/data-loader/tree/master.svg?style=svg)](https://circleci.com/gh/manetu/data-loader/tree/master)

The Manetu Benchmark Tool is a command-line tool used to performance test and generate benchmarks for the Manetu Platform. This tool emulates the essential functions of a Manetu data connector intended to facilitate testing and benchmarking.

## Features

The tool supports five main test suites:

1. **vaults**: Tests vault creation and deletion operations
    - Creates specified number of vaults
    - Deletes the created vaults
    - Measures performance of both operations

2. **e2e**: End-to-end lifecycle testing
    - Performs full lifecycle test of vault operations
    - Creates vault, loads attributes, deletes attributes, and deletes vault
    - Measures complete operation performance

3. **attributes**: Standalone attribute operations testing
    - Creates specified number of vaults
    - Performs attribute operations across the vaults
    - Cleans up by deleting the vaults
    - Measures attribute operation performance
4. **Tokenizer**: Standalone tokenize operations testing
    - Creates specified number of vaults
    - Performs tokenize operations across the vaults using vault MRNs 
    - Supports both ephemeral and persistent tokens
    - Supports generating multiple tokens per request
    - Measures tokenize operation performance 
    - Cleans up by deleting the vaults
5. **Tokenizer + Translate E2E**: Standalone tokenize/translate e2e operations testing
    - Creates specified number of vaults
    - Performs tokenize and translate operations across the vaults using vault MRNs
    - Supports both ephemeral and persistent tokens
    - Supports generating multiple tokens per request
    - Measures tokenize + translate e2e operation performance
    - Cleans up by deleting the vaults


See config file section on how to enable and modify the tests

## Installation

### Prerequisites
- JRE

## Building

### Prerequisites
- Leiningen

```shell
make
```

## Usage

### Benchmarking Mode
The tool supports automated benchmarking using a YAML configuration file.

```shell
./target/bin/manetu-performance-app -u https://manetu.instance --token $MANETU_TOKEN --config test-config.yaml
```

#### Configuration File Format (test-config.yaml)
```yaml
tests:
  vaults:
    # Tests vault creation and deletion operations
    enabled: true
    count:
      - 50  # Supports supplying multiple values for count
      - 100 # Test will run multiple times for each value of count supplied
    prefix: vault-test
    clean_up: false # ONLY set to true if previous test run ended prematurely and wasn't able to clean up data. See README.MD for more details.
  e2e:
    # Tests full lifecycle (create vault -> load attributes -> delete attributes -> delete vault)
    enabled: true
    count:
      - 50  # Supports supplying multiple values for count
      - 100 # Test will run multiple times for each value of count supplied
    prefix: e2e-test
    clean_up: false # ONLY set to true if previous test run ended prematurely and wasn't able to clean up data. See README.MD for more details.
  attributes:
    # Tests standalone attribute operations
    enabled: true
    count:
      - 50  # Supports supplying multiple values for count
      - 100 # Test will run multiple times for each value of count supplied
    vault_count: 10  # Number of vaults to create for attribute operations
    prefix: attr-test
    clean_up: false # ONLY set to true if previous test run ended prematurely and wasn't able to clean up data. See README.MD for more details.
  tokenizer:
    # Tests tokenize only
    enabled: true
    count:
      - 50   # Supports supplying multiple values for count
      - 100 # Test will run multiple times for each value of count supplied
    vault_count: 10
    prefix: tokenizer-test
    realm: data-loader # IMPORTANT: This needs to match the realm you are running your tests
    clean_up: false
    value_min: 4
    value_max: 32
    tokens_per_job:
      - 1 # Supports supplying multiple values for tokens_per_job
      - 2 # Test will run multiple times for each value of tokens_per_job supplied
    token_type:
      - EPHEMERAL  # Supports supplying both, this will run the test twice, once for each token type
      - PERSISTENT # Can be EPHEMERAL or PERSISTENT
  tokenizer_translate_e2e:
    # Tests tokenize + translate e2e time
    enabled: true
    count:
      - 50   # Supports supplying multiple values for count
      - 100 # Test will run multiple times for each value of count supplied
    vault_count: 10
    prefix: tokenizer-e2e-test
    realm: data-loader # IMPORTANT: This needs to match the realm you are running your tests
    clean_up: false
    value_min: 4
    value_max: 32
    tokens_per_job:
      - 1  # Supports supplying multiple values for tokens_per_job
      - 2  # Test will run multiple times for each value of tokens_per_job supplied
    token_type:
      - EPHEMERAL  # Supports supplying both, this will run the test twice, once for each token type
      - PERSISTENT # Can be EPHEMERAL or PERSISTENT
concurrency:
  # note: count values for all sections (especially vault_count for attributes)
  # should be greater than concurrency values for accurate performance measurements
  # e.g: To fire 64 concurrent attribute operation requests, there needs to be 64
  # or more vaults (vault_count>64)
  - 16
  - 32
  - 64
log_level: debug  # Available levels: trace, debug, info, error

#reports:               # optional define custom path for test reports. Default is ./reports/manetu-perf-results-<hh-mm-MM-dd-yyyy>.[csv|json]
#  csv: "reports.csv"   # Path for CSV report output
#  json: "reports.json" # Path for JSON report output

```

* Each test has a knob: `clean_up: false`
  * This knob should almost always be set to false and should only be set to true if a previous test run ended prematurely and wasn't able to clean up the data.
  * If enabled, no real test will be run. This will attempt to delete any existing vaults from previous tests runs using the same prefix/count.
  * prefix/count must match the prefix/count used for the prematurely ended test run.


#### Results Output
Results are written to a JSON file and CSV file (default: `./reports/manetu-perf-results-<hh-mm-MM-dd-yyyy>.[csv|json]`) containing:
- Timestamp of the test run
- Results for each concurrency level including:
    - Success/failure counts
    - Latency statistics (min, mean, stddev, percentiles)
    - Total duration and operation rate

Example output structure JSON:
```json
{
  "timestamp": "2024-11-29T16:56:26.840325Z",
  "results": [{
    "concurrency": 5,
    "tests": {
      "attributes": {
        "standalone-attributes": {
          "count": 20.0,
          "failures": 0,
          "max": 264.378,
          "mean": 253.342,
          "min": 238.058,
          "p50": 254.996,
          "p90": 260.24,
          "p95": 262.531,
          "p99": 264.378,
          "rate": 25.39,
          "stddev": 7.136,
          "successes": 20,
          "total-duration": 787.841
        }
      },
      "e2e": {
        "full-lifecycle": {}
      },
      "vaults": {
        "create-vaults": {},
        "delete-vaults": {}
      }
    }
  },
    {
      "concurrency": 10,
      "tests": {
        "attributes": {},
        "e2e": {},
        "vaults": {}
      }
    }]
}
```
Example Output Structure CSV:
```csv
Concurrency,Test Suite,Section,Successes,Failures,Min (ms),Mean (ms),Stddev,P50 (ms),P90 (ms),P95 (ms),P99 (ms),Max (ms),Total Duration (ms),Rate (ops/sec),Count
5,attributes,standalone-attributes,20,0,238.058,253.342,7.136,254.996,260.24,262.531,264.378,264.378,787.841,25.39,20.0
5,e2e,full-lifecycle,10,0,3202.563,3636.258,270.47,3672.565,3975.141,4039.821,4039.821,4039.821,7118.985,1.4,10.0
5,vaults,create-vaults,10,0,932.794,1130.447,139.429,1162.655,1299.361,1321.757,1321.757,1321.757,2243.901,4.46,10.0
5,vaults,delete-vaults,10,0,1589.095,1737.56,97.55,1765.868,1856.632,1895.452,1895.452,1895.452,3490.003,2.87,10.0
10,attributes,standalone-attributes,20,0,220.332,321.945,58.195,320.715,394.167,398.972,399.072,399.072,712.592,28.07,20.0
10,e2e,full-lifecycle,10,0,3969.638,4249.237,185.763,4279.673,4451.816,4452.376,4452.376,4452.376,4453.518,2.25,10.0
10,vaults,create-vaults,10,0,1046.066,1245.897,86.058,1266.006,1339.987,1352.715,1352.715,1352.715,1354.455,7.38,10.0
10,vaults,delete-vaults,10,0,1969.871,2223.559,123.344,2245.206,2348.903,2370.766,2370.766,2370.766,2372.024,4.22,10.0
```

### Options
```
Options:
  -h, --help
  -v, --version                                               Print the version and exit
  -u, --url URL                                               The connection URL
  -i, --insecure        false                                 Disable TLS checks (dev only)
      --[no-]progress   true                                  Enable/disable progress output (default: enabled)
  -t, --token TOKEN                                           A personal access token
      --fatal-errors    false                                 Any sub-operation failure is considered to be an application level failure
      --verbose-errors  false                                 Any sub-operation failure is logged as ERROR instead of TRACE
      --type TYPE       performance-app                       The type of data source this CLI represents
      --id ID           535CC6FC-EAF7-4CF3-BA97-24B2406674A7  The id of the data-source this CLI represents
      --class CLASS     global                                The schemaClass of the data-source this CLI represents
      --config CONFIG                                         Path to test configuration YAML file
  -d, --driver DRIVER   :graphql                              Select the driver from: [graphql]
```

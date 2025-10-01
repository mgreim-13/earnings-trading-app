# Test Scripts

This folder contains all test scripts for the TradingAWS project, organized for easy access and execution.

## Test Scripts

### StockFilterLambda Tests
- **`run-cache-tests.sh`** - Tests for cache functionality and performance
- **`run-earnings-filter-tests.sh`** - Tests for earnings stability filter
- **`run-integration-tests.sh`** - Integration tests with real API calls
- **`run-ivratio-tests.sh`** - Tests for IV ratio filter functionality

### TradingCommonUtils Tests
- **`run-tests.sh`** - Comprehensive test suite for common utilities

### InitiateTradesLambda Tests
- **`test-quick.sh`** - Quick smoke tests for trade initiation
- **`run-tests.sh`** - Full test suite for trade initiation

### ScanEarningsLambda Tests
- **`test-local.sh`** - Local testing with DynamoDB Local
- **`test-simplified.sh`** - Simplified test structure

## Usage

All test scripts are executable and can be run directly:

```bash
# Run specific tests
./run-cache-tests.sh
./run-ivratio-tests.sh

# Run all tests (if available)
./run-tests.sh
```

## Prerequisites

- Java 21
- Maven 3.6+
- AWS CLI configured
- Appropriate environment variables set (ALPACA_SECRET_NAME, etc.)

## Notes

- Some tests require valid AWS credentials and API access
- Integration tests may take longer to complete
- Check individual script headers for specific requirements

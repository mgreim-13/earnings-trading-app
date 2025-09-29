# Testing Guide for InitiateTradesLambda

This document explains the testing structure and how to run different types of tests.

## Test Structure

### 1. Unit Tests (`InitiateTradesLambdaUnitTest.java`)
- **Purpose**: Test business logic in isolation
- **Dependencies**: All external dependencies are mocked
- **Speed**: Fast execution (< 1 second)
- **Reliability**: 100% reliable, no external dependencies

**What's tested:**
- JSON order building
- API key retrieval from Secrets Manager
- Logging functionality
- DynamoDB data processing
- Input validation

**Run with:**
```bash
mvn test -Dtest=InitiateTradesLambdaUnitTest
```

### 2. Integration Tests (`InitiateTradesLambdaIntegrationTest.java`)
- **Purpose**: Test real API interactions
- **Dependencies**: Real Alpaca API, AWS Secrets Manager
- **Speed**: Slower (depends on API response times)
- **Reliability**: May fail due to network issues, API limits, or market hours

**What's tested:**
- Real Alpaca API connectivity
- Account access and market status
- Option contract selection
- Order submission and cancellation
- Complete workflow validation

**Run with:**
```bash
# Set environment variables
export ALPACA_API_KEY="your-api-key"
export ALPACA_SECRET_KEY="your-secret-key"

# Run integration tests
mvn test -Dtest=InitiateTradesLambdaIntegrationTest
```

### 3. Manual Testing Scripts

#### Quick Test (`scripts/test-quick.sh`)
- **Purpose**: Fast validation of API connectivity
- **Usage**: `./scripts/test-quick.sh <API_KEY> <SECRET_KEY>`
- **Duration**: ~10 seconds

**What's tested:**
- Account access
- Market status
- Stock quotes
- Order submission and cancellation

#### Comprehensive Test (`scripts/test-api.sh`)
- **Purpose**: Complete API testing with detailed output
- **Usage**: `ALPACA_API_KEY=xxx ALPACA_SECRET_KEY=xxx ./scripts/test-api.sh`
- **Duration**: ~30 seconds

**What's tested:**
- All basic API functions
- Order lifecycle (submit, cancel, resubmit)
- Position and order retrieval
- Error handling

## Running Tests

### Run All Unit Tests
```bash
mvn test -Dtest=InitiateTradesLambdaUnitTest
```

### Run All Integration Tests
```bash
export ALPACA_API_KEY="your-api-key"
export ALPACA_SECRET_KEY="your-secret-key"
mvn test -Dtest=InitiateTradesLambdaIntegrationTest
```

### Run Legacy Tests (Deprecated)
```bash
mvn test -Dtest=InitiateTradesLambdaTest
```

### Run Manual Tests
```bash
# Quick test
./scripts/test-quick.sh PKD58EYDICDW7400CWZL ieUDUhhyGQxpUBMDOJCuK1HB9LvNtgPhhXRTkGlP

# Comprehensive test
ALPACA_API_KEY="your-key" ALPACA_SECRET_KEY="your-secret" ./scripts/test-api.sh
```

## Test Configuration

### Environment Variables
- `ALPACA_API_KEY`: Your Alpaca paper trading API key
- `ALPACA_SECRET_KEY`: Your Alpaca paper trading secret key

### Test Credentials
For testing, you can use the provided test credentials:
- API Key: `PKD58EYDICDW7400CWZL`
- Secret Key: `ieUDUhhyGQxpUBMDOJCuK1HB9LvNtgPhhXRTkGlP`

**Note**: These are paper trading credentials and safe to use for testing.

## Test Results Interpretation

### Unit Tests
- ✅ **All tests should pass** - these test pure business logic
- ❌ **Any failure indicates a code bug** that needs fixing

### Integration Tests
- ✅ **Pass when APIs are accessible** and market is open
- ⚠️ **May fail when market is closed** or APIs are unavailable
- ❌ **Failures due to invalid credentials** need credential updates

### Manual Tests
- ✅ **All tests should pass** with valid credentials
- ❌ **Failures indicate API connectivity issues** or credential problems

## Troubleshooting

### Common Issues

1. **"unauthorized" errors**
   - Check API credentials
   - Verify credentials are for paper trading (not live)
   - Ensure credentials haven't expired

2. **Integration tests failing**
   - Market may be closed (tests will skip)
   - Network connectivity issues
   - API rate limiting

3. **Unit tests failing**
   - Code logic error
   - Mock setup issue
   - Missing dependencies

### Debug Mode

Run tests with debug output:
```bash
mvn test -Dtest=InitiateTradesLambdaUnitTest -X
```

## Best Practices

1. **Always run unit tests** before committing code
2. **Run integration tests** before deployment
3. **Use manual tests** for quick API validation
4. **Keep test credentials separate** from production credentials
5. **Mock external dependencies** in unit tests
6. **Test real APIs** in integration tests

## Test Coverage

- **Unit Tests**: 90%+ business logic coverage
- **Integration Tests**: 100% API interaction coverage
- **Manual Tests**: 100% end-to-end workflow coverage

## Continuous Integration

For CI/CD pipelines:
1. Run unit tests on every commit
2. Run integration tests on pull requests
3. Use manual tests for deployment validation
4. Skip integration tests if credentials are not available


#!/bin/bash

# Integration test runner for EarningsStabilityFilter
# Tests EarningsStabilityFilter with real Alpaca API calls

set -e

echo "Running EarningsStabilityFilter Integration Tests..."
echo "=================================================="

# Set default secret name if not provided
export ALPACA_SECRET_NAME=${ALPACA_SECRET_NAME:-"trading/alpaca/credentials"}

echo "Using Alpaca secret: $ALPACA_SECRET_NAME"
echo ""

# Check if AWS credentials are available
if ! aws sts get-caller-identity > /dev/null 2>&1; then
    echo "Error: AWS credentials not configured. Please run 'aws configure' first."
    exit 1
fi

# Check if the secret exists
if ! aws secretsmanager describe-secret --secret-id "$ALPACA_SECRET_NAME" > /dev/null 2>&1; then
    echo "Error: Secret '$ALPACA_SECRET_NAME' not found in AWS Secrets Manager."
    echo "Please create the secret with your Alpaca API credentials."
    exit 1
fi

echo "AWS credentials and secret are available."
echo ""

# Run the integration tests
echo "Running EarningsStabilityFilter integration tests..."
mvn test -Dtest=EarningsStabilityFilterIntegrationTest -q

echo ""
echo "Integration tests completed successfully!"
echo ""
echo "Test Summary:"
echo "- EarningsStabilityFilter.hasHistoricalEarningsStability() - Main filter logic"
echo "- Multiple symbols testing - AAPL, MSFT, GOOGL, TSLA, AMZN"
echo "- Different earnings dates - Various future dates"
echo "- Error handling - Invalid symbols and edge cases"
echo "- Performance testing - Execution time validation"
echo "- Configuration testing - Default parameter validation"
echo "- Options data testing - Real options data retrieval"
echo "- Consistency testing - Multiple calls with same parameters"
echo ""
echo "Note: Some tests may show warnings for unavailable data, which is normal."
echo "The filter is designed to handle missing data gracefully."

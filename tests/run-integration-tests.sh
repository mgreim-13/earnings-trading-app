#!/bin/bash

# Integration test runner for StockFilterLambda
# Tests AlpacaApiService with real Alpaca API calls

set -e

echo "Running StockFilterLambda Integration Tests..."
echo "=============================================="

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
echo "Running AlpacaApiService integration tests..."
mvn test -Dtest=AlpacaApiServiceIntegrationTest -q

echo ""
echo "Integration tests completed successfully!"
echo ""
echo "Test Summary:"
echo "- AlpacaApiService.getLatestQuote() - Real stock quote retrieval"
echo "- AlpacaApiService.getHistoricalBars() - Historical stock data"
echo "- AlpacaApiService.getLatestTrade() - Latest trade data"
echo "- AlpacaApiService.getOptionChain() - Option chain data"
echo "- AlpacaApiService.getOptionSnapshots() - Option snapshot data"
echo "- AlpacaApiService.getOptionHistoricalTrades() - Historical option trades"
echo "- AlpacaApiService.getLatestOptionTrades() - Latest option trades"
echo "- AlpacaApiService.getOptionHistoricalBars() - Historical option bars"
echo "- AlpacaApiService.getLatestOptionQuotes() - Latest option quotes"
echo "- AlpacaApiService.getConditionCodes() - Market condition codes"
echo "- AlpacaApiService.getExchangeCodes() - Exchange codes"
echo "- Error handling and data consistency tests"

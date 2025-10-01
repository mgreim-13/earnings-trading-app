#!/bin/bash

# Test runner script for IVRatioFilter tests
# This script runs both unit tests and integration tests

set -e

echo "=========================================="
echo "Running IVRatioFilter Tests"
echo "=========================================="

# Change to the StockFilterLambda directory
cd "$(dirname "$0")"

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo "Error: Maven is not installed or not in PATH"
    exit 1
fi

# Set environment variables for testing
export MAVEN_OPTS="-Xmx1024m -XX:MaxPermSize=256m"

echo ""
echo "1. Running Unit Tests (Fast, No API calls)..."
echo "=============================================="
mvn test -Dtest=IVRatioFilterUnitTest -DfailIfNoTests=false

if [ $? -eq 0 ]; then
    echo "✅ Unit tests passed!"
else
    echo "❌ Unit tests failed!"
    exit 1
fi

echo ""
echo "2. Running Integration Tests (Slow, Real API calls)..."
echo "====================================================="

# Check if ALPACA_SECRET_NAME is set
if [ -z "$ALPACA_SECRET_NAME" ]; then
    echo "⚠️  ALPACA_SECRET_NAME not set. Integration tests will be skipped."
    echo "   To run integration tests, set ALPACA_SECRET_NAME environment variable."
    echo "   Example: export ALPACA_SECRET_NAME=trading/alpaca/credentials"
    echo ""
    echo "Skipping integration tests..."
else
    echo "Using Alpaca credentials from: $ALPACA_SECRET_NAME"
    mvn test -Dtest=IVRatioFilterIntegrationTest -DfailIfNoTests=false
    
    if [ $? -eq 0 ]; then
        echo "✅ Integration tests passed!"
    else
        echo "❌ Integration tests failed!"
        exit 1
    fi
fi

echo ""
echo "3. Running All IVRatioFilter Tests..."
echo "===================================="
mvn test -Dtest=*IVRatioFilter* -DfailIfNoTests=false

if [ $? -eq 0 ]; then
    echo "✅ All IVRatioFilter tests passed!"
else
    echo "❌ Some IVRatioFilter tests failed!"
    exit 1
fi

echo ""
echo "=========================================="
echo "Test Summary"
echo "=========================================="
echo "✅ Unit Tests: Passed"
if [ -n "$ALPACA_SECRET_NAME" ]; then
    echo "✅ Integration Tests: Passed"
else
    echo "⚠️  Integration Tests: Skipped (no credentials)"
fi
echo "✅ All Tests: Passed"
echo ""
echo "IVRatioFilter is ready for use! 🚀"

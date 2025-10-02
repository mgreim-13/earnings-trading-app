#!/bin/bash

# Cache Tests Runner Script
# This script runs all cache-related tests for the StockFilterLambda

echo "🧪 Running Cache Logic Tests for StockFilterLambda"
echo "=================================================="

# Set test environment variables
export EARNINGS_TABLE="earnings-table"
export FILTERED_TABLE="filtered-tickers-table"
export VOLUME_THRESHOLD="2000000"
export MIN_SCORE_THRESHOLD="7"
export ALPACA_API_KEY="test-api-key"
export ALPACA_SECRET_KEY="test-secret-key"

# Change to the StockFilterLambda directory
cd "$(dirname "$0")"

echo "📁 Current directory: $(pwd)"
echo ""

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo "❌ Maven is not installed or not in PATH"
    echo "Please install Maven to run the tests"
    exit 1
fi

echo "🔧 Running Maven test compilation..."
mvn test-compile -q

if [ $? -ne 0 ]; then
    echo "❌ Test compilation failed"
    exit 1
fi

echo "✅ Test compilation successful"
echo ""

echo "🧪 Running CacheManager Tests..."
mvn test -Dtest=CacheManagerTest -q

if [ $? -eq 0 ]; then
    echo "✅ CacheManager tests passed"
else
    echo "❌ CacheManager tests failed"
    CACHE_MANAGER_FAILED=1
fi

echo ""

echo "🧪 Running StockFilterLambda Cache Tests..."
mvn test -Dtest=StockFilterLambdaCacheTest -q

if [ $? -eq 0 ]; then
    echo "✅ StockFilterLambda cache tests passed"
else
    echo "❌ StockFilterLambda cache tests failed"
    STOCK_FILTER_FAILED=1
fi

echo ""

echo "🧪 Running Cache Integration Tests..."
mvn test -Dtest=CacheIntegrationTest -q

if [ $? -eq 0 ]; then
    echo "✅ Cache integration tests passed"
else
    echo "❌ Cache integration tests failed"
    INTEGRATION_FAILED=1
fi

echo ""

echo "📊 Test Summary"
echo "==============="

if [ -z "$CACHE_MANAGER_FAILED" ] && [ -z "$STOCK_FILTER_FAILED" ] && [ -z "$INTEGRATION_FAILED" ]; then
    echo "🎉 All cache tests passed!"
    echo ""
    echo "✅ CacheManager functionality verified"
    echo "✅ StockFilterLambda cache integration verified"
    echo "✅ End-to-end cache behavior verified"
    echo ""
    echo "🚀 Cache logic is working correctly!"
    exit 0
else
    echo "❌ Some tests failed:"
    [ ! -z "$CACHE_MANAGER_FAILED" ] && echo "   - CacheManager tests"
    [ ! -z "$STOCK_FILTER_FAILED" ] && echo "   - StockFilterLambda cache tests"
    [ ! -z "$INTEGRATION_FAILED" ] && echo "   - Cache integration tests"
    echo ""
    echo "🔍 Check the test output above for details"
    exit 1
fi

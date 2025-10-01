#!/bin/bash

# Simplified test runner for ScanEarningsLambda
# This script runs the essential tests without complexity

echo "🚀 Running Simplified Tests for ScanEarningsLambda"
echo "=================================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    print_error "Maven is not installed or not in PATH"
    exit 1
fi

echo ""
echo "📋 Test Plan:"
echo "1. Unit Tests (fast, mocked dependencies)"
echo "2. Simplified Integration Tests (comprehensive but simple)"
echo "3. Optional: Real API Tests (if enabled)"
echo ""

# Run unit tests
echo "🔧 Running Unit Tests..."
if mvn test -Dtest=ScanEarningsLambdaTest; then
    print_status "Unit tests passed"
else
    print_error "Unit tests failed"
    exit 1
fi

echo ""

# Run simplified integration tests
echo "🔧 Running Simplified Integration Tests..."
if mvn test -Dtest=ScanEarningsLambdaSimplifiedTest; then
    print_status "Simplified integration tests passed"
else
    print_error "Simplified integration tests failed"
    exit 1
fi

echo ""

# Optional: Run real API tests if enabled
if [ "$1" = "--with-real-api" ]; then
    echo "🔧 Running Real API Tests (this will make actual API calls)..."
    print_warning "This will consume Finnhub API rate limits"
    
    if mvn test -Dtest=ScanEarningsLambdaSimplifiedTest -DrunRealApiTests=true; then
        print_status "Real API tests passed"
    else
        print_warning "Real API tests failed (this might be due to rate limits or network issues)"
    fi
else
    echo "💡 To run real API tests, use: $0 --with-real-api"
fi

echo ""
echo "📊 Test Summary:"
echo "=================="
echo "✅ Unit Tests: Core business logic with mocked dependencies"
echo "✅ Integration Tests: Comprehensive testing with simplified structure"
echo "💡 Real API Tests: Available with --with-real-api flag"

echo ""
print_status "All essential tests completed successfully!"
echo ""
echo "🎯 Simplified Test Structure Benefits:"
echo "• Single comprehensive test class for integration scenarios"
echo "• Focused unit tests for core business logic"
echo "• Optional real API testing when needed"
echo "• Easy to understand and maintain"
echo "• Fast execution for CI/CD pipelines"

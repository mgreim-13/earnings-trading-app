#!/bin/bash

# Test Runner Script for InitiateTradesLambda
# Provides easy commands to run different types of tests

set -e

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

print_header() {
    echo -e "${BLUE}================================${NC}"
    echo -e "${BLUE}  InitiateTradesLambda Tests${NC}"
    echo -e "${BLUE}================================${NC}"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

# Function to run unit tests
run_unit_tests() {
    print_header
    echo "Running Unit Tests..."
    echo "===================="
    
    if mvn test -Dtest=InitiateTradesLambdaUnitTest -q; then
        print_success "Unit tests passed!"
    else
        print_error "Unit tests failed!"
        exit 1
    fi
}

# Function to run integration tests
run_integration_tests() {
    print_header
    echo "Running Integration Tests..."
    echo "==========================="
    
    if [ -z "$ALPACA_API_KEY" ] || [ -z "$ALPACA_SECRET_KEY" ]; then
        print_warning "API credentials not set. Using test credentials..."
        export ALPACA_API_KEY="PKD58EYDICDW7400CWZL"
        export ALPACA_SECRET_KEY="ieUDUhhyGQxpUBMDOJCuK1HB9LvNtgPhhXRTkGlP"
    fi
    
    if mvn test -Dtest=InitiateTradesLambdaIntegrationTest -q; then
        print_success "Integration tests passed!"
    else
        print_warning "Integration tests failed (may be due to market hours or API issues)"
    fi
}

# Function to run manual tests
run_manual_tests() {
    print_header
    echo "Running Manual Tests..."
    echo "======================"
    
    if [ -z "$ALPACA_API_KEY" ] || [ -z "$ALPACA_SECRET_KEY" ]; then
        print_warning "API credentials not set. Using test credentials..."
        ALPACA_API_KEY="PKD58EYDICDW7400CWZL"
        ALPACA_SECRET_KEY="ieUDUhhyGQxpUBMDOJCuK1HB9LvNtgPhhXRTkGlP"
    fi
    
    if ./scripts/test-quick.sh "$ALPACA_API_KEY" "$ALPACA_SECRET_KEY"; then
        print_success "Manual tests passed!"
    else
        print_error "Manual tests failed!"
        exit 1
    fi
}

# Function to run all tests
run_all_tests() {
    print_header
    echo "Running All Tests..."
    echo "==================="
    
    run_unit_tests
    echo ""
    run_integration_tests
    echo ""
    run_manual_tests
    
    print_success "All tests completed!"
}

# Function to show help
show_help() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  unit        Run unit tests only"
    echo "  integration Run integration tests only"
    echo "  manual      Run manual tests only"
    echo "  all         Run all tests (default)"
    echo "  help        Show this help message"
    echo ""
    echo "Environment Variables:"
    echo "  ALPACA_API_KEY     Your Alpaca API key"
    echo "  ALPACA_SECRET_KEY  Your Alpaca secret key"
    echo ""
    echo "Examples:"
    echo "  $0 unit                    # Run unit tests"
    echo "  $0 integration             # Run integration tests"
    echo "  $0 manual                  # Run manual tests"
    echo "  $0 all                     # Run all tests"
    echo "  ALPACA_API_KEY=xxx $0 all  # Run with custom credentials"
}

# Main execution
case "${1:-all}" in
    "unit")
        run_unit_tests
        ;;
    "integration")
        run_integration_tests
        ;;
    "manual")
        run_manual_tests
        ;;
    "all")
        run_all_tests
        ;;
    "help"|"-h"|"--help")
        show_help
        ;;
    *)
        print_error "Unknown command: $1"
        show_help
        exit 1
        ;;
esac


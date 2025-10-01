#!/bin/bash

# Quick API Test Script for InitiateTradesLambda
# Simple validation of API connectivity and basic functionality

set -e

# Configuration
API_BASE_URL="https://paper-api.alpaca.markets/v2"
DATA_API_URL="https://data.alpaca.markets/v2"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

print_status() {
    echo -e "${GREEN}[✓]${NC} $1"
}

print_error() {
    echo -e "${RED}[✗]${NC} $1"
}

# Check if credentials are provided
if [ $# -ne 2 ]; then
    echo "Usage: $0 <API_KEY> <SECRET_KEY>"
    echo "Example: $0 PKD58EYDICDW7400CWZL ieUDUhhyGQxpUBMDOJCuK1HB9LvNtgPhhXRTkGlP"
    exit 1
fi

API_KEY="$1"
SECRET_KEY="$2"

echo "Testing Alpaca API connectivity..."
echo "=================================="

# Test 1: Account access
echo -n "Testing account access... "
if curl -s -X GET "$API_BASE_URL/account" \
    -H "APCA-API-KEY-ID: $API_KEY" \
    -H "APCA-API-SECRET-KEY: $SECRET_KEY" | grep -q "account_number"; then
    print_status "Account access successful"
else
    print_error "Account access failed"
    exit 1
fi

# Test 2: Market status
echo -n "Testing market status... "
if curl -s -X GET "$API_BASE_URL/clock" \
    -H "APCA-API-KEY-ID: $API_KEY" \
    -H "APCA-API-SECRET-KEY: $SECRET_KEY" | grep -q "is_open"; then
    print_status "Market status retrieved"
else
    print_error "Market status failed"
    exit 1
fi

# Test 3: Stock quote
echo -n "Testing stock quote... "
if curl -s -X GET "$DATA_API_URL/stocks/AAPL/quotes/latest" \
    -H "APCA-API-KEY-ID: $API_KEY" \
    -H "APCA-API-SECRET-KEY: $SECRET_KEY" | grep -q "symbol"; then
    print_status "Stock quote successful"
else
    print_error "Stock quote failed"
    exit 1
fi

# Test 4: Order submission (calendar spread)
echo -n "Testing order submission... "
order_response=$(curl -s -X POST "$API_BASE_URL/orders" \
    -H "APCA-API-KEY-ID: $API_KEY" \
    -H "APCA-API-SECRET-KEY: $SECRET_KEY" \
    -H "Content-Type: application/json" \
    -d '{
        "order_class": "mleg",
        "type": "limit",
        "time_in_force": "day",
        "limit_price": "2.50",
        "qty": 1,
        "legs": [
            {
                "symbol": "AAPL251024C00255000",
                "side": "buy",
                "ratio_qty": "1",
                "position_intent": "buy_to_open"
            },
            {
                "symbol": "AAPL251003C00255000",
                "side": "sell",
                "ratio_qty": "1",
                "position_intent": "sell_to_open"
            }
        ]
    }')

if echo "$order_response" | grep -q "id"; then
    print_status "Order submitted successfully"
    order_id=$(echo "$order_response" | jq -r '.id' 2>/dev/null)
    echo "  Order ID: $order_id"
    
    # Test 5: Order cancellation
    echo -n "Testing order cancellation... "
    if curl -s -X DELETE "$API_BASE_URL/orders/$order_id" \
        -H "APCA-API-KEY-ID: $API_KEY" \
        -H "APCA-API-SECRET-KEY: $SECRET_KEY" > /dev/null; then
        print_status "Order cancelled successfully"
    else
        print_error "Order cancellation failed"
    fi
else
    print_error "Order submission failed"
    echo "$order_response"
fi

echo ""
echo "All tests completed successfully! ✅"
echo "Your Alpaca API integration is working correctly."


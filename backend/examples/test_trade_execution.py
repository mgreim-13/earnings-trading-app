#!/usr/bin/env python3
"""
Example script demonstrating how to use the new trade execution endpoint.

This script shows how to test calendar spread entry and exit functionality
via the /trades/execute endpoint using curl commands.

Usage:
    python examples/test_trade_execution.py

Or use the curl commands directly:
    # Test entry trades
    curl -X POST "http://localhost:8000/trades/execute" \
         -H "Content-Type: application/json" \
         -d '{"order_type": "entry", "trades": [...]}'
    
    # Test exit trades  
    curl -X POST "http://localhost:8000/trades/execute" \
         -H "Content-Type: application/json" \
         -d '{"order_type": "exit", "trades": [...]}'
"""

import json
import requests
from datetime import datetime, timedelta

# API configuration
API_BASE_URL = "http://localhost:8000"

def test_entry_trades():
    """Test executing entry trades."""
    print("🔄 Testing Entry Trades")
    print("=" * 50)
    
    # Sample entry trade data
    entry_trades = {
        "order_type": "entry",
        "trades": [
            {
                "ticker": "AAPL",                    # ← Changed from 'symbol' to 'ticker'
                "earnings_date": "2024-01-15",
                "earnings_time": "amc",              # ← Added required field
                "recommendation_score": 85,          # ← Added required field
                "filters": {},                       # ← Added required field
                "reasoning": "Direct endpoint test", # ← Added required field
                "status": "selected",                # ← Added required field
                "short_expiration": "2024-01-19",
                "long_expiration": "2024-02-16",
                "quantity": 1
            },
            {
                "ticker": "MSFT",                    # ← Changed from 'symbol' to 'ticker'
                "earnings_date": "2024-01-16", 
                "earnings_time": "amc",              # ← Added required field
                "recommendation_score": 82,          # ← Added required field
                "filters": {},                       # ← Added required field
                "reasoning": "Direct endpoint test", # ← Added required field
                "status": "selected",                # ← Added required field
                "short_expiration": "2024-01-19",
                "long_expiration": "2024-02-16",
                "quantity": 1
            }
        ]
    }
    
    print(f"📤 Sending entry trade request for {len(entry_trades['trades'])} trades...")
    print(f"   Symbols: {[t['ticker'] for t in entry_trades['trades']]}")  # ← Changed from 'symbol' to 'ticker'
    
    try:
        response = requests.post(
            f"{API_BASE_URL}/trades/execute",
            json=entry_trades,
            headers={"Content-Type": "application/json"}
        )
        
        print(f"📥 Response Status: {response.status_code}")
        
        if response.status_code == 200:
            result = response.json()
            print(f"✅ Success: {result['message']}")
            print(f"   Order Type: {result['data']['order_type']}")
            print(f"   Trade Count: {result['data']['trade_count']}")
            print(f"   Symbols: {result['data']['symbols']}")
            print(f"   Timestamp: {result['data']['timestamp']}")
        else:
            print(f"❌ Error: {response.text}")
            
    except requests.exceptions.RequestException as e:
        print(f"❌ Request failed: {e}")
    
    print()

def test_exit_trades():
    """Test executing exit trades."""
    print("🔄 Testing Exit Trades")
    print("=" * 50)
    
    # Sample exit trade data
    exit_trades = {
        "order_type": "exit",
        "trades": [
            {
                "ticker": "AAPL",                    # ← Changed from 'symbol' to 'ticker'
                "trade_id": 123,
                "earnings_time": "amc",              # ← Added required field
                "recommendation_score": 85,          # ← Added required field
                "filters": {},                       # ← Added required field
                "reasoning": "Direct endpoint test", # ← Added required field
                "status": "selected",                # ← Added required field
                "short_expiration": "2024-01-19",
                "long_expiration": "2024-02-16",
                "quantity": 1
            },
            {
                "ticker": "MSFT",                    # ← Changed from 'symbol' to 'ticker'
                "trade_id": 456,
                "earnings_time": "amc",              # ← Added required field
                "recommendation_score": 82,          # ← Added required field
                "filters": {},                       # ← Added required field
                "reasoning": "Direct endpoint test", # ← Added required field
                "status": "selected",                # ← Added required field
                "short_expiration": "2024-01-19",
                "long_expiration": "2024-02-16",
                "quantity": 1
            }
        ]
    }
    
    print(f"📤 Sending exit trade request for {len(exit_trades['trades'])} trades...")
    print(f"   Symbols: {[t['ticker'] for t in exit_trades['trades']]}")  # ← Changed from 'symbol' to 'ticker'
    
    try:
        response = requests.post(
            f"{API_BASE_URL}/trades/execute",
            json=exit_trades,
            headers={"Content-Type": "application/json"}
        )
        
        print(f"📥 Response Status: {response.status_code}")
        
        if response.status_code == 200:
            result = response.json()
            print(f"✅ Success: {result['message']}")
            print(f"   Order Type: {result['data']['order_type']}")
            print(f"   Trade Count: {result['data']['trade_count']}")
            print(f"   Symbols: {result['data']['symbols']}")
            print(f"   Timestamp: {result['data']['timestamp']}")
        else:
            print(f"❌ Error: {response.text}")
            
    except requests.exceptions.RequestException as e:
        print(f"❌ Request failed: {e}")
    
    print()

def test_validation_errors():
    """Test validation error handling."""
    print("🔄 Testing Validation Errors")
    print("=" * 50)
    
    # Test cases that should fail validation
    test_cases = [
        {
            "name": "Missing order_type",
            "data": {"trades": []},
            "expected_error": "order_type is required"
        },
        {
            "name": "Invalid order_type",
            "data": {"order_type": "invalid", "trades": []},
            "expected_error": "order_type must be 'entry' or 'exit'"
        },
        {
            "name": "Missing trades",
            "data": {"order_type": "entry"},
            "expected_error": "trades must be a non-empty list"
        },
        {
            "name": "Empty trades list",
            "data": {"order_type": "entry", "trades": []},
            "expected_error": "trades must be a non-empty list"
        },
        {
            "name": "Missing symbol",
            "data": {"order_type": "entry", "trades": [{"earnings_date": "2024-01-15"}]},
            "expected_error": "must have a 'symbol' field"
        },
        {
            "name": "Missing earnings_date for entry",
            "data": {"order_type": "entry", "trades": [{"symbol": "AAPL"}]},
            "expected_error": "must have an 'earnings_date' field"
        },
        {
            "name": "Missing trade_id for exit",
            "data": {"order_type": "exit", "trades": [{"symbol": "AAPL"}]},
            "expected_error": "must have a 'trade_id' field"
        }
    ]
    
    for test_case in test_cases:
        print(f"🧪 Testing: {test_case['name']}")
        
        try:
            response = requests.post(
                f"{API_BASE_URL}/trades/execute",
                json=test_case['data'],
                headers={"Content-Type": "application/json"}
            )
            
            if response.status_code == 400:
                error_detail = response.json().get('detail', '')
                if test_case['expected_error'] in error_detail:
                    print(f"   ✅ Correctly rejected: {test_case['expected_error']}")
                else:
                    print(f"   ❌ Unexpected error: {error_detail}")
            else:
                print(f"   ❌ Should have failed validation but got status {response.status_code}")
                
        except requests.exceptions.RequestException as e:
            print(f"   ❌ Request failed: {e}")
        
        print()

def print_curl_examples():
    """Print example curl commands."""
    print("🔄 Example cURL Commands")
    print("=" * 50)
    
    # Entry trades example
    entry_example = {
        "order_type": "entry",
        "trades": [
            {
                "ticker": "AAPL",                    # ← Changed from 'symbol' to 'ticker'
                "earnings_date": "2024-01-15",
                "earnings_time": "amc",              # ← Added required field
                "recommendation_score": 85,          # ← Added required field
                "filters": {},                       # ← Added required field
                "reasoning": "Direct endpoint test", # ← Added required field
                "status": "selected",                # ← Added required field
                "short_expiration": "2024-01-19",
                "long_expiration": "2024-02-16",
                "quantity": 1
            }
        ]
    }
    
    print("📤 Entry Trades:")
    print(f"curl -X POST '{API_BASE_URL}/trades/execute' \\")
    print("     -H 'Content-Type: application/json' \\")
    print(f"     -d '{json.dumps(entry_example, indent=2)}'")
    print()
    
    # Exit trades example
    exit_example = {
        "order_type": "exit",
        "trades": [
            {
                "ticker": "AAPL",                    # ← Changed from 'symbol' to 'ticker'
                "trade_id": 123,
                "earnings_time": "amc",              # ← Added required field
                "recommendation_score": 85,          # ← Added required field
                "filters": {},                       # ← Added required field
                "reasoning": "Direct endpoint test", # ← Added required field
                "status": "selected",                # ← Added required field
                "short_expiration": "2024-01-19",
                "long_expiration": "2024-02-16",
                "quantity": 1
            }
        ]
    }
    
    print("📤 Exit Trades:")
    print(f"curl -X POST '{API_BASE_URL}/trades/execute' \\")
    print("     -H 'Content-Type: application/json' \\")
    print(f"     -d '{json.dumps(exit_example, indent=2)}'")
    print()

def main():
    """Main function to run all tests."""
    print("🚀 Trade Execution Endpoint Test Suite")
    print("=" * 60)
    print(f"API Base URL: {API_BASE_URL}")
    print(f"Timestamp: {datetime.now().isoformat()}")
    print()
    
    # Test the endpoint functionality
    test_entry_trades()
    test_exit_trades()
    test_validation_errors()
    
    # Show curl examples
    print_curl_examples()
    
    print("✨ Test suite completed!")
    print()
    print("💡 Tips:")
    print("   - Make sure the API server is running on localhost:8000")
    print("   - Check the server logs for detailed execution information")
    print("   - The endpoint works with all trades and monitoring functionality")
    print("   - No time restrictions - can be run anytime for testing")

if __name__ == "__main__":
    main()

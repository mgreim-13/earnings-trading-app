# Trade Execution Endpoint Documentation

## Overview

The `/trades/execute` endpoint provides direct access to the `_execute_and_monitor_trades` method from the trading scheduler. This allows you to test calendar spread entry and exit functionality directly via HTTP requests, bypassing the normal scheduled job timing.

## Endpoint Details

- **URL**: `POST /trades/execute`
- **Content-Type**: `application/json`
- **Authentication**: None required (uses existing scheduler authentication)

## Request Format

### Entry Trades
```json
{
  "order_type": "entry",
  "trades": [
    {
      "ticker": "AAPL",                    # ← Use 'ticker', not 'symbol'
      "earnings_date": "2024-01-15",
      "earnings_time": "amc",              # ← Required field
      "recommendation_score": 85,          # ← Required field
      "filters": {},                       # ← Required field
      "reasoning": "Direct endpoint test", # ← Required field
      "status": "selected",                # ← Required field
      "short_expiration": "2024-01-19",
      "long_expiration": "2024-02-16",
      "quantity": 1
    }
  ]
}
```

### Exit Trades
```json
{
  "order_type": "exit",
  "trades": [
    {
      "ticker": "AAPL",                    # ← Use 'ticker', not 'symbol'
      "trade_id": 123,
      "earnings_time": "amc",              # ← Required field
      "recommendation_score": 85,          # ← Required field
      "filters": {},                       # ← Required field
      "reasoning": "Direct endpoint test", # ← Required field
      "status": "selected",                # ← Required field
      "short_expiration": "2024-01-19",
      "long_expiration": "2024-02-16",
      "quantity": 1
    }
  ]
}
```

## Required Fields

### For Entry Trades (`order_type: "entry"`)
- `ticker`: Stock ticker (e.g., "AAPL") - **Note: Use 'ticker', not 'symbol'**
- `earnings_date`: Earnings date in YYYY-MM-DD format
- `earnings_time`: Earnings time (default: "amc")
- `recommendation_score`: Recommendation score (0-100)
- `filters`: Filters object (can be empty {})
- `reasoning`: Reasoning for trade selection
- `status`: Trade status (should be "selected")
- `short_expiration`: Short option expiration date
- `long_expiration`: Long option expiration date
- `quantity`: Number of contracts

### For Exit Trades (`order_type: "exit"`)
- `ticker`: Stock ticker (e.g., "AAPL") - **Note: Use 'ticker', not 'symbol'**
- `trade_id`: Database trade ID to exit
- `earnings_time`: Earnings time (default: "amc")
- `recommendation_score`: Recommendation score (0-100)
- `filters`: Filters object (can be empty {})
- `reasoning`: Reasoning for trade selection
- `status`: Trade status (should be "selected")
- `short_expiration`: Short option expiration date
- `long_expiration`: Long option expiration date
- `quantity`: Number of contracts

## Response Format

### Success Response (200)
```json
{
  "success": true,
  "message": "Successfully initiated execution and monitoring for 1 entry trades",
  "data": {
    "order_type": "entry",
    "trade_count": 1,
    "symbols": ["AAPL"],
    "timestamp": "2024-01-15T10:30:00.000Z"
  }
}
```

### Error Response (400/500)
```json
{
  "detail": "order_type is required ('entry' or 'exit')"
}
```

## cURL Examples

### Test Entry Trades
```bash
curl -X POST "http://localhost:8000/trades/execute" \
     -H "Content-Type: application/json" \
     -d '{
       "order_type": "entry",
       "trades": [
         {
           "ticker": "AAPL",
           "earnings_date": "2024-01-15",
           "earnings_time": "amc",
           "recommendation_score": 85,
           "filters": {},
           "reasoning": "Direct endpoint test",
           "status": "selected",
           "short_expiration": "2024-01-19",
           "long_expiration": "2024-02-16",
           "quantity": 1
         }
       ]
     }'
```

### Test Exit Trades
```bash
curl -X POST "http://localhost:8000/trades/execute" \
     -H "Content-Type: application/json" \
     -d '{
       "order_type": "exit",
       "trades": [
         {
           "ticker": "AAPL",
           "trade_id": 123,
           "earnings_time": "amc",
           "recommendation_score": 85,
           "filters": {},
           "reasoning": "Direct endpoint test",
           "status": "selected",
           "short_expiration": "2024-01-19",
           "long_expiration": "2024-02-16",
           "quantity": 1
         }
       ]
     }'
```

## Python Example

```python
import requests
import json

# API configuration
API_BASE_URL = "http://localhost:8000"

# Entry trade data
entry_trades = {
    "order_type": "entry",
    "trades": [
        {
            "ticker": "AAPL",                    # ← Use 'ticker', not 'symbol'
            "earnings_date": "2024-01-15",
            "earnings_time": "amc",              # ← Required field
            "recommendation_score": 85,          # ← Required field
            "filters": {},                       # ← Required field
            "reasoning": "Direct endpoint test", # ← Required field
            "status": "selected",                # ← Required field
            "short_expiration": "2024-01-19",
            "long_expiration": "2024-02-16",
            "quantity": 1
        }
    ]
}

# Execute entry trades
response = requests.post(
    f"{API_BASE_URL}/trades/execute",
    json=entry_trades,
    headers={"Content-Type": "application/json"}
)

if response.status_code == 200:
    result = response.json()
    print(f"✅ Success: {result['message']}")
    print(f"   Order Type: {result['data']['order_type']}")
    print(f"   Trade Count: {result['data']['trade_count']}")
    print(f"   Symbols: {result['data']['symbols']}")
else:
    print(f"❌ Error: {response.text}")
```

## What Happens When You Call This Endpoint

1. **Validation**: The endpoint validates the request format and required fields
2. **Execution**: Calls `scheduler._execute_and_monitor_trades(trades, order_type)`
3. **Trade Execution**: 
   - For entry: Uses `execute_trades_with_parallel_preparation()`
   - For exit: Uses `execute_exit_trades()`
4. **Monitoring Setup**: Schedules comprehensive monitoring for executed trades
5. **Status Updates**: For exit trades, updates trade status to 'exiting'
6. **Response**: Returns success/error information

## Key Benefits

- **No Time Restrictions**: Can be run anytime, not just at scheduled times
- **Direct Testing**: Bypass normal scheduling for immediate testing
- **Full Functionality**: Uses all existing execution and monitoring logic
- **Real Monitoring**: Sets up actual monitoring jobs, not just execution
- **Status Tracking**: Maintains proper trade status updates

## Monitoring Behavior

The endpoint maintains the exact same monitoring behavior as the scheduled jobs:

- **Entry Monitoring**: `is_exit=False`, 5% timeout premium after 8 minutes
- **Exit Monitoring**: `is_exit=True`, 10% timeout premium after 8 minutes
- **Price Updates**: 1% threshold for price updates during 0-8 minute period
- **Fallback Logic**: Timeout orders after 8 minutes, market orders as final fallback
- **Comprehensive Monitoring**: Full monitoring loop with all safety features

## Error Handling

The endpoint includes comprehensive validation:

- Missing or invalid `order_type`
- Missing or empty `trades` list
- Missing required fields for each trade type
- Proper error messages for debugging

## Security Notes

- This endpoint bypasses normal scheduling but maintains all trading safety checks
- Uses the same authentication and authorization as other endpoints
- All existing trading safety mechanisms remain in place
- No additional security risks beyond normal trading operations

## Testing

Run the included test script to verify functionality:

```bash
cd backend
python examples/test_trade_execution.py
```

This will test both entry and exit scenarios and show example cURL commands.

## Troubleshooting

- **400 Bad Request**: Check request format and required fields
- **500 Internal Server Error**: Check server logs for detailed error information
- **Connection Refused**: Ensure the API server is running on localhost:8000
- **Validation Errors**: Verify all required fields are present and correctly formatted

## Integration

This endpoint integrates seamlessly with the existing trading system:

- Uses the same scheduler instance
- Maintains all existing monitoring infrastructure
- Preserves trade history and status tracking
- Works with existing database and Alpaca client configurations

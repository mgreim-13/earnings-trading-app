# Market Scheduler Lambda

This Lambda function provides conditional scheduling for all trading-related Lambda functions based on US stock market holidays and early closure days. It uses Finnhub API to determine market status and conditionally triggers other Lambda functions.

## Features

- **Holiday Detection**: Uses Finnhub API to check for market holidays
- **Early Closure Detection**: Handles early closure days (Black Friday, Christmas Eve, etc.)
- **Conditional Triggering**: Only triggers other lambdas when market is open
- **Comprehensive Scheduling**: Manages all trading lambda schedules in one place
- **DynamoDB Management**: Creates and cleans up temporary tables for trading data

## Schedule Configuration

### ScanEarningsLambda
- **Normal Days**: 3:30 PM EST (M-F, non-holidays)
- **Early Closure Days**: 12:30 PM EST
- **Holidays**: Skipped

### StockFilterLambda
- **Normal Days**: 3:35 PM EST (M-F, non-holidays)
- **Early Closure Days**: 12:35 PM EST
- **Holidays**: Skipped

### InitiateTradesLambda
- **Normal Days**: 3:45 PM EST (M-F, non-holidays)
- **Early Closure Days**: 12:45 PM EST
- **Holidays**: Skipped


### InitiateExitTradesLambda
- **All Market Days**: 9:45 AM EST (M-F, non-holidays and early closure days)
- **Holidays**: Skipped

## DynamoDB Table Management

### Table Creation
- **Normal Days**: 3:25 PM EST (5 minutes before ScanEarningsLambda)
- **Early Closure Days**: 12:25 PM EST (5 minutes before ScanEarningsLambda)
- **Tables Created**: `earnings-table`, `filtered-tickers-table`

### Table Cleanup
- **Normal Days**: 4:00 PM EST (30 minutes after ScanEarningsLambda)
- **Early Closure Days**: 1:00 PM EST (30 minutes after ScanEarningsLambda)
- **Cleanup Method**: Complete table deletion (no TTL)

## Environment Variables

- `FINNHUB_SECRET_NAME`: Name of the Secrets Manager secret containing Finnhub API key
- `SCAN_EARNINGS_LAMBDA`: Name of the ScanEarningsLambda function
- `STOCK_FILTER_LAMBDA`: Name of the StockFilterLambda function
- `INITIATE_TRADES_LAMBDA`: Name of the InitiateTradesLambda function
- `INITIATE_EXIT_TRADES_LAMBDA`: Name of the InitiateExitTradesLambda function

## Deployment

1. **Build the project**:
   ```bash
   ./build.sh
   ```

2. **Deploy with SAM**:
   ```bash
   sam deploy --guided
   ```

3. **Configure secrets**:
   - Update the Finnhub API key in AWS Secrets Manager
   - Ensure all target Lambda function names are correct

## Safety Features

- **Conservative Approach**: Assumes market is closed on any error
- **Fallback Logic**: Falls back to basic time/day checks if API calls fail
- **Comprehensive Validation**: Each target lambda also performs its own market open check

## Monitoring

- **CloudWatch Logs**: `/aws/lambda/{environment}-market-scheduler-lambda`
- **EventBridge Rules**: Check AWS Console for rule execution status
- **Target Lambda Logs**: Each triggered lambda will log its execution status

## Updating Schedules

To modify schedules, update the EventBridge rules in `template.yaml` and redeploy:

```bash
sam deploy
```

## Testing

The Lambda can be tested manually by sending events with different sources:

```json
{
  "source": "scan-earnings-schedule"
}
```

Available sources:
- `scan-earnings-schedule`
- `stock-filter-schedule`
- `initiate-trades-schedule`
- `monitor-trades-schedule`
- `initiate-exit-trades-schedule`

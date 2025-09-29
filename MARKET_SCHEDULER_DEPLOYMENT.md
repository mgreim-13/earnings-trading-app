# Market Scheduler Deployment Guide

This guide explains how to deploy the new Market Scheduler system that conditionally triggers trading lambdas based on market holidays.

## Overview

The Market Scheduler Lambda replaces direct EventBridge scheduling with conditional scheduling based on:
- US stock market holidays (using Finnhub API)
- Early closure days
- Market open/closed status verification

## Architecture

```
EventBridge Rules → Market Scheduler Lambda → Target Lambdas
                      ↓
                 Finnhub API (holiday data)
                      ↓
                 Alpaca API (market status)
```

## Prerequisites

1. **Finnhub API Key**: Get a free API key from [Finnhub](https://finnhub.io/)
2. **Existing Lambda Functions**: All target lambdas must be deployed first
3. **AWS CLI and SAM CLI**: Installed and configured

## Deployment Steps

### Step 1: Deploy Market Scheduler Lambda

```bash
cd MarketSchedulerLambda
./build.sh
sam deploy --guided
```

**Configuration during deployment:**
- Environment: `prod` (or your preferred environment)
- Finnhub Secret Name: `finnhub-api-key` (or your preferred name)
- Target Lambda Names: Use the actual function names from your AWS account

### Step 2: Configure Finnhub API Key

1. Go to AWS Secrets Manager
2. Find the secret named `finnhub-api-key` (or your configured name)
3. Update the secret value:
   ```json
   {
     "apiKey": "YOUR_FINNHUB_API_KEY_HERE"
   }
   ```

### Step 3: Update Target Lambda Function Names

Update the Market Scheduler Lambda environment variables with your actual function names:

```bash
aws lambda update-function-configuration \
  --function-name prod-market-scheduler-lambda \
  --environment Variables='{
    "FINNHUB_SECRET_NAME":"finnhub-api-key",
    "SCAN_EARNINGS_LAMBDA":"prod-ScanEarningsLambda",
    "STOCK_FILTER_LAMBDA":"prod-stock-filter-lambda",
    "INITIATE_TRADES_LAMBDA":"InitiateTradesLambda",
    "MONITOR_TRADES_LAMBDA":"MonitorTradesLambda",
    "INITIATE_EXIT_TRADES_LAMBDA":"prod-initiate-exit-trades-lambda"
  }'
```

### Step 4: Disable Direct Scheduling (Optional)

If you want to completely remove direct EventBridge scheduling from existing lambdas:

```bash
./disable-direct-scheduling.sh
```

Then manually update each lambda template to comment out the Events sections.

### Step 5: Verify Deployment

1. **Check EventBridge Rules**: Go to AWS EventBridge console and verify rules are created
2. **Test Manual Trigger**: Use AWS Lambda console to test the Market Scheduler with different event sources
3. **Monitor Logs**: Check CloudWatch logs for both Market Scheduler and target lambdas

## Schedule Configuration

### Current Schedules

| Lambda | Normal Days | Early Closure | Holidays |
|--------|-------------|---------------|----------|
| ScanEarningsLambda | 3:30 PM EST | 12:30 PM EST | Skipped |
| StockFilterLambda | 3:35 PM EST | 12:35 PM EST | Skipped |
| InitiateTradesLambda | 3:45 PM EST | 12:45 PM EST | Skipped |
| MonitorTradesLambda | 9:45:30-10:00 AM, 3:45:30-4:00 PM EST | 9:45:30-10:00 AM, 12:45:30-1:00 PM EST | Skipped |
| InitiateExitTradesLambda | 9:45 AM EST | 9:45 AM EST | Skipped |

### Modifying Schedules

To change schedules, update the EventBridge rules in `MarketSchedulerLambda/template.yaml`:

```yaml
Events:
  ScanEarningsSchedule:
    Type: Schedule
    Properties:
      Schedule: cron(30 20 ? * MON-FRI *)  # 3:30 PM EST
```

Then redeploy:
```bash
cd MarketSchedulerLambda
sam deploy
```

## Safety Features

### Double Market Check
1. **Market Scheduler**: Checks holidays and basic constraints
2. **Target Lambdas**: Each lambda performs its own market open check using Alpaca API

### Error Handling
- **Conservative Approach**: Assumes market is closed on any error
- **Fallback Logic**: Falls back to basic checks if API calls fail
- **Graceful Degradation**: Target lambdas can still run with their own market checks

## Monitoring

### CloudWatch Logs
- Market Scheduler: `/aws/lambda/{environment}-market-scheduler-lambda`
- Target Lambdas: `/aws/lambda/{function-name}`

### Key Metrics to Monitor
- Market Scheduler execution success rate
- Target lambda trigger success rate
- Holiday detection accuracy
- API call failures

### Alerts
Set up CloudWatch alarms for:
- Market Scheduler failures
- Target lambda execution failures
- API rate limit exceeded

## Troubleshooting

### Common Issues

1. **Target Lambda Not Triggered**
   - Check function names in environment variables
   - Verify IAM permissions for Lambda invoke
   - Check Market Scheduler logs for errors

2. **Holiday Detection Not Working**
   - Verify Finnhub API key is correct
   - Check API rate limits
   - Review Market Scheduler logs

3. **Market Status Check Failing**
   - Verify Alpaca credentials in target lambdas
   - Check network connectivity
   - Review TradingCommonUtils logs

### Debug Commands

```bash
# Check Market Scheduler logs
aws logs tail /aws/lambda/prod-market-scheduler-lambda --follow

# Test Market Scheduler manually
aws lambda invoke --function-name prod-market-scheduler-lambda \
  --payload '{"source":"scan-earnings-schedule"}' response.json

# Check EventBridge rules
aws events list-rules --name-prefix "prod-market-scheduler"
```

## Cost Optimization

- **EventBridge Rules**: ~$1 per million rule evaluations
- **Lambda Invocations**: Pay per invocation and duration
- **API Calls**: Finnhub free tier, Alpaca API costs
- **CloudWatch Logs**: Standard logging costs

## Security Considerations

- **API Keys**: Store in AWS Secrets Manager
- **IAM Permissions**: Least privilege principle
- **Network Security**: Use VPC if required
- **Logging**: Monitor for sensitive data exposure

## Rollback Plan

If issues occur, you can quickly rollback by:

1. **Re-enable Direct Scheduling**: Uncomment Events sections in lambda templates
2. **Disable Market Scheduler**: Disable EventBridge rules
3. **Redeploy**: Deploy updated lambda templates

```bash
# Disable Market Scheduler rules
aws events disable-rule --name "prod-market-scheduler-ScanEarningsSchedule"
# ... repeat for other rules
```

## Support

For issues or questions:
1. Check CloudWatch logs first
2. Review this deployment guide
3. Check AWS EventBridge and Lambda documentation
4. Verify API credentials and permissions

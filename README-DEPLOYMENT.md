# Trading Lambda Deployment Guide

This guide will help you deploy the complete trading lambda infrastructure to AWS using CloudFormation.

## Prerequisites

1. **AWS CLI installed and configured**
   ```bash
   aws configure
   ```

2. **Java 11+ and Maven installed**
   ```bash
   java -version
   mvn -version
   ```

3. **API Credentials ready**
   - Alpaca API Key and Secret
   - Finnhub API Key

## Quick Start

### 1. Setup API Secrets
```bash
./setup-secrets.sh
```
This will securely store your API credentials in AWS Secrets Manager.

### 2. Deploy Everything
```bash
./deploy.sh
```
This will:
- Build all Lambda functions
- Deploy CloudFormation stack
- Update Lambda codes
- Set up EventBridge scheduling

## What Gets Deployed

### Lambda Functions
- **MarketSchedulerLambda**: Runs at 1 AM EST on weekdays, orchestrates all other lambdas
- **ScanEarningsLambda**: Scans earnings data from Finnhub API
- **StockFilterLambda**: Filters stocks based on criteria
- **InitiateTradesLambda**: Initiates trading positions
- **MonitorTradesLambda**: Monitors active trades
- **InitiateExitTradesLambda**: Exits positions at 9:45 AM EST

### DynamoDB Tables (Created Dynamically)
- **earnings-data**: Created by MarketSchedulerLambda only on market-open days
- **filtered-stocks**: Created by MarketSchedulerLambda only on market-open days
- **30-minute TTL**: Automatic cleanup after 30 minutes
- **Pay-per-request billing**: Only pay when tables are used

### EventBridge Rules
- **market-scheduler-rule**: Triggers MarketSchedulerLambda at 1 AM EST on weekdays

## Architecture

```
EventBridge (1 AM EST weekdays)
    ↓
MarketSchedulerLambda
    ↓ (checks market status)
    ├── ScanEarningsLambda (3:30 PM EST)
    ├── StockFilterLambda (3:35 PM EST)
    ├── InitiateTradesLambda (3:45 PM EST)
    ├── MonitorTradesLambda (every 30 seconds)
    └── InitiateExitTradesLambda (9:45 AM EST)
```

## Cost Optimization Features

1. **Dynamic table creation**: Tables only created on market-open days
2. **Pay-per-request DynamoDB**: Only pay for what you use
3. **Minimal Lambda memory**: Optimized for cost vs performance
4. **30-minute TTL**: Automatic data cleanup
5. **Weekday-only execution**: No weekend costs
6. **Conservative error handling**: Prevents unnecessary API calls
7. **No idle table costs**: Tables don't exist on weekends/holidays

## Environment Variables

The lambdas are configured with these environment variables:

- `ALPACA_SECRET_NAME`: AWS Secrets Manager secret for Alpaca credentials
- `FINNHUB_SECRET_NAME`: AWS Secrets Manager secret for Finnhub credentials
- `DYNAMODB_TABLE`: DynamoDB table name for data storage
- `PAPER_TRADING`: Set to 'true' for paper trading mode

## Monitoring

### CloudWatch Logs
Each lambda creates its own log group:
- `/aws/lambda/{environment}-market-scheduler`
- `/aws/lambda/{environment}-scan-earnings`
- `/aws/lambda/{environment}-stock-filter`
- `/aws/lambda/{environment}-initiate-trades`
- `/aws/lambda/{environment}-monitor-trades`
- `/aws/lambda/{environment}-initiate-exit-trades`

### DynamoDB Monitoring
- Check table metrics in DynamoDB console
- Monitor TTL cleanup in CloudWatch metrics

## Testing

### Manual Testing
```bash
# Test MarketSchedulerLambda
aws lambda invoke \
  --function-name dev-market-scheduler \
  --payload '{"source": "scan-earnings-schedule"}' \
  response.json

# View response
cat response.json
```

### Check Logs
```bash
# View recent logs
aws logs describe-log-groups --log-group-name-prefix '/aws/lambda/dev-'

# Get specific log stream
aws logs get-log-events \
  --log-group-name '/aws/lambda/dev-market-scheduler' \
  --log-stream-name '2024/01/01/[$LATEST]abc123'
```

## Troubleshooting

### Common Issues

1. **Lambda timeout**: Increase timeout in CloudFormation template
2. **Memory issues**: Increase memory allocation
3. **Permission errors**: Check IAM role permissions
4. **API errors**: Verify secrets are correctly stored

### Debug Commands
```bash
# Check stack status
aws cloudformation describe-stacks --stack-name trading-lambdas-dev

# List all resources
aws cloudformation list-stack-resources --stack-name trading-lambdas-dev

# Check lambda configuration
aws lambda get-function --function-name dev-market-scheduler
```

## Cleanup

To remove all resources:
```bash
aws cloudformation delete-stack --stack-name trading-lambdas-dev
```

## Security Notes

- All API credentials are stored in AWS Secrets Manager
- IAM roles follow least privilege principle
- DynamoDB tables use pay-per-request billing
- No hardcoded credentials in code

## Support

If you encounter issues:
1. Check CloudWatch logs
2. Verify AWS credentials
3. Ensure all dependencies are installed
4. Check IAM permissions

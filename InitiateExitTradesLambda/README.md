# Initiate Exit Trades Lambda

AWS Lambda function for exiting multi-leg option positions based on predefined criteria.

## Overview

This Lambda function queries the Alpaca trading API to fetch all currently held positions, identifies option positions, groups them into multi-leg strategies, evaluates exit criteria, and submits exit orders to close out positions that meet the exit conditions.

## Features

- **Position Management**: Fetches all held positions from Alpaca API
- **Multi-leg Detection**: Groups option positions into multi-leg strategies based on underlying symbol, expiration, and strategy type
- **Exit Criteria Evaluation**: Evaluates positions based on profit targets, stop losses, and time-based exits
- **Order Submission**: Submits opposing multi-leg orders to close positions
- **Error Handling**: Comprehensive error handling with CloudWatch logging
- **Market Hours Check**: Only processes exits during market hours
- **Paper Trading Support**: Configurable for paper or live trading

## Exit Criteria

The function evaluates the following exit criteria for each multi-leg position group:

1. **Profit Target**: Exit when unrealized P&L reaches 50% of cost basis
2. **Stop Loss**: Exit when unrealized loss reaches 20% of cost basis  
3. **Time-based Exit**: Exit when days to expiration is less than 7 days

## Configuration

### Environment Variables

- `ALPACA_SECRET_NAME`: AWS Secrets Manager secret name containing Alpaca API credentials
- `PAPER_TRADING`: Set to "true" for paper trading, "false" for live trading
- `AWS_REGION`: AWS region for Secrets Manager access

### Alpaca Credentials Secret

The secret should contain JSON with the following structure:

```json
{
  "keyId": "your-alpaca-key-id",
  "secretKey": "your-alpaca-secret-key", 
  "baseUrl": "https://paper-api.alpaca.markets"
}
```

For live trading, use `https://api.alpaca.markets` as the base URL.

## Deployment

### Prerequisites

- Java 11 or higher
- Maven 3.6 or higher
- AWS CLI configured
- Alpaca trading account

### Build

```bash
mvn clean package
```

### Deploy

The Lambda function can be deployed using AWS CLI, Serverless Framework, or AWS CDK. Example AWS CLI deployment:

```bash
aws lambda create-function \
  --function-name InitiateExitTradesLambda \
  --runtime java11 \
  --role arn:aws:iam::ACCOUNT:role/lambda-execution-role \
  --handler com.trading.lambda.InitiateExitTradesLambda::handleRequest \
  --zip-file fileb://target/initiate-exit-trades-lambda-1.0.0.jar
```

## Monitoring

The function logs all activities to CloudWatch Logs. Key log events include:

- Position fetching and grouping
- Exit criteria evaluation results
- Order submission success/failure
- Error conditions with detailed context

## Error Handling

- **API Failures**: Logged to CloudWatch with retry recommendations
- **Order Submission Failures**: Individual order failures are logged with details
- **Market Closed**: Function exits gracefully when market is closed
- **Invalid Positions**: Positions that cannot be grouped or evaluated are logged and skipped

## Testing

Run unit tests:

```bash
mvn test
```

## Security

- API credentials are stored in AWS Secrets Manager
- IAM role should have minimal required permissions
- VPC configuration recommended for production use
- CloudTrail logging enabled for audit purposes

## Performance

- Designed to handle up to 50 positions efficiently
- Target execution time: 5-10 seconds per invocation
- Concurrent processing of multiple position groups
- Rate limiting compliance with Alpaca API

## Dependencies

- AWS SDK v2 for Lambda and Secrets Manager
- Alpaca Java SDK for trading API access
- Jackson for JSON processing
- Lombok for code generation
- SLF4J for logging


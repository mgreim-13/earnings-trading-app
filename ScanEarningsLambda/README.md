# Scan Earnings Lambda

A Java-based AWS Lambda function that fetches earnings data from Finnhub's API, filters for After Market Close (AMC) and Before Market Open (BMO) earnings, and writes results to a DynamoDB table. The function is designed to run on market-open days at 4:15 PM EST, triggered by EventBridge via Step Functions.

## Features

- **Local Market Status**: Determines market status using local calculation (no external API dependency)
- **Earnings Data Processing**: Fetches and filters earnings data from Finnhub API
- **DynamoDB Integration**: Efficiently stores earnings data with batch writes (tables cleaned up at 4:00 PM EST)
- **Holiday Awareness**: Skips weekends and US holidays (2025)
- **Error Handling**: Comprehensive error handling and logging
- **Rate Limiting**: Respects API rate limits (Finnhub: ~60 calls/min)

## Architecture

```
EventBridge (Cron: 4:15 PM EST) → Step Functions → Lambda → DynamoDB
                                                      ↓
                                              Finnhub API
```

## Prerequisites

- Java 21
- Maven 3.6+
- AWS CLI configured
- AWS SAM CLI
- Docker (for local testing)

## Project Structure

```
├── src/
│   ├── main/java/com/trading/
│   │   ├── ScanEarningsLambda.java    # Main Lambda function
│   │   └── EarningsRecord.java        # Data model
│   └── test/java/com/trading/
│       └── ScanEarningsLambdaTest.java # Unit tests
├── step-functions/
│   └── earnings-scan.json             # Step Functions definition
├── pom.xml                            # Maven dependencies
├── template.yaml                      # SAM template
├── samconfig.toml                     # SAM configuration
└── README.md                          # This file
```

## Setup

### 1. Clone and Build

```bash
git clone <repository-url>
cd TradingAWS
mvn clean package
```

### 2. AWS Secrets Manager Setup

Create the following secrets in AWS Secrets Manager:

#### Finnhub API Key
```json
{
  "key": "your-finnhub-api-key"
}
```


### 3. Environment Variables

The Lambda function uses the following environment variables (set in `template.yaml`):

- `FINNHUB_API_URL`: https://finnhub.io/api/v1/calendar/earnings
- `FINNHUB_SECRET_NAME`: finnhub-api-key
- `DYNAMODB_TABLE`: DynamoDB table name (auto-generated)

## Local Testing

### 1. Unit Tests

```bash
mvn test
```

### 2. Local Lambda Testing with SAM

#### Prerequisites
- Install AWS SAM CLI
- Install Docker

#### Build and Test Locally

```bash
# Build the project
mvn clean package

# Build SAM application
sam build

# Test locally with mock event
sam local invoke ScanEarningsLambda --event events/test-event.json

# Test with environment variables
sam local invoke ScanEarningsLambda \
  --event events/test-event.json \
  --env-vars events/env-vars.json
```

#### Create Test Event Files

Create `events/test-event.json`:
```json
{}
```

Create `events/env-vars.json`:
```json
{
  "ScanEarningsLambda": {
    "FINNHUB_API_URL": "https://finnhub.io/api/v1/calendar/earnings",
    "ALPACA_API_URL": "https://paper-api.alpaca.markets",
    "FINNHUB_SECRET_NAME": "finnhub-api-key",
    "ALPACA_SECRET_NAME": "alpaca-api-keys",
    "DYNAMODB_TABLE": "EarningsTable"
  }
}
```

### 3. DynamoDB Local Testing

#### Start DynamoDB Local

```bash
# Using Docker
docker run -p 8000:8000 amazon/dynamodb-local

# Or using SAM
sam local start-dynamodb
```

#### Create Table

```bash
aws dynamodb create-table \
  --table-name EarningsTable \
  --attribute-definitions \
    AttributeName=scanDate,AttributeType=S \
    AttributeName=ticker,AttributeType=S \
  --key-schema \
    AttributeName=scanDate,KeyType=HASH \
    AttributeName=ticker,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url http://localhost:8000
```

#### Test with Local DynamoDB

```bash
sam local invoke ScanEarningsLambda \
  --event events/test-event.json \
  --env-vars events/env-vars.json \
  --parameter-overrides DynamoDbEndpoint=http://localhost:8000
```

## Deployment

### 1. Deploy to AWS

```bash
# Deploy to dev environment
sam deploy --config-env dev

# Deploy to staging
sam deploy --config-env staging

# Deploy to production
sam deploy --config-env prod
```

### 2. Verify Deployment

```bash
# Check stack status
aws cloudformation describe-stacks --stack-name trading-earnings-lambda-dev

# Test the function
aws lambda invoke \
  --function-name dev-ScanEarningsLambda \
  --payload '{}' \
  response.json

cat response.json
```

## Configuration

### DynamoDB Table Schema

| Attribute | Type | Description |
|-----------|------|-------------|
| scanDate  | String (PK) | Date when the scan was performed (YYYY-MM-DD) |
| ticker    | String (SK) | Stock ticker symbol |
| earningsDate | String | Date of the earnings announcement |
| time      | String | "AMC" or "BMO" |

**Note**: Tables are created dynamically and cleaned up at 4:00 PM EST (normal days) or 1:00 PM EST (early closure days). No TTL is used - tables are deleted entirely.

### EventBridge Schedule

The function is scheduled to run at 4:15 PM EST on weekdays:
- Cron expression: `cron(15 21 ? * MON-FRI *)`
- Timezone: America/New_York

### 2025 US Holidays

The function skips the following holidays:
- New Year's Day (Jan 1)
- Martin Luther King Jr. Day (Jan 20)
- Presidents' Day (Feb 17)
- Good Friday (Apr 18)
- Memorial Day (May 26)
- Juneteenth (Jun 19)
- Independence Day (Jul 4)
- Labor Day (Sep 1)
- Thanksgiving Day (Nov 27)
- Christmas Day (Dec 25)

## Monitoring

### CloudWatch Logs

Logs are available in CloudWatch under:
```
/aws/lambda/{environment}-ScanEarningsLambda
```

### Key Metrics

- Duration: Should be 5-10 seconds
- Memory usage: 256 MB
- Error rate: Monitor for API failures
- DynamoDB write capacity: Monitor for throttling

### Alarms

Consider setting up CloudWatch alarms for:
- Function errors
- Duration exceeding 20 seconds
- DynamoDB throttling
- API rate limit errors

## Troubleshooting

### Common Issues

1. **API Key Errors**
   - Verify secrets are correctly stored in Secrets Manager
   - Check IAM permissions for `secretsmanager:GetSecretValue`

2. **DynamoDB Errors**
   - Verify table exists and has correct schema
   - Check IAM permissions for DynamoDB operations
   - Monitor for throttling

3. **Market Status Issues**
   - Check system time and timezone settings
   - Verify holiday list is up to date
   - Ensure Lambda is running in correct timezone

4. **Rate Limiting**
   - Finnhub: 60 calls/minute
   - Consider implementing retry logic with exponential backoff

### Debug Mode

Enable debug logging by setting the log level to DEBUG in CloudWatch:

```bash
aws logs put-retention-policy \
  --log-group-name /aws/lambda/dev-ScanEarningsLambda \
  --retention-in-days 14
```

## Performance Optimization

- **Batch Writes**: Uses DynamoDB batch writes (25 items per batch)
- **Connection Pooling**: Reuses HTTP connections
- **Memory Management**: Optimized for 256 MB memory limit
- **Error Handling**: Graceful degradation on API failures

## Security

- **API Keys**: Stored in AWS Secrets Manager
- **IAM Roles**: Least privilege access
- **VPC**: Can be deployed in VPC for additional security
- **Encryption**: DynamoDB encryption at rest enabled

## Cost Optimization

- **DynamoDB**: Pay-per-request billing mode
- **Lambda**: 256 MB memory, 30-second timeout
- **CloudWatch**: 14-day log retention
- **API Calls**: Minimal API usage (1 Finnhub call, 1 Alpaca call per execution)

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For issues and questions:
1. Check the troubleshooting section
2. Review CloudWatch logs
3. Create an issue in the repository
4. Contact the development team

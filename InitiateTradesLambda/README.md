# InitiateTradesLambda

A Java-based AWS Lambda function for initiating calendar spread trades in a trading application. The function reads filtered tickers from DynamoDB, fetches option contracts and prices from Alpaca, calculates optimal trade quantities, and submits orders to Alpaca's paper trading environment.

## Features

- **Calendar Spread Trading**: Automatically selects near-term and far-term call options for calendar spreads
- **Risk Management**: Calculates position sizes based on 2% of account value
- **Market Hours Check**: Only executes trades when market is open
- **Paper Trading**: Uses Alpaca's paper trading environment for safe testing
- **DynamoDB Integration**: Reads from FilteredTickersTable and writes to OrdersTable
- **Error Handling**: Robust error handling with detailed logging

## Architecture

```
Step Functions (3:45 PM EST) 
    ↓
InitiateTradesLambda
    ↓
DynamoDB FilteredTickersTable (Read)
    ↓
Alpaca APIs (Options, Quotes, Account, Orders)
    ↓
DynamoDB OrdersTable (Write)
```

## Prerequisites

- Java 21 (required for AWS Lambda runtime)
- Maven 3.6+
- AWS CLI configured
- SAM CLI installed
- Alpaca paper trading account
- DynamoDB Local (for local testing)

**Note**: If you're using Java 23+ for development, the unit tests may fail due to Mockito compatibility issues. Use Java 21 for testing or skip tests with `mvn package -DskipTests`.

## Setup

### 1. Clone and Build

```bash
git clone <repository-url>
cd InitiateTradesLambda
mvn clean package
```

### 2. Configure Environment Variables

Create a `.env` file or set environment variables:

```bash
export FILTERED_TABLE="FilteredTickersTable"
export ORDERS_TABLE="OrdersTable"
export ALPACA_SECRET_NAME="trading/alpaca/credentials"
export ALPACA_API_URL="https://paper-api.alpaca.markets/v2"
```

### 3. Set up Alpaca API Keys

Store your Alpaca API keys in AWS Secrets Manager:

```bash
aws secretsmanager create-secret \
    --name "alpaca-api-keys" \
    --description "Alpaca API keys for trading" \
    --secret-string '{"apiKey":"your-api-key","secretKey":"your-secret-key"}'
```

### 4. DynamoDB Tables

**Note**: DynamoDB tables are created and managed automatically by MarketSchedulerLambda:
- Tables created at 3:25 PM EST (normal days) / 12:25 PM EST (early closure days)
- Tables cleaned up at 4:00 PM EST (normal days) / 1:00 PM EST (early closure days)
- No manual table creation required

```bash
# Tables are created automatically, but for reference:
# Create FilteredTickersTable
aws dynamodb create-table \
    --table-name FilteredTickersTable \
    --attribute-definitions \
        AttributeName=scanDate,AttributeType=S \
        AttributeName=ticker,AttributeType=S \
    --key-schema \
        AttributeName=scanDate,KeyType=HASH \
        AttributeName=ticker,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST

# Create OrdersTable
aws dynamodb create-table \
    --table-name OrdersTable \
    --attribute-definitions \
        AttributeName=ticker,AttributeType=S \
        AttributeName=orderId,AttributeType=S \
    --key-schema \
        AttributeName=ticker,KeyType=HASH \
        AttributeName=orderId,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST
```

## Local Testing

### 1. Unit Tests

Run the unit tests:

```bash
mvn test
```

### 2. Integration Testing with DynamoDB Local

Start DynamoDB Local:

```bash
# Install DynamoDB Local
npm install -g dynamodb-local

# Start DynamoDB Local
dynamodb-local -port 8000
```

Create test data in DynamoDB Local:

```bash
# Add test tickers
aws dynamodb put-item \
    --endpoint-url http://localhost:8000 \
    --table-name FilteredTickersTable \
    --item '{
        "scanDate": {"S": "2025-09-26"},
        "ticker": {"S": "AAPL"},
        "status": {"S": "Recommended"},
        "recommendationScore": {"N": "85"}
    }'

aws dynamodb put-item \
    --endpoint-url http://localhost:8000 \
    --table-name FilteredTickersTable \
    --item '{
        "scanDate": {"S": "2025-09-26"},
        "ticker": {"S": "GOOGL"},
        "status": {"S": "Consider"},
        "recommendationScore": {"N": "75"}
    }'
```

### 3. SAM Local Testing

Create a test event file `test-event.json`:

```json
{
    "scanDate": "2025-09-26"
}
```

Test locally with SAM:

```bash
# Build the function
sam build

# Test locally
sam local invoke InitiateTradesLambda --event test-event.json --env-vars env.json
```

Create `env.json` for local testing:

```json
{
    "InitiateTradesLambda": {
        "FILTERED_TABLE": "FilteredTickersTable",
        "ORDERS_TABLE": "OrdersTable",
        "ALPACA_SECRET_NAME": "alpaca-api-keys",
        "ALPACA_API_URL": "https://paper-api.alpaca.markets/v2"
    }
}
```

## Deployment

### 1. Deploy with SAM

```bash
# Build and deploy
sam build
sam deploy --guided
```

### 2. Manual Deployment

Package and deploy manually:

```bash
# Package the function
mvn clean package

# Upload to S3
aws s3 cp target/initiate-trades-lambda-1.0.0.jar s3://your-bucket/lambda-functions/

# Create Lambda function
aws lambda create-function \
    --function-name InitiateTradesLambda \
    --runtime java21 \
    --role arn:aws:iam::your-account:role/lambda-execution-role \
    --handler com.example.InitiateTradesLambda::handleRequest \
    --code S3Bucket=your-bucket,S3Key=lambda-functions/initiate-trades-lambda-1.0.0.jar \
    --timeout 300 \
    --memory-size 512
```

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `FILTERED_TABLE` | DynamoDB table for filtered tickers | `FilteredTickersTable` |
| `ORDERS_TABLE` | DynamoDB table for order details | `OrdersTable` |
| `ALPACA_SECRET_NAME` | Secrets Manager secret name | `trading/alpaca/credentials` |
| `ALPACA_API_URL` | Alpaca API base URL | `https://paper-api.alpaca.markets/v2` |

### IAM Permissions

The Lambda function requires the following permissions:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "dynamodb:Query",
                "dynamodb:Scan"
            ],
            "Resource": "arn:aws:dynamodb:*:*:table/FilteredTickersTable"
        },
        {
            "Effect": "Allow",
            "Action": [
                "dynamodb:PutItem",
                "dynamodb:UpdateItem"
            ],
            "Resource": "arn:aws:dynamodb:*:*:table/OrdersTable"
        },
        {
            "Effect": "Allow",
            "Action": [
                "secretsmanager:GetSecretValue"
            ],
            "Resource": "arn:aws:secretsmanager:*:*:secret:alpaca-api-keys*"
        },
        {
            "Effect": "Allow",
            "Action": [
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents"
            ],
            "Resource": "*"
        }
    ]
}
```

## Usage

### Manual Invocation

```bash
aws lambda invoke \
    --function-name InitiateTradesLambda \
    --payload '{"scanDate": "2025-09-26"}' \
    response.json
```

### Scheduled Execution

The function is configured to run at 3:45 PM EST via EventBridge:

```yaml
ScheduleExpression: cron(45 20 * * ? *)  # 3:45 PM EST (8:45 PM UTC)
```

## Monitoring

### CloudWatch Logs

Monitor execution logs in CloudWatch:

```bash
aws logs describe-log-groups --log-group-name-prefix /aws/lambda/InitiateTradesLambda
```

### Metrics

Key metrics to monitor:
- Duration
- Errors
- Throttles
- Concurrent executions

## Troubleshooting

### Common Issues

1. **Market Closed**: Function skips execution when market is closed
2. **No Options Available**: Function skips tickers with no suitable options
3. **API Rate Limits**: Function respects Alpaca's 200 req/min limit
4. **Insufficient Quantity**: Function skips trades with quantity < 1

### Debug Mode

Enable debug logging by setting log level to DEBUG in CloudWatch.

### Testing with Mock Data

For testing without live market data, modify the function to use mock responses:

```java
// In selectOptionContracts method
if (System.getenv("MOCK_MODE").equals("true")) {
    return createMockOptionContracts(ticker);
}
```

## Development

### Project Structure

```
src/
├── main/java/com/example/
│   └── InitiateTradesLambda.java
└── test/java/com/example/
    └── InitiateTradesLambdaTest.java
```

### Key Methods

- `handleRequest()`: Main entry point
- `fetchFilteredTickers()`: Reads from DynamoDB
- `selectOptionContracts()`: Finds suitable options
- `calculateDebit()`: Calculates spread cost
- `submitOrder()`: Submits to Alpaca
- `writeToOrdersTable()`: Writes to DynamoDB

### Adding New Features

1. Add new methods to the main class
2. Write unit tests
3. Update documentation
4. Test locally
5. Deploy and verify

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## Support

For issues and questions:
- Create an issue in the repository
- Check CloudWatch logs for execution details
- Review Alpaca API documentation for trading specifics

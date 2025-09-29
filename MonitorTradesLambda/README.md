# Monitor Trades Lambda

A Java-based AWS Lambda function for monitoring active trading orders with time-based logic. The function monitors entry and exit orders every 30 seconds for 15 minutes, implementing different strategies based on elapsed time since order submission.

## Features

- **Time-based Monitoring**: Different actions based on elapsed time (0-10 min, 10-13 min, 13+ min)
- **Market Spread Price Calculation**: Calculates current spread prices using Alpaca options quotes
- **Dynamic Order Updates**: Updates limit prices when spread changes by more than 0.05%
- **Order Management**: Cancels entry orders after 10 minutes, converts exit orders to market orders after 13 minutes
- **Market Hours Validation**: Only operates during market hours using Alpaca's clock API
- **Error Handling**: Robust error handling with detailed logging

## Architecture

- **Runtime**: Java 21
- **AWS Services**: Lambda, DynamoDB (lightweight tracking), Secrets Manager
- **External APIs**: Alpaca Paper Trading API (primary data source)
- **Deployment**: AWS SAM (Serverless Application Model)
- **Data Flow**: 
  - DynamoDB stores only order IDs and monitoring periods
  - Real-time order status fetched directly from Alpaca API
  - Single source of truth for order data

## Project Structure

```
MonitorTradesLambda/
├── src/
│   ├── main/java/com/example/
│   │   └── MonitorTradesLambda.java
│   └── test/java/com/example/
│       ├── MonitorTradesLambdaTest.java
│       └── MonitorTradesLambdaIntegrationTest.java
├── pom.xml
├── template.yaml
└── README.md
```

## Dependencies

- AWS SDK for Java v2 (DynamoDB, Secrets Manager)
- Apache HttpClient for HTTP requests
- Jackson for JSON processing
- JUnit 5 for testing
- Mockito for mocking

## Configuration

### Environment Variables

- `ORDERS_TABLE`: DynamoDB table name for storing orders
- `ALPACA_SECRET_NAME`: AWS Secrets Manager secret name containing Alpaca API credentials
- `ALPACA_API_URL`: Alpaca API base URL (https://paper-api.alpaca.markets)

### DynamoDB Table Schema

**Table**: `OrdersTable`
- **Partition Key**: `ticker` (String)
- **Sort Key**: `orderId` (String)
- **Attributes**:
  - `tradeType` (String): "entry" or "exit"
  - `submissionTime` (String): ISO 8601 timestamp
  - `limitPrice` (Number): Order limit price
  - `status` (String): Order status
  - `monitoringEndTime` (String): ISO 8601 timestamp when monitoring should end
  - `legs` (List): Order legs with symbol, side, ratio_quantity

### Secrets Manager

Store Alpaca API credentials as JSON:
```json
{
  "apiKey": "your-alpaca-api-key",
  "secretKey": "your-alpaca-secret-key"
}
```

## Time-based Logic

### First 10 Minutes
- Check order status
- Calculate current spread price
- Update limit price if spread changed by more than 0.05%

### After 10 Minutes
- **Entry Orders**: Cancel if still open
- **Exit Orders**: Update limit to 3% below current market price

### After 13 Minutes
- **Exit Orders**: Convert to market orders (cancel and resubmit as market)

## Market Spread Price Calculation

### Entry Trades (Debit Spreads)
```
debit = far_ask - near_bid
```

### Exit Trades (Credit Spreads)
```
credit = far_bid - near_ask
```

Prices are rounded to 2 decimal places.

## Local Development

### Prerequisites

- Java 21
- Maven 3.6+
- AWS CLI configured
- SAM CLI installed
- Docker (for local testing)

### Building

```bash
mvn clean package
```

### Running Tests

```bash
mvn test
```

### Local Testing with SAM

1. **Start DynamoDB Local**:
```bash
sam local start-dynamodb
```

2. **Build and invoke locally**:
```bash
sam build
sam local invoke MonitorTradesFunction --event events/test-event.json
```

3. **Start API Gateway locally**:
```bash
sam local start-api
```

## Deployment

### Using SAM

1. **Build the application**:
```bash
sam build
```

2. **Deploy to AWS**:
```bash
sam deploy --guided
```

3. **Update Alpaca credentials**:
```bash
aws secretsmanager update-secret \
  --secret-id alpaca-api-keys \
  --secret-string '{"apiKey":"your-key","secretKey":"your-secret"}'
```

### Manual Deployment

1. Package the JAR:
```bash
mvn clean package
```

2. Upload to Lambda:
```bash
aws lambda update-function-code \
  --function-name MonitorTradesFunction \
  --zip-file fileb://target/monitor-trades-lambda-1.0.0-shaded.jar
```

## Testing

### Unit Tests

Run all unit tests:
```bash
mvn test
```

### Integration Tests

Run integration tests with mocked services:
```bash
mvn test -Dtest=MonitorTradesLambdaIntegrationTest
```

### Manual Testing

1. **Create test orders in DynamoDB**:
```bash
aws dynamodb put-item \
  --table-name OrdersTable \
  --item file://test-data/sample-order.json
```

2. **Invoke Lambda function**:
```bash
aws lambda invoke \
  --function-name MonitorTradesFunction \
  --payload '{}' \
  response.json
```

### Mock Data

See `test-data/` directory for sample test data files.

## Monitoring and Logging

- **CloudWatch Logs**: All function execution logs
- **DynamoDB**: Order status updates
- **Metrics**: Custom metrics for monitoring success/failure rates

## Error Handling

The function includes comprehensive error handling:
- Network failures to Alpaca API
- DynamoDB operation failures
- Invalid order data
- Market closed scenarios
- Rate limiting

## Performance

- **Memory**: 256 MB
- **Timeout**: 30 seconds
- **Expected Duration**: 2-5 seconds per invocation
- **Concurrency**: Sequential processing of orders

## Security

- IAM roles with minimal required permissions
- Secrets stored in AWS Secrets Manager
- No hardcoded credentials
- VPC configuration (if needed)

## Troubleshooting

### Common Issues

1. **Market Closed**: Function skips execution when market is closed
2. **Invalid Credentials**: Check Secrets Manager configuration
3. **DynamoDB Permissions**: Verify IAM role has required permissions
4. **Network Timeouts**: Check VPC configuration and security groups

### Debugging

Enable detailed logging by setting log level to DEBUG in CloudWatch.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

This project is licensed under the MIT License.

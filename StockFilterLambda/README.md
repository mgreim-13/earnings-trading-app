# Stock Filter Lambda

A Java-based AWS Lambda function for filtering stocks based on volume, volatility, and options data. The function retrieves tickers from the DynamoDB `EarningsTable`, applies sophisticated filters, and writes results to the `FilteredTickersTable`.

## Features

- **Volume Filtering**: Filters stocks based on average daily volume threshold
- **Volatility Analysis**: Calculates IV30/RV30 ratio using historical volatility
- **Term Structure Analysis**: Evaluates options term structure slope
- **Parallel Processing**: Processes multiple tickers concurrently with rate limiting
- **Error Handling**: Graceful error handling for API failures and data issues

## Architecture

```
Step Functions → StockFilterLambda → DynamoDB (EarningsTable)
                      ↓
                 Yahoo Finance API
                      ↓
                 DynamoDB (FilteredTickersTable)
```

## Prerequisites

- Java 21
- Maven 3.6+
- AWS CLI configured
- AWS SAM CLI
- Docker (for local testing)

## Dependencies

- AWS SDK for Java v2
- Yahoo Finance API
- Jackson for JSON processing
- JUnit 5 for testing

## Local Development Setup

### 1. Clone and Build

```bash
git clone <repository-url>
cd StockFilterLambda
mvn clean package
```

### 2. Install AWS SAM CLI

```bash
# macOS
brew install aws-sam-cli

# Or download from: https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html
```

### 3. Install DynamoDB Local

```bash
# Using Docker
docker run -p 8000:8000 amazon/dynamodb-local

# Or download JAR from AWS
wget https://s3.us-west-2.amazonaws.com/dynamodb-local/dynamodb_local_latest.tar.gz
tar -xzf dynamodb_local_latest.tar.gz
java -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar -sharedDb -inMemory
```

## Local Testing

### 1. Unit Tests

```bash
mvn test
```

### 2. Integration Testing with SAM Local

#### Create Local DynamoDB Tables

```bash
# Create earnings table
aws dynamodb create-table \
    --table-name dev-EarningsTable \
    --attribute-definitions \
        AttributeName=scanDate,AttributeType=S \
        AttributeName=ticker,AttributeType=S \
    --key-schema \
        AttributeName=scanDate,KeyType=HASH \
        AttributeName=ticker,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    --endpoint-url http://localhost:8000

# Create filtered tickers table
aws dynamodb create-table \
    --table-name filtered-tickers-table \
    --attribute-definitions \
        AttributeName=scanDate,AttributeType=S \
        AttributeName=ticker,AttributeType=S \
    --key-schema \
        AttributeName=scanDate,KeyType=HASH \
        AttributeName=ticker,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    --endpoint-url http://localhost:8000
```

#### Add Sample Data

```bash
# Add sample earnings data
aws dynamodb put-item \
    --table-name dev-EarningsTable \
    --item '{"scanDate": {"S": "2024-01-15"}, "ticker": {"S": "AAPL"}}' \
    --endpoint-url http://localhost:8000

aws dynamodb put-item \
    --table-name dev-EarningsTable \
    --item '{"scanDate": {"S": "2024-01-15"}, "ticker": {"S": "MSFT"}}' \
    --endpoint-url http://localhost:8000
```

#### Test with SAM Local

```bash
# Build the function
sam build

# Test locally
sam local invoke StockFilterLambdaFunction --event events/test-event.json
```

### 3. Test Event

Create `events/test-event.json`:

```json
{
  "scanDate": "2024-01-15"
}
```

## Deployment

### 1. Deploy to AWS

```bash
# Build and deploy
sam build
sam deploy --guided

# Or deploy with specific parameters
sam deploy --parameter-overrides \
    Environment=dev \
    VolumeThreshold=1000000 \
    RatioThreshold=1.2 \
    SlopeThreshold=0.0
```

### 2. Environment Variables

The function uses the following environment variables:

- `EARNINGS_TABLE`: Name of the earnings DynamoDB table
- `FILTERED_TABLE`: Name of the filtered tickers DynamoDB table
- `VOLUME_THRESHOLD`: Minimum average daily volume (default: 2,000,000)
- `RATIO_THRESHOLD`: Minimum IV30/RV30 ratio (default: 1.2)
- `SLOPE_THRESHOLD`: Minimum term structure slope (default: 0.0)

## Configuration

### Filter Thresholds

You can customize the filtering criteria by setting environment variables:

```bash
export VOLUME_THRESHOLD=2000000    # 2M shares minimum (improved liquidity)
export RATIO_THRESHOLD=1.5         # Higher IV/RV ratio
export SLOPE_THRESHOLD=0.05        # Positive slope requirement
```

### Rate Limiting

The function includes built-in rate limiting for Yahoo Finance API calls:

- Maximum 10 concurrent requests
- Sequential processing to avoid throttling
- Error handling for API failures

## Monitoring and Logging

### CloudWatch Logs

The function logs key information to CloudWatch:

- Processing start/completion
- Ticker processing results
- Filter evaluation results
- Error messages

### Metrics

Monitor these CloudWatch metrics:

- `Duration`: Function execution time
- `Errors`: Number of errors
- `Throttles`: Number of throttles
- `Invocations`: Number of invocations

## Performance Considerations

### Memory and Timeout

- **Memory**: 512 MB (configurable)
- **Timeout**: 60 seconds (configurable)
- **Expected Processing Time**: 5-15 seconds for 100-500 tickers

### Optimization Tips

1. **Parallel Processing**: Uses ExecutorService for concurrent ticker processing
2. **Rate Limiting**: Respects Yahoo Finance API limits
3. **Error Handling**: Skips failed tickers to continue processing
4. **Caching**: Consider implementing caching for frequently accessed data

## Error Handling

The function handles various error scenarios:

- **Yahoo Finance API Failures**: Logs error and skips ticker
- **DynamoDB Errors**: Logs error and continues processing
- **Data Validation**: Validates data before processing
- **Network Issues**: Implements retry logic where appropriate

## Testing Strategy

### Unit Tests

- Test individual methods in isolation
- Mock external dependencies
- Verify calculation accuracy

### Integration Tests

- Test with real DynamoDB Local
- Test with sample Yahoo Finance data
- Verify end-to-end functionality

### Load Tests

- Test with large datasets (500+ tickers)
- Monitor memory usage and performance
- Verify timeout handling

## Troubleshooting

### Common Issues

1. **Yahoo Finance API Errors**
   - Check rate limiting
   - Verify ticker symbols
   - Handle API changes

2. **DynamoDB Errors**
   - Check IAM permissions
   - Verify table names
   - Check region configuration

3. **Memory Issues**
   - Increase Lambda memory
   - Optimize data structures
   - Implement pagination

### Debug Mode

Enable debug logging by setting log level to DEBUG in CloudWatch.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure all tests pass
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For issues and questions:

1. Check the troubleshooting section
2. Review CloudWatch logs
3. Create an issue in the repository
4. Contact the development team

## Changelog

### Version 1.0.0
- Initial release
- Basic filtering functionality
- Yahoo Finance integration
- DynamoDB integration
- Unit tests
- SAM deployment template


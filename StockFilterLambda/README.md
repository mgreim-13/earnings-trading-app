# Stock Filter Lambda

A sophisticated Java-based AWS Lambda function for filtering stocks using a comprehensive gatekeeper system. The function retrieves tickers from the DynamoDB `earnings-table`, applies multiple mandatory and optional filters, and writes results to the `filtered-tickers-table` with proportional portfolio allocation.

## Features

### Core Filtering System
- **Gatekeeper Architecture**: Mandatory filters that all stocks must pass
- **Optional Filters**: Additional filters that provide position sizing bonuses
- **Centralized Configuration**: All thresholds managed in `FilterThresholds.java`
- **Proportional Portfolio Allocation**: Ensures no more than 30% portfolio allocation per day

### Mandatory Filters (Gatekeepers)
- **Liquidity Filter**: Volume, bid-ask spreads, quote depth, and option trading activity
- **IV Ratio Filter**: Implied volatility ratio for earnings plays
- **Term Structure Filter**: Options term structure backwardation
- **Execution Spread Filter**: Calendar spread execution feasibility

### Optional Filters (Position Bonuses)
- **Earnings Stability Filter**: Historical earnings move consistency (+1% position bonus)
- **Volatility Crush Filter**: Historical volatility crush patterns (+1% position bonus)

### Technical Features
- **Parallel Processing**: Processes multiple tickers concurrently with rate limiting
- **Caching System**: Efficient data caching to reduce API calls
- **Error Handling**: Graceful error handling for API failures and data issues
- **Real-time API Integration**: Alpaca API for live market data

## Architecture

```
Step Functions → StockFilterLambda → DynamoDB (earnings-table)
                      ↓
                 Alpaca API (Live Market Data)
                      ↓
                 CacheManager (Efficient Data Caching)
                      ↓
                 Filter System (Gatekeepers + Optional)
                      ↓
                 Proportional Portfolio Allocation (30% max)
                      ↓
                 DynamoDB (filtered-tickers-table - cleaned up at 4:00 PM EST)
```

## Prerequisites

- Java 21
- Maven 3.6+
- AWS CLI configured
- AWS SAM CLI
- Docker (for local testing)

## Dependencies

- AWS SDK for Java v2
- Alpaca API (Live market data)
- TradingCommonUtils (Custom trading utilities)
- Jackson for JSON processing
- JUnit 5 for testing
- Mockito for test mocking

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
    --table-name earnings-table \
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
    --table-name earnings-table \
    --item '{"scanDate": {"S": "2024-01-15"}, "ticker": {"S": "AAPL"}}' \
    --endpoint-url http://localhost:8000

aws dynamodb put-item \
    --table-name earnings-table \
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
- `ALPACA_API_KEY`: Alpaca API key for live market data
- `ALPACA_SECRET_KEY`: Alpaca secret key for authentication

**Note**: All filter thresholds are now centrally managed in `FilterThresholds.java` and can be modified directly in the code.

## Configuration

### Centralized Filter Thresholds

All filter thresholds are centrally managed in `FilterThresholds.java`. Key thresholds include:

#### Core Thresholds
- **Volume Threshold**: 1,500,000 shares (minimum daily volume)
- **Price Range**: $30 - $400 (stock price range)
- **ATM Threshold**: 2% (At-The-Money option definition)

#### Mandatory Filter Thresholds
- **Liquidity Filter**:
  - Bid-Ask Spread: 5%
  - Quote Depth: 100 contracts
  - Daily Option Trades: 500 minimum
- **IV Ratio Filter**: 1.20 (20% IV skew required)
- **Term Structure Filter**: 5% slope threshold
- **Execution Spread Filter**: 4% max debit/price ratio

#### Optional Filter Thresholds
- **Earnings Stability**: 70% stable earnings required
- **Volatility Crush**: 70% crush frequency required
- **Position Sizing**: 5% base + 1% bonus per optional filter

#### Portfolio Management
- **Max Daily Allocation**: 30% (proportional scaling when exceeded)

### Modifying Thresholds

To change any threshold, edit the corresponding constant in `FilterThresholds.java`:

```java
public class FilterThresholds {
    public static final int VOLUME_THRESHOLD = 1500000;  // Modify this value
    public static final double BID_ASK_THRESHOLD = 0.05;  // Modify this value
    // ... other thresholds
}
```

## Filter System Details

### Gatekeeper System

The filter system uses a **gatekeeper architecture** where stocks must pass all mandatory filters to be recommended:

#### 1. Liquidity Filter (Mandatory)
- **Volume Check**: Minimum 1.5M daily volume
- **Price Range**: $30 - $400 stock price
- **Bid-Ask Spread**: Maximum 5% spread on options
- **Quote Depth**: Minimum 100 contracts combined bid/ask
- **Option Trading**: Minimum 500 daily option trades

#### 2. IV Ratio Filter (Mandatory)
- **IV Skew**: Short-term IV must be 20% higher than long-term IV
- **Earnings Focus**: Designed for earnings plays with IV expansion

#### 3. Term Structure Filter (Mandatory)
- **Backwardation**: Requires 5% IV difference between expirations
- **Earnings Expectation**: Indicates strong earnings expectations

#### 4. Execution Spread Filter (Mandatory)
- **Cost Efficiency**: Calendar spreads must cost <4% of stock price
- **Feasibility**: Ensures trades can be executed profitably

### Optional Filters (Position Bonuses)

#### 1. Earnings Stability Filter (+1% bonus)
- **Historical Consistency**: 70% of past earnings moves <5%
- **Predictability**: Rewards stocks with stable earnings patterns

#### 2. Volatility Crush Filter (+1% bonus)
- **Crush Pattern**: 70% of past earnings show volatility crush
- **Profitability**: Rewards stocks with consistent crush patterns

### Position Sizing

- **Base Position**: 5% of portfolio
- **Optional Bonuses**: +1% per optional filter passed
- **Maximum Position**: 7% (5% base + 2% bonuses)
- **Portfolio Limit**: 30% maximum daily allocation
- **Proportional Scaling**: When total exceeds 30%, all positions scaled proportionally

### Example Scenarios

**Scenario 1**: Stock passes all mandatory filters + both optional filters
- Position Size: 7% (5% base + 2% bonuses)
- Total Portfolio: 7%

**Scenario 2**: 5 stocks each get 7% allocation
- Total Portfolio: 35% (exceeds 30% limit)
- Scaled Positions: 6% each (30% ÷ 5 stocks)
- All stocks still invested, just scaled down

**Scenario 3**: Stock passes mandatory filters only
- Position Size: 5% (base only)
- Total Portfolio: 5%

### Rate Limiting

The function includes built-in rate limiting for Alpaca API calls:

- Maximum 10 concurrent requests
- Sequential processing to avoid throttling
- Error handling for API failures
- Caching system to reduce redundant API calls

## Monitoring and Logging

### CloudWatch Logs

The function logs key information to CloudWatch:

- Processing start/completion
- Ticker processing results
- Individual filter evaluation results (pass/fail)
- Position sizing calculations
- Proportional scaling decisions
- Cache hit/miss statistics
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
2. **Rate Limiting**: Respects Alpaca API limits
3. **Error Handling**: Skips failed tickers to continue processing
4. **Caching**: Implements comprehensive caching for stock data, options data, and historical data
5. **Proportional Scaling**: Automatically scales positions to maintain portfolio limits
6. **Filter Efficiency**: Early termination on mandatory filter failures

## Error Handling

The function handles various error scenarios:

- **Alpaca API Failures**: Logs error and skips ticker
- **DynamoDB Errors**: Logs error and continues processing
- **Data Validation**: Validates data before processing
- **Network Issues**: Implements retry logic where appropriate
- **Filter Failures**: Graceful handling of individual filter failures
- **Cache Errors**: Continues processing even if caching fails

## Testing Strategy

### Unit Tests

- Test individual methods in isolation
- Mock external dependencies
- Verify calculation accuracy

### Integration Tests

- Test with real DynamoDB Local
- Test with live Alpaca API data
- Verify end-to-end functionality
- Test individual filter components
- Test proportional scaling logic
- Test cache functionality

### Load Tests

- Test with large datasets (500+ tickers)
- Monitor memory usage and performance
- Verify timeout handling

## Troubleshooting

### Common Issues

1. **Alpaca API Errors**
   - Check API credentials
   - Verify rate limiting
   - Handle API changes

2. **DynamoDB Errors**
   - Check IAM permissions
   - Verify table names
   - Check region configuration

3. **Memory Issues**
   - Increase Lambda memory
   - Optimize data structures
   - Implement pagination

4. **Filter Failures**
   - Check threshold values in `FilterThresholds.java`
   - Verify market data availability
   - Review filter logic

5. **Cache Issues**
   - Check cache configuration
   - Monitor cache hit/miss ratios
   - Verify cache cleanup

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

### Version 2.0.0 (Current)
- **Gatekeeper Architecture**: Implemented mandatory and optional filter system
- **Centralized Configuration**: All thresholds managed in `FilterThresholds.java`
- **Proportional Portfolio Allocation**: 30% maximum daily allocation with scaling
- **Alpaca API Integration**: Real-time market data from Alpaca
- **Comprehensive Caching**: Efficient data caching system
- **Enhanced Filtering**: Liquidity, IV Ratio, Term Structure, Execution Spread filters
- **Position Sizing**: Base 5% + 1% bonus per optional filter
- **Comprehensive Testing**: Unit and integration tests for all components

### Version 1.0.0
- Initial release
- Basic filtering functionality
- Yahoo Finance integration
- DynamoDB integration
- Unit tests
- SAM deployment template


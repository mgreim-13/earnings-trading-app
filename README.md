# TradingAWS - Automated Earnings Trading System

A sophisticated AWS-based automated trading system that scans for earnings opportunities and executes debit calendar spread strategies using advanced financial filters and real-time market data.

## 🚀 Overview

This system automates the entire earnings trading workflow from scanning potential stocks to executing and monitoring trades. It uses a sophisticated **6-factor financial filtering system** to identify high-probability earnings trading opportunities and executes debit calendar spread strategies with built-in risk management.

**Key Features:**
- **Advanced Financial Filtering**: 6-factor gatekeeper system for stock selection
- **Real-time Market Data**: Integration with Alpaca Markets and Finnhub APIs
- **Automated Execution**: Debit calendar spread strategy with position sizing
- **Risk Management**: Built-in position limits and monitoring
- **Market Adaptation**: Automatic handling of early closure days and holidays

## 🏗️ Architecture

### **AWS Services Used**
- **AWS Lambda**: Serverless compute for all trading functions
- **EventBridge**: Cron-based scheduling for automated execution
- **DynamoDB**: Temporary data storage (cleaned up at 4:00 PM EST)
- **CloudWatch**: Logging and monitoring
- **AWS Secrets Manager**: Secure API key storage
- **SNS**: Alert notifications

### **Lambda Functions**

| Function | Purpose | Schedule | Memory | Timeout |
|----------|---------|----------|--------|---------|
| **MarketSchedulerLambda** | Market status detection & rule management | 6:00 AM EST (Mon-Fri) | 256MB | 60s |
| **ScanEarningsLambda** | Scans earnings calendar for opportunities | 3:30 PM EST (Mon-Fri) | 512MB | 300s |
| **StockFilterLambda** | Advanced stock filtering with 6 criteria | 3:35 PM EST (Mon-Fri) | 512MB | 300s |
| **InitiateTradesLambda** | Executes debit calendar spreads | 3:45 PM EST (Mon-Fri) | 1024MB | 300s |
| **MonitorTradesLambda** | Monitors open positions | Every 30s during market hours | 512MB | 300s |
| **InitiateExitTradesLambda** | Closes positions at market open | 9:45 AM EST (Mon-Fri) | 1024MB | 300s |

## 📊 Trading Strategy

### **Debit Calendar Spread Strategy**
The system executes **debit calendar spreads** - a sophisticated options strategy that profits from volatility crush after earnings announcements.

**Strategy Details:**
- **Entry**: Trading session before earnings announcement (3:45 PM EST)
- **Exit**: First trading session after earnings announcement (9:45 AM EST)
- **Position Sizing**: 6% of portfolio equity per trade (configurable)
- **Strike Selection**: At-the-money (ATM) strikes for maximum liquidity
- **Expiration Selection**: 
  - Short leg: First expiration after earnings (typically 1-7 days)
  - Long leg: ~30 days from earnings for time decay benefit

**Why Calendar Spreads?**
- **Volatility Crush**: Profits when IV drops after earnings
- **Time Decay**: Long leg decays slower than short leg
- **Limited Risk**: Maximum loss is the debit paid
- **High Probability**: Benefits from predictable volatility patterns

**Risk Management:**
- **Position Limits**: Maximum 6% of portfolio per trade
- **Diversification**: Multiple positions across different sectors
- **Stop Loss**: Automatic exit at market open next day
- **Monitoring**: Real-time position tracking and adjustment

### **Market Schedule Adaptation**
The system automatically adapts to market conditions using MarketSchedulerLambda:

**Normal Trading Days:**
- **6:00 AM EST**: Market status check and rule configuration
- **9:45 AM EST**: Exit all existing positions
- **9:46-10:00 AM EST**: Monitor trades (every minute)
- **3:25 PM EST**: Create DynamoDB tables
- **3:30 PM EST**: Scan earnings calendar
- **3:35 PM EST**: Filter stocks using 6-factor system
- **3:45 PM EST**: Execute debit calendar spreads
- **3:46-4:00 PM EST**: Monitor new positions (every minute)
- **4:00 PM EST**: Clean up DynamoDB tables

**Early Closure Days:**
- **6:00 AM EST**: Market status check and rule configuration
- **9:45 AM EST**: Exit all existing positions
- **9:46-10:00 AM EST**: Monitor trades (every minute)
- **12:25 PM EST**: Create DynamoDB tables
- **12:30 PM EST**: Scan earnings calendar
- **12:35 PM EST**: Filter stocks using 6-factor system
- **12:45 PM EST**: Execute debit calendar spreads
- **12:46-1:00 PM EST**: Monitor new positions (every minute)
- **1:00 PM EST**: Clean up DynamoDB tables

**Holiday Detection:**
- **Finnhub API**: Real-time market holiday detection
- **Hardcoded Fallback**: Common US market holidays
- **Automatic Skip**: All trading activities disabled on holidays

## 🔍 Financial Filtering System

The StockFilterLambda uses a sophisticated **6-factor gatekeeper system** that evaluates stocks through multiple financial criteria. Each filter acts as a gatekeeper - if any filter fails, the stock is rejected.

### **Gatekeeper 1: Liquidity Filter** 🏊‍♂️
**Purpose**: Ensures sufficient trading volume and options liquidity
- **Average Daily Volume**: Minimum 2M shares (configurable)
- **Stock Price Range**: $20-$1000 for optimal liquidity
- **Options Spread Analysis**: Tight bid-ask spreads required
- **Quote Depth**: Minimum 200 contracts on each side
- **Trade Activity**: Recent options trading activity required

### **Gatekeeper 2: IV Ratio Filter** 📊
**Purpose**: Identifies stocks with elevated implied volatility vs historical volatility
- **IV30/RV30 Ratio**: Must be > 1.20 (20% premium over realized volatility)
- **Data Source**: 30-day implied volatility vs 30-day realized volatility
- **Rationale**: Higher IV suggests market expects significant price movement

### **Gatekeeper 3: Term Structure Filter** 📈
**Purpose**: Detects volatility backwardation (earnings week IV > longer-term IV)
- **Backwardation Detection**: Earnings week IV > max(IV30, IV60)
- **Minimum Slope**: 0.05 (5% backwardation required)
- **Rationale**: Backwardation indicates market expects earnings volatility

### **Gatekeeper 4: Execution Spread Filter** 💰
**Purpose**: Ensures profitable trade execution with reasonable spreads
- **Debit-to-Price Ratio**: Maximum 4% of stock price
- **Dynamic Threshold**: Based on stock price and volatility
- **Net Theta Analysis**: Positive theta for time decay benefit
- **Strike Selection**: ATM strikes for maximum liquidity

### **Optional Filters (Position Sizing)**
These filters don't reject stocks but influence position sizing:

### **Earnings Stability Filter** 🎯
**Purpose**: Historical earnings move analysis
- **Stability Threshold**: Average post-earnings move ≤ 6%
- **Historical Analysis**: Past 8 quarters of earnings data
- **Rationale**: Predictable earnings moves reduce risk

### **Volatility Crush Filter** 💥
**Purpose**: Historical volatility crush analysis
- **Crush Percentage**: 60%+ of past earnings show 20%+ crush
- **Threshold Analysis**: 80% of earnings must meet crush criteria
- **Rationale**: Consistent volatility crush indicates profitable opportunities

### **Filter Configuration**
All filters are configurable via environment variables:

```yaml
Environment:
  Variables:
    # Liquidity Filter
    VOLUME_THRESHOLD: '2000000'           # Minimum daily volume
    MIN_STOCK_PRICE: '30.0'               # Minimum stock price
    MAX_STOCK_PRICE: '400.0'             # Maximum stock price
    BID_ASK_THRESHOLD: '0.08'             # Maximum bid-ask spread
    QUOTE_DEPTH_THRESHOLD: '200'          # Minimum quote depth
    
    # IV Ratio Filter
    IV_RATIO_THRESHOLD: '1.20'            # Minimum IV30/RV30 ratio
    
    # Term Structure Filter
    SLOPE_THRESHOLD: '0.05'              # Minimum backwardation
    
    # Execution Spread Filter
    MAX_DEBIT_TO_PRICE_RATIO: '0.04'      # Maximum debit/price ratio
    
    # Earnings Stability Filter
    EARNINGS_STABILITY_THRESHOLD: '0.06'  # Maximum average move
    STABILITY_THRESHOLD: '0.70'           # Minimum stability score
    
    # Volatility Crush Filter
    VOLATILITY_CRUSH_THRESHOLD: '0.80'    # Minimum crush percentage
    CRUSH_PERCENTAGE: '0.70'              # Minimum historical crush
```

### **Filter Logic Flow**
```
Stock Input
    ↓
Gatekeeper 1: Liquidity Check
    ↓ (PASS)
Gatekeeper 2: IV Ratio Check
    ↓ (PASS)
Gatekeeper 3: Term Structure Check
    ↓ (PASS)
Gatekeeper 4: Execution Spread Check
    ↓ (PASS)
Position Sizing Calculation
    ↓
Final Recommendation
```

## 🛠️ Technical Implementation

### **Data Sources & APIs**

**Alpaca Markets API Integration:**
- **Market Data**: Real-time and historical stock prices, options quotes
- **Options Chain**: Complete options data with strikes, expirations, Greeks
- **Account Data**: Portfolio equity, positions, order history
- **Trading API**: Order submission, modification, and cancellation
- **Rate Limiting**: Built-in retry logic with exponential backoff

**Finnhub API Integration:**
- **Earnings Calendar**: Upcoming earnings announcements and dates
- **Historical Earnings**: Past earnings data for analysis
- **Company Fundamentals**: Financial metrics and ratios
- **Market Holidays**: Real-time market closure detection

### **Key Technical Features**
- **Historical Data Ordering**: Explicit sorting ensures correct chronological analysis
- **Thread Pool Management**: Proper cleanup prevents resource leaks
- **Error Handling**: Comprehensive error reporting and logging
- **Environment Configuration**: All parameters configurable via environment variables
- **Caching System**: Intelligent caching reduces API calls and improves performance
- **Rate Limiting**: Respects API limits with automatic retry mechanisms

### **DynamoDB Tables**
- **earnings-table**: Temporary storage for earnings calendar data (cleaned up at 4:00 PM EST)
- **filtered-tickers-table**: Temporary storage for filtered stock recommendations (cleaned up at 4:00 PM EST)

## 💰 Cost Analysis

**Monthly AWS Costs: ~$1.20-2.00**
- Lambda Functions: $0.50-1.00
- DynamoDB: $0.10-0.25
- EventBridge: $0.10
- Secrets Manager: $0.40
- CloudWatch Logs: $0.10-0.25

**Budget Protection**: AWS Budget alerts configured for $5/month threshold

## 🚀 Deployment

### **Prerequisites**
- AWS CLI configured
- Java 21
- Maven 3.6+
- AWS account with appropriate permissions

### **Build & Deploy**
```bash
# Build all Lambda functions
mvn clean package -DskipTests

# Deploy via CloudFormation
aws cloudformation update-stack \
  --stack-name trading-lambdas-dev \
  --template-body file://cloudformation-template.yaml \
  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM
```

### **Environment Setup**
1. **AWS Secrets Manager**: Store Alpaca and Finnhub API credentials
2. **IAM Roles**: Configure Lambda execution roles with necessary permissions
3. **EventBridge Rules**: Set up cron expressions for scheduling
4. **CloudWatch**: Configure log groups and alarms

## 📈 Monitoring & Alerts

### **CloudWatch Alarms**
- **Duration Monitoring**: Lambda execution time alerts
- **Error Rate Tracking**: Function failure rate monitoring
- **Custom Metrics**: Trading performance and success rates
- **Memory Usage**: Lambda memory consumption alerts

### **Budget Alerts**
- **Email Notifications**: 80% and 100% of $5/month budget
- **Forecasted Spending**: Predictive cost analysis
- **Cost Breakdown**: Per-service cost tracking

### **Comprehensive Logging**
The system provides detailed logging for complete traceability:

**Stock Filter Logging:**
- Individual filter pass/fail results
- Detailed financial metrics (IV ratios, spreads, volumes)
- Filter configuration and thresholds
- Cache hit/miss statistics

**Trading Execution Logging:**
- Order submission success/failure
- Position sizing calculations
- Account equity and risk metrics
- API response times and errors

**Monitoring Logging:**
- Position status updates
- Order conversion to market orders
- Exit strategy execution
- Real-time P&L tracking

**Debug Information:**
- API rate limiting and retries
- Data validation errors
- Network timeouts and failures
- Performance bottlenecks

## 🔒 Security Features

- **AWS Secrets Manager**: Secure storage of API credentials
- **IAM Roles**: Least privilege access for Lambda functions
- **VPC Configuration**: Network isolation where applicable
- **Environment Variables**: Sensitive data not hardcoded

## 🧪 Testing

### **Unit Tests**
- Individual Lambda function testing
- Mock data for external API calls
- Filter logic validation

### **Integration Tests**
- End-to-end workflow testing
- Real API integration validation
- Error handling verification

## 📝 Configuration Files

- **cloudformation-template.yaml**: Main infrastructure definition
- **StockFilterLambda/template.yaml**: Stock filter specific configuration
- **pom.xml**: Maven dependencies for each Lambda function

## 🔄 Development Workflow

1. **Local Development**: Test individual Lambda functions
2. **Build**: Compile Java code with Maven
3. **Deploy**: Update Lambda functions with new JAR files
4. **Monitor**: Check CloudWatch logs and metrics
5. **Iterate**: Refine filters and trading logic

## 📊 Performance Metrics

- **Scan Speed**: Sub-second earnings analysis
- **Execution Time**: Real-time order placement
- **Monitoring Frequency**: Every 30 seconds during market hours
- **Data Processing**: Efficient historical data analysis

## 🛡️ Risk Management

- **Position Sizing**: 6% maximum per trade
- **Market Hours**: Only trades during regular market hours
- **Holiday Detection**: Automatic adaptation to market closures
- **Error Handling**: Graceful failure with comprehensive logging

## 📚 Documentation

- **API Documentation**: Comprehensive JavaDoc for all classes
- **Configuration Guide**: Environment variable documentation
- **Deployment Guide**: Step-by-step AWS setup instructions
- **Troubleshooting**: Common issues and solutions

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Submit a pull request

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## ⚠️ Disclaimer

This software is for educational and research purposes. Trading involves substantial risk and may result in significant financial losses. Always test thoroughly in paper trading mode before using real money.

## 🆘 Support

For issues and questions:
- Check the CloudWatch logs
- Review the configuration settings
- Verify API credentials and permissions
- Create an issue with detailed information

## 🔄 Version History

- **v1.0.0**: Initial AWS Lambda implementation
- **v1.1.0**: Enhanced stock filtering with earnings-specific criteria
- **v1.2.0**: Improved error handling and monitoring
- **v1.3.0**: Added budget protection and cost optimization
- **v1.4.0**: Comprehensive financial filtering system with 6-factor gatekeeper
- **v1.5.0**: Java 21 migration and cron expression fixes
- **v1.6.0**: Enhanced logging and debugging capabilities

---

**Built with ❤️ using AWS Lambda, Java, and modern cloud architecture**

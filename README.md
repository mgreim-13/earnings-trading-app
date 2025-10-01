# TradingAWS - Automated Earnings Trading System

A sophisticated AWS-based automated trading system that scans for earnings opportunities and executes debit calendar spread strategies using Alpaca Markets API and Finnhub data.

## 🚀 Overview

This system automates the entire earnings trading workflow from scanning potential stocks to executing and monitoring trades. It's built on AWS Lambda functions with EventBridge scheduling, DynamoDB for data storage, and CloudWatch for monitoring.

## 🏗️ Architecture

### **AWS Services Used**
- **AWS Lambda**: Serverless compute for all trading functions
- **EventBridge**: Cron-based scheduling for automated execution
- **DynamoDB**: Temporary data storage with TTL
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
- **Entry**: Trading session before earnings announcement
- **Exit**: First trading session after earnings announcement
- **Position Sizing**: 6% of portfolio equity per trade
- **Risk Management**: Built-in position limits and monitoring

### **Market Schedule Adaptation**
The system automatically adapts to market conditions:

**Normal Trading Days:**
- Tables created: 3:25 PM EST
- Earnings scan: 3:30 PM EST
- Stock filtering: 3:35 PM EST
- Trade execution: 3:45 PM EST
- Position monitoring: 3:45:30-4:00 PM EST
- Tables cleaned: 9:00 PM EST

**Early Closure Days:**
- Tables created: 12:25 PM EST
- Earnings scan: 12:30 PM EST
- Stock filtering: 12:35 PM EST
- Trade execution: 12:45 PM EST
- Position monitoring: 12:45:30-1:00 PM EST
- Tables cleaned: 6:00 PM EST

## 🔍 Stock Filtering System

The StockFilterLambda uses a sophisticated **6-factor filtering system**:

### **Core Quality Filters**
1. **Volume Filter**: Minimum 1M average daily volume
2. **IV30/RV30 Ratio**: Implied vs realized volatility comparison (>1.2)
3. **Term Structure Slope**: Volatility curve analysis (backwardation detection)

### **Earnings-Specific Filters**
4. **Historical Volatility Crush**: 70%+ of past earnings show 15%+ volatility crush
5. **Historical Earnings Stability**: Average post-earnings move ≤5%
6. **Options Liquidity**: Multi-layered liquidity assessment

### **Filter Configuration**
All filters are configurable via environment variables:

```yaml
Environment:
  Variables:
    VOLUME_THRESHOLD: '2000000'
    RATIO_THRESHOLD: '1.2'
    SLOPE_THRESHOLD: '-0.00406'
    MIN_AVERAGE_VOLUME: '500000'
    VOLATILITY_CRUSH_THRESHOLD: '0.85'
    EARNINGS_STABILITY_THRESHOLD: '0.05'
    MIN_STOCK_PRICE: '20.0'
    MAX_STOCK_PRICE: '1000.0'
```

## 🛠️ Technical Implementation

### **Data Sources**
- **Alpaca Markets API**: Historical bars, quotes, trades, account data
- **Finnhub API**: Earnings calendar, historical earnings data, company financials

### **Key Features**
- **Historical Data Ordering**: Explicit sorting ensures correct chronological analysis
- **Thread Pool Management**: Proper cleanup prevents resource leaks
- **Error Handling**: Comprehensive error reporting and logging
- **Environment Configuration**: All parameters configurable via environment variables

### **DynamoDB Tables**
- **dev-earnings-data**: Temporary storage for earnings calendar data (30min TTL)
- **dev-filtered-stocks**: Temporary storage for filtered stock recommendations (30min TTL)

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
- Lambda function duration monitoring
- Error rate tracking
- Custom metrics for trading performance

### **Budget Alerts**
- Email notifications at 80% and 100% of $5/month budget
- Forecasted spending alerts

### **Logging**
- Comprehensive logging across all Lambda functions
- Error tracking and debugging information
- Performance metrics

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

---

**Built with ❤️ using AWS Lambda, Java, and modern cloud architecture**

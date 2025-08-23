# Earnings Trading App

A sophisticated automated options trading application that scans for earnings opportunities and executes calendar spread strategies using the Alpaca trading API.

## 🚀 Features

### **Automated Trading**
- **Earnings Scanner**: AI-powered scanning for high-probability earnings trades
- **Calendar Spread Strategy**: Automated execution of options calendar spreads
- **Risk Management**: Built-in position sizing and risk controls
- **Scheduled Execution**: Automated trade entry/exit at optimal times

### **Smart Scanning**
- **Multi-Factor Analysis**: Combines volume, volatility, and technical indicators
- **Real-Time Data**: Integrates with Alpaca, Finnhub, and yfinance for comprehensive market data
- **Customizable Filters**: Adjustable scoring thresholds and filter weights
- **Historical Analysis**: Learns from past earnings performance

### **Professional UI**
- **Real-Time Dashboard**: Live account overview and position monitoring
- **Trade Management**: Easy trade selection and execution control
- **Settings Panel**: Configurable trading parameters and risk limits
- **Responsive Design**: Modern Material-UI interface

## 🏗️ Architecture

### **Backend (Python/FastAPI)**
- **FastAPI**: High-performance web framework
- **APScheduler**: Robust job scheduling for automated trading
- **SQLite**: Local data storage with repository pattern
- **Alpaca API**: Professional trading execution
- **Pytest**: Comprehensive testing suite

### **Frontend (React/Material-UI)**
- **React 18**: Modern component-based architecture
- **Material-UI**: Professional design system
- **Real-time Updates**: Live data streaming
- **Responsive Layout**: Mobile and desktop optimized

## 📊 Trading Strategy

### **Calendar Spread Approach**
- **Entry**: 3:45 PM ET (day before earnings)
- **Exit**: 9:45 AM ET (earnings day)
- **Risk Management**: Position sizing based on account balance
- **Monitoring**: Continuous order monitoring and execution

### **Scanning Criteria**
- **Volume Analysis**: High average volume for liquidity
- **Volatility**: IV30 vs RV30 comparison
- **Technical Indicators**: RSI, beta, short interest


## 🔍 **Stock Filtering System**

The app uses a sophisticated **10-factor scoring algorithm** that evaluates stocks across multiple dimensions to identify high-probability earnings trades.

### **Filter Categories & Weights**

| Filter | Weight | Description | Thresholds |
|--------|--------|-------------|------------|
| **IV30/RV30 Ratio** | 20% | Implied vs Realized Volatility comparison | Large: 1.3+, Mid: 1.4+, Small: 1.5+ |
| **Term Structure Slope** | 17% | Volatility curve steepness (0-45 days) | Large: -0.004 max, Mid: -0.003 max, Small: -0.002 max |
| **Option Liquidity** | 18% | Open interest, volume, bid-ask spreads | Large: OI 1000+, Vol 250+, Spread 10% max |
| **Historical Earnings Volatility** | 18% | Past earnings move magnitude | Large: 8% max, Mid: 12% max, Small: 15% max |
| **Average Volume** | 12% | Stock trading volume requirements | Large: 1M+, Mid: 500K+, Small: 300K+ |
| **IV Percentile** | 10% | Current IV vs historical IV ranking | Large: 60+, Mid: 55+, Small: 50+ |
| **Beta** | 7% | Market correlation measure | Large: 1.3 max, Mid: 1.6 max, Small: 2.0 max |
| **Short Interest** | 4% | Short selling activity | Large: 5% max, Mid: 7% max, Small: 10% max |
| **RSI** | 2% | Relative Strength Index | Large: 35-65, Mid: 30-70, Small: 25-75 |



### **Market Cap Tier System**

The app automatically adjusts thresholds based on market capitalization:

#### **Large Cap (>$10B)**
- **Volume**: 1M+ shares/day
- **IV/RV Ratio**: 1.3+
- **Term Structure**: -0.004 max slope
- **Earnings Volatility**: 8% max move
- **Options**: OI 1000+, Volume 250+, Spread 10% max
- **Risk Profile**: Conservative thresholds for stability

#### **Mid Cap ($2B-$10B)**
- **Volume**: 500K+ shares/day
- **IV/RV Ratio**: 1.4+
- **Term Structure**: -0.003 max slope
- **Earnings Volatility**: 12% max move
- **Options**: OI 750+, Volume 150+, Spread 12% max
- **Risk Profile**: Balanced thresholds for growth potential

#### **Small Cap (<$2B)**
- **Volume**: 200K+ shares/day
- **IV/RV Ratio**: 1.4+
- **Term Structure**: -0.002 max slope
- **Earnings Volatility**: 15% max move
- **Options**: OI 100+, Volume 25+, Spread 25% max
- **Risk Profile**: Aggressive thresholds for higher returns

### **Sector-Specific Adjustments**

The system automatically adjusts thresholds based on sector characteristics:

- **Technology**: Lower IV/RV requirements (1.1+), higher RSI tolerance
- **Healthcare**: Higher volatility thresholds (1.4+), larger earnings moves (18% max)
- **Financial Services**: Moderate thresholds with balanced risk
- **Energy**: Highest volatility requirements (1.5+), largest earnings moves (20% max)
- **Consumer Cyclical**: Standard thresholds with slight adjustments

### **Scoring System**

#### **Individual Filter Scoring**
- **Full Pass (1.0)**: Meets or exceeds threshold
- **Marginal (0.5)**: Within 10% of threshold (configurable)
- **Fail (0.0)**: Below threshold

#### **Final Recommendation Levels**
- **Recommended (80%+)**: High-confidence trades, auto-selected
- **Consider (60-79%)**: Moderate confidence, manual review recommended
- **Avoid (<60%)**: Below threshold, not suitable for trading

#### **Liquidity Override**
- **Minimum Option Liquidity Score**: 0.6 (60%)
- **Automatic Avoid**: Stocks below liquidity threshold
- **Components**: Open interest, volume, bid-ask spreads

### **Dynamic Threshold Computation**

The system uses **adaptive thresholds** that adjust based on:
- **Historical Volatility**: 75th percentile of realized volatility
- **Stock-Specific Data**: Market cap, sector, trading patterns
- **Market Conditions**: Current vs historical volatility levels
- **Risk Tolerance**: Conservative to aggressive based on stock characteristics

### **Filter Validation Process**

1. **Data Quality Check**: Ensures sufficient options data (45+ days future)
2. **Threshold Calculation**: Dynamic computation based on stock characteristics
3. **Multi-Factor Scoring**: Weighted evaluation across all 10 filters
4. **Liquidity Verification**: Confirms options are tradeable
5. **Final Recommendation**: Comprehensive score with detailed breakdown

## 🚀 Quick Start

### **Prerequisites**
- Python 3.8+
- Node.js 16+
- Alpaca trading account
- Finnhub API key (optional)

### **Backend Setup**
```bash
cd backend
python -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
pip install -r requirements.txt
```

### **Frontend Setup**
```bash
cd frontend
npm install
```

### **Environment Configuration**
Create `.env` file in the backend directory:
```env
PAPER_ALPACA_API_KEY=your_paper_api_key
PAPER_ALPACA_SECRET_KEY=your_paper_secret_key
LIVE_ALPACA_API_KEY=your_live_api_key
LIVE_ALPACA_SECRET_KEY=your_live_secret_key
FINNHUB_API_KEY=your_finnhub_key
```

### **Running the Application**
```bash
# Terminal 1: Backend
cd backend
python main.py

# Terminal 2: Frontend
cd frontend
npm start
```

## 🔧 Configuration

### **Trading Parameters**
- **Risk per Trade**: Configurable percentage of account
- **Position Limits**: Maximum contracts per trade
- **Timing Windows**: Entry/exit time tolerances
- **Slippage Protection**: Price deviation limits

### **Scanning Filters**
- **Score Thresholds**: Minimum recommendation scores
- **Filter Weights**: Customizable scoring algorithm
- **Volume Requirements**: Minimum liquidity thresholds
- **Volatility Criteria**: IV percentile and ratio thresholds

## 📈 Usage

### **Daily Workflow**
1. **3:00 PM ET**: Automated earnings scan runs
2. **3:45 PM ET**: Selected trades are executed
3. **9:45 AM ET**: Next day positions are closed
4. **Continuous**: Order monitoring and risk management

### **Manual Operations**
- **Scan Specific Symbols**: Run scans on demand
- **Trade Selection**: Manually select/deselect trades
- **Position Monitoring**: Real-time position tracking
- **Settings Adjustment**: Modify trading parameters

## 🧪 Testing

### **Backend Tests**
```bash
cd backend
python -m pytest tests/ -v
```

### **Frontend Tests**
```bash
cd frontend
npm test
```

### **Test Coverage**
- Unit tests for all core functions
- Integration tests for API endpoints
- Security tests for credential handling
- Trading safety tests

## 🔒 Security Features

- **Credential Protection**: Partial logging of API keys
- **CORS Configuration**: Restricted origin access
- **Environment Validation**: Required variable checking
- **Trading Safety**: Live trading prevention in test mode

## 📊 Performance

- **Scan Speed**: Sub-second earnings analysis
- **Execution**: Real-time order placement
- **Monitoring**: Continuous position tracking
- **Scalability**: Repository pattern for maintainability

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Submit a pull request

## 📝 License

This project is licensed under the MIT License - see the LICENSE file for details.

## ⚠️ Disclaimer

This software is for educational and research purposes. Trading involves substantial risk and may result in significant financial losses. Always test thoroughly in paper trading mode before using real money.

## 🆘 Support

For issues and questions:
- Check the documentation
- Review existing issues
- Create a new issue with detailed information

## 🔄 Version History

- **v1.0.0**: Initial release with core trading functionality
- **v1.1.0**: Enhanced security features and testing
- **v1.2.0**: Improved UI components and loader animations

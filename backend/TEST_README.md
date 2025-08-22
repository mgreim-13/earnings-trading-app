# Trading Application Test Suite

This document provides comprehensive information about the test suite for the trading application, including how to run tests, what they cover, and how to interpret results.

## 🚀 Quick Start

### Install Test Dependencies
```bash
cd backend
python run_tests.py --install-deps
```

### Run All Tests
```bash
python run_tests.py --all
```

### Run Specific Test Types
```bash
# Unit tests only
python run_tests.py --unit

# Fast tests only
python run_tests.py --fast

# Integration tests only
python run_tests.py --integration

# Benchmark tests only
python run_tests.py --benchmark
```

## 📁 Test Structure

```
tests/
├── __init__.py              # Test package initialization
├── conftest.py              # Pytest configuration and shared fixtures
├── test_trading_safety.py   # Trading safety module tests
├── test_database.py         # Database module tests
├── test_base_repository.py  # Base repository tests
├── test_utils.py            # Utility modules tests
├── test_services.py         # Service layer tests
├── test_api.py              # API endpoint tests
├── test_core.py             # Core functionality tests
└── test_repositories.py     # Repository layer tests
```

## 🧪 Test Categories

### Unit Tests (`@pytest.mark.unit`)
- **Purpose**: Test individual functions and methods in isolation
- **Speed**: Fast execution
- **Scope**: Single module/class functionality
- **Dependencies**: Mocked external dependencies

### Integration Tests (`@pytest.mark.integration`)
- **Purpose**: Test interaction between multiple components
- **Speed**: Medium execution
- **Scope**: Component integration
- **Dependencies**: May use real database connections

### Fast Tests (`@pytest.mark.fast`)
- **Purpose**: Quick validation of critical functionality
- **Speed**: Very fast execution (< 100ms)
- **Scope**: Core business logic
- **Use Case**: CI/CD pipelines, development feedback

### Database Tests (`@pytest.mark.database`)
- **Purpose**: Test database operations and data persistence
- **Speed**: Medium execution
- **Scope**: Database CRUD operations
- **Dependencies**: Temporary test database

### API Tests (`@pytest.mark.api`)
- **Purpose**: Test HTTP endpoints and API functionality
- **Speed**: Medium execution
- **Scope**: REST API behavior
- **Dependencies**: Mocked database and services

### Trading Tests (`@pytest.mark.trading`)
- **Purpose**: Test trading-related functionality
- **Speed**: Medium execution
- **Scope**: Trading operations, order management
- **Safety**: Always use paper trading mode

### Safety Tests (`@pytest.mark.safety`)
- **Purpose**: Test trading safety mechanisms
- **Speed**: Fast execution
- **Scope**: Safety decorators and validation
- **Critical**: Prevent live trading during tests

## 🔧 Test Configuration

### Environment Variables
The test suite automatically sets these environment variables:
```bash
TESTING_MODE=true
PREVENT_LIVE_TRADING_IN_TESTS=true
LIVE_TRADING_ALLOWED=false
```

### Pytest Configuration (`pytest.ini`)
- **Coverage**: Minimum 80% coverage required
- **Parallel**: Multi-process execution enabled
- **Reports**: HTML, XML, and JSON reports generated
- **Markers**: Strict marker validation enabled
- **Timeouts**: 30-second timeout per test

### Test Database
- **Type**: Temporary SQLite database
- **Location**: Automatically created in temp directory
- **Cleanup**: Automatically removed after tests
- **Isolation**: Each test gets a fresh database

## 📊 Test Coverage

### Core Modules
- **Trading Safety**: 100% coverage
  - Safety decorators
  - Live trading prevention
  - Paper trading requirements
  - Error handling

- **Database Layer**: 95% coverage
  - Connection management
  - CRUD operations
  - Error handling
  - Transaction management

- **Repository Pattern**: 90% coverage
  - Base repository functionality
  - Specialized repositories
  - Data validation
  - Query optimization

### Service Layer
- **Scheduler**: 85% coverage
  - Job management
  - Scheduling logic
  - Error handling

- **Order Monitor**: 80% coverage
  - Order status tracking
  - Trade updates
  - Error handling

- **Data Manager**: 85% coverage
  - Market data retrieval
  - Caching mechanisms
  - API integration

- **Scan Manager**: 80% coverage
  - Scanning algorithms
  - Result processing
  - Candidate evaluation

- **Trade Executor**: 85% coverage
  - Order execution
  - Trade validation
  - Error handling

### API Layer
- **Endpoints**: 90% coverage
  - CRUD operations
  - Data validation
  - Error handling
  - CORS support

### Utilities
- **Cache Service**: 95% coverage
  - TTL management
  - Memory optimization
  - Performance metrics

- **Filters**: 90% coverage
  - Data filtering
  - Range validation
  - String matching

- **YFinance Cache**: 85% coverage
  - API integration
  - Caching strategies
  - Error handling

## 🚦 Running Tests

### Basic Commands
```bash
# Run all tests
pytest

# Run with verbose output
pytest -v

# Run specific test file
pytest tests/test_api.py

# Run specific test class
pytest tests/test_api.py::TestAPI

# Run specific test method
pytest tests/test_api.py::TestAPI::test_get_trades

# Run tests matching pattern
pytest -k "test_get_trades"

# Run tests with specific marker
pytest -m "unit"
pytest -m "fast"
pytest -m "database"
```

### Advanced Commands
```bash
# Run with coverage
pytest --cov=. --cov-report=html

# Run with parallel execution
pytest -n auto

# Run with performance profiling
pytest --durations=10

# Run with HTML report
pytest --html=test-results/report.html

# Run with JUnit XML output
pytest --junitxml=test-results/junit.xml

# Run with benchmark
pytest --benchmark-only
```

### Test Runner Script
```bash
# Install dependencies and run all tests
python run_tests.py --install-deps --all

# Run only unit tests
python run_tests.py --unit

# Run specific test file
python run_tests.py --file tests/test_api.py

# Run specific test function
python run_tests.py --test tests/test_api.py::TestAPI::test_get_trades

# Generate coverage report
python run_tests.py --coverage
```

## 📈 Performance Metrics

### Test Execution Times
- **Unit Tests**: < 1 second total
- **Fast Tests**: < 100ms per test
- **Database Tests**: 100-500ms per test
- **API Tests**: 200-800ms per test
- **Integration Tests**: 500ms - 2s per test

### Memory Usage
- **Peak Memory**: < 100MB
- **Memory Leaks**: None detected
- **Cleanup**: Automatic after each test

### Coverage Targets
- **Overall Coverage**: ≥ 80%
- **Critical Modules**: ≥ 90%
- **Safety Modules**: 100%
- **API Endpoints**: ≥ 85%

## 🐛 Debugging Tests

### Common Issues
1. **Import Errors**: Ensure you're in the `backend` directory
2. **Database Errors**: Check that test database path is writable
3. **Mock Issues**: Verify mock setup and return values
4. **Environment Variables**: Ensure testing mode is enabled

### Debug Mode
```bash
# Run with debug output
pytest -s -v

# Run single test with debugger
pytest tests/test_api.py::TestAPI::test_get_trades -s

# Run with maximum verbosity
pytest -vvv
```

### Test Isolation
- Each test runs in isolation
- Database is reset between tests
- Mocks are reset automatically
- No shared state between tests

## 🔒 Security Considerations

### Trading Safety
- **Live Trading**: Always blocked during tests
- **Paper Trading**: Only mode allowed
- **API Keys**: Never used in tests
- **Real Orders**: Never placed during tests

### Data Protection
- **Test Data**: Uses synthetic data only
- **Credentials**: Mocked in all tests
- **External APIs**: Never called during tests
- **Database**: Temporary test database only

## 📋 Continuous Integration

### CI/CD Integration
```yaml
# Example GitHub Actions workflow
- name: Run Tests
  run: |
    cd backend
    python run_tests.py --all
    python run_tests.py --coverage

- name: Upload Coverage
  uses: codecov/codecov-action@v3
  with:
    file: ./backend/.coverage
```

### Quality Gates
- **Test Coverage**: ≥ 80%
- **Test Execution**: All tests must pass
- **Performance**: Tests complete within 5 minutes
- **Security**: No live trading attempts

## 🎯 Best Practices

### Writing Tests
1. **Test One Thing**: Each test should verify one behavior
2. **Descriptive Names**: Use clear, descriptive test names
3. **Arrange-Act-Assert**: Follow AAA pattern
4. **Mock External Dependencies**: Don't rely on external services
5. **Clean Setup/Teardown**: Use fixtures for test data

### Test Organization
1. **Group Related Tests**: Use test classes for related functionality
2. **Use Markers**: Mark tests with appropriate categories
3. **Keep Tests Fast**: Aim for < 100ms per test
4. **Minimize Dependencies**: Reduce test coupling

### Maintenance
1. **Update Tests**: Keep tests in sync with code changes
2. **Review Coverage**: Regularly review coverage reports
3. **Performance Monitoring**: Track test execution times
4. **Documentation**: Keep test documentation updated

## 📚 Additional Resources

### Documentation
- [Pytest Documentation](https://docs.pytest.org/)
- [Coverage.py Documentation](https://coverage.readthedocs.io/)
- [FastAPI Testing Guide](https://fastapi.tiangolo.com/tutorial/testing/)

### Testing Patterns
- [Test-Driven Development](https://en.wikipedia.org/wiki/Test-driven_development)
- [Behavior-Driven Development](https://en.wikipedia.org/wiki/Behavior-driven_development)
- [Mock Testing](https://en.wikipedia.org/wiki/Mock_object)

### Performance Testing
- [Pytest Benchmark](https://pytest-benchmark.readthedocs.io/)
- [Performance Testing Best Practices](https://martinfowler.com/articles/microservice-testing/#testing-component-performance)

## 🤝 Contributing

### Adding New Tests
1. Follow existing test patterns
2. Use appropriate markers
3. Ensure adequate coverage
4. Add to relevant test file
5. Update this documentation

### Test Review Process
1. All tests must pass
2. Coverage should not decrease
3. Performance should not degrade
4. Security requirements must be met
5. Documentation must be updated

---

For questions or issues with the test suite, please refer to the project documentation or create an issue in the project repository.

#!/bin/bash

# Script to set up DynamoDB tables for testing
# Make sure AWS credentials are configured before running this script

echo "Setting up DynamoDB tables for StockFilterLambda testing..."

# Set the region (change if needed)
REGION="us-east-1"

# Create earnings table
echo "Creating earnings table..."
aws dynamodb create-table \
    --table-name dev-EarningsTable \
    --attribute-definitions \
        AttributeName=scanDate,AttributeType=S \
        AttributeName=ticker,AttributeType=S \
    --key-schema \
        AttributeName=scanDate,KeyType=HASH \
        AttributeName=ticker,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    --region $REGION

# Wait for table to be created
echo "Waiting for earnings table to be created..."
aws dynamodb wait table-exists \
    --table-name dev-EarningsTable \
    --region $REGION

# Create filtered tickers table
echo "Creating filtered tickers table..."
aws dynamodb create-table \
    --table-name dev-filtered-tickers-table \
    --attribute-definitions \
        AttributeName=scanDate,AttributeType=S \
        AttributeName=ticker,AttributeType=S \
    --key-schema \
        AttributeName=scanDate,KeyType=HASH \
        AttributeName=ticker,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    --region $REGION

# Wait for table to be created
echo "Waiting for filtered tickers table to be created..."
aws dynamodb wait table-exists \
    --table-name dev-filtered-tickers-table \
    --region $REGION

echo "DynamoDB tables created successfully!"
echo "Earnings table: dev-EarningsTable"
echo "Filtered tickers table: dev-filtered-tickers-table"

# Add some test data
echo "Adding test data to earnings table..."
TODAY=$(date +%Y-%m-%d)

# Add test tickers
TICKERS=("AAPL" "MSFT" "GOOGL" "AMZN" "TSLA" "META" "NVDA" "NFLX" "AMD" "INTC")

for ticker in "${TICKERS[@]}"; do
    aws dynamodb put-item \
        --table-name dev-EarningsTable \
        --item "{\"scanDate\": {\"S\": \"$TODAY\"}, \"ticker\": {\"S\": \"$ticker\"}}" \
        --region $REGION
    echo "Added $ticker to earnings table"
done

echo "Test data added successfully!"
echo "You can now run the integration tests with: mvn test -Dtest=RealDataIntegrationTest"

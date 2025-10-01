#!/bin/bash

# Local testing script for Scan Earnings Lambda

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_status "Starting local testing for Scan Earnings Lambda"

# Check prerequisites
print_status "Checking prerequisites..."

# Check if Docker is running
if ! docker info &> /dev/null; then
    print_error "Docker is not running. Please start Docker first."
    exit 1
fi

# Check if SAM CLI is installed
if ! command -v sam &> /dev/null; then
    print_error "AWS SAM CLI is not installed. Please install it first."
    exit 1
fi

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    print_error "Maven is not installed. Please install it first."
    exit 1
fi

print_status "Prerequisites check passed"

# Build the project
print_status "Building the project..."
mvn clean package

if [ $? -ne 0 ]; then
    print_error "Maven build failed"
    exit 1
fi

print_status "Build completed successfully"

# Build SAM application
print_status "Building SAM application..."
sam build

if [ $? -ne 0 ]; then
    print_error "SAM build failed"
    exit 1
fi

print_status "SAM build completed successfully"

# Start DynamoDB Local
print_status "Starting DynamoDB Local..."
docker run -d --name dynamodb-local -p 8000:8000 amazon/dynamodb-local

# Wait for DynamoDB to start
print_status "Waiting for DynamoDB Local to start..."
sleep 5

# Create the table
print_status "Creating DynamoDB table..."
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

if [ $? -ne 0 ]; then
    print_warning "Table might already exist, continuing..."
fi

# Test the Lambda function
print_status "Testing Lambda function locally..."
sam local invoke ScanEarningsLambda \
    --event events/test-event.json \
    --env-vars events/env-vars.json \
    --parameter-overrides DynamoDbEndpoint=http://localhost:8000

if [ $? -ne 0 ]; then
    print_error "Lambda function test failed"
    # Clean up
    docker stop dynamodb-local
    docker rm dynamodb-local
    exit 1
fi

print_status "Lambda function test completed successfully!"

# Check DynamoDB table contents
print_status "Checking DynamoDB table contents..."
aws dynamodb scan \
    --table-name EarningsTable \
    --endpoint-url http://localhost:8000 \
    --query 'Items[0:5]' \
    --output table

# Clean up
print_status "Cleaning up..."
docker stop dynamodb-local
docker rm dynamodb-local

print_status "Local testing completed successfully!"
print_warning "Note: This test uses mock data and doesn't make real API calls."
print_warning "For full testing, you'll need to set up real API keys in AWS Secrets Manager."

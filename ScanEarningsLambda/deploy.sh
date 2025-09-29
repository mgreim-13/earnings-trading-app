#!/bin/bash

# Deployment script for Scan Earnings Lambda

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

# Check if environment is provided
if [ $# -eq 0 ]; then
    print_error "Please provide an environment (dev, staging, prod)"
    echo "Usage: $0 <environment>"
    exit 1
fi

ENVIRONMENT=$1

# Validate environment
if [[ ! "$ENVIRONMENT" =~ ^(dev|staging|prod)$ ]]; then
    print_error "Invalid environment. Must be one of: dev, staging, prod"
    exit 1
fi

print_status "Starting deployment for environment: $ENVIRONMENT"

# Check prerequisites
print_status "Checking prerequisites..."

# Check if AWS CLI is installed
if ! command -v aws &> /dev/null; then
    print_error "AWS CLI is not installed. Please install it first."
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

# Check AWS credentials
if ! aws sts get-caller-identity &> /dev/null; then
    print_error "AWS credentials not configured. Please run 'aws configure' first."
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

# Deploy to AWS
print_status "Deploying to AWS environment: $ENVIRONMENT"
sam deploy --config-env $ENVIRONMENT

if [ $? -ne 0 ]; then
    print_error "SAM deployment failed"
    exit 1
fi

print_status "Deployment completed successfully!"

# Get stack outputs
print_status "Retrieving stack outputs..."
STACK_NAME="trading-earnings-lambda-$ENVIRONMENT"

aws cloudformation describe-stacks \
    --stack-name $STACK_NAME \
    --query 'Stacks[0].Outputs' \
    --output table

print_status "Deployment script completed successfully!"
print_warning "Don't forget to:"
print_warning "1. Create the required secrets in AWS Secrets Manager"
print_warning "2. Test the Lambda function"
print_warning "3. Verify the DynamoDB table was created"

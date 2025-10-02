#!/bin/bash

# Deployment script for InitiateTradesLambda

set -e

echo "Starting deployment for InitiateTradesLambda..."

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

# Configuration
STACK_NAME="initiate-trades-lambda"
REGION="us-east-1"
S3_BUCKET="your-sam-deployment-bucket"

# Check if required tools are installed
check_dependencies() {
    print_status "Checking dependencies..."
    
    if ! command -v aws &> /dev/null; then
        print_error "AWS CLI is not installed. Please install AWS CLI."
        exit 1
    fi
    
    if ! command -v sam &> /dev/null; then
        print_error "SAM CLI is not installed. Please install SAM CLI."
        exit 1
    fi
    
    if ! command -v mvn &> /dev/null; then
        print_error "Maven is not installed. Please install Maven 3.6+."
        exit 1
    fi
    
    print_status "All dependencies checked."
}

# Check AWS credentials
check_aws_credentials() {
    print_status "Checking AWS credentials..."
    
    if ! aws sts get-caller-identity &> /dev/null; then
        print_error "AWS credentials not configured. Please run 'aws configure'."
        exit 1
    fi
    
    print_status "AWS credentials verified."
}

# Create S3 bucket if it doesn't exist
create_s3_bucket() {
    print_status "Creating S3 bucket for deployment artifacts..."
    
    if ! aws s3 ls "s3://$S3_BUCKET" 2>&1 | grep -q 'NoSuchBucket'; then
        print_status "S3 bucket $S3_BUCKET already exists."
    else
        aws s3 mb "s3://$S3_BUCKET" --region $REGION
        print_status "S3 bucket $S3_BUCKET created."
    fi
}

# Build the project
build_project() {
    print_status "Building project..."
    mvn clean package
    print_status "Build completed."
}

# Deploy with SAM
deploy_with_sam() {
    print_status "Deploying with SAM..."
    
    # Build with SAM
    sam build
    
    # Deploy
    sam deploy \
        --stack-name $STACK_NAME \
        --s3-bucket $S3_BUCKET \
        --region $REGION \
        --capabilities CAPABILITY_IAM \
        --parameter-overrides \
            FilteredTickersTableName=filtered-tickers-table \
            OrdersTableName=OrdersTable \
            AlpacaSecretName=trading/alpaca/credentials \
        --confirm-changeset
    
    print_status "SAM deployment completed."
}

# Verify deployment
verify_deployment() {
    print_status "Verifying deployment..."
    
    # Check if stack exists
    if aws cloudformation describe-stacks --stack-name $STACK_NAME --region $REGION &> /dev/null; then
        print_status "Stack $STACK_NAME deployed successfully."
        
        # Get stack outputs
        aws cloudformation describe-stacks \
            --stack-name $STACK_NAME \
            --region $REGION \
            --query 'Stacks[0].Outputs' \
            --output table
        
    else
        print_error "Stack deployment failed."
        exit 1
    fi
}

# Test the deployed function
test_deployed_function() {
    print_status "Testing deployed function..."
    
    # Create test event
    cat > test-deployment-event.json << EOF
{
    "scanDate": "2025-09-26"
}
EOF
    
    # Invoke the function
    aws lambda invoke \
        --function-name InitiateTradesLambda \
        --payload file://test-deployment-event.json \
        --region $REGION \
        response.json
    
    # Check response
    if [ -f response.json ]; then
        print_status "Function response:"
        cat response.json
        print_status "Function test completed."
    else
        print_error "Function test failed."
        exit 1
    fi
}

# Cleanup
cleanup() {
    print_status "Cleaning up temporary files..."
    rm -f test-deployment-event.json response.json
}

# Main execution
main() {
    # Set up cleanup on exit
    trap cleanup EXIT
    
    print_status "Starting InitiateTradesLambda deployment..."
    
    # Check dependencies
    check_dependencies
    
    # Check AWS credentials
    check_aws_credentials
    
    # Create S3 bucket
    create_s3_bucket
    
    # Build project
    build_project
    
    # Deploy with SAM
    deploy_with_sam
    
    # Verify deployment
    verify_deployment
    
    # Test deployed function
    test_deployed_function
    
    print_status "Deployment completed successfully!"
    print_status "Stack name: $STACK_NAME"
    print_status "Region: $REGION"
    print_status "S3 bucket: $S3_BUCKET"
}

# Run main function
main "$@"




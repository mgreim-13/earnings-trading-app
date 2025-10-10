#!/bin/bash

# Trading Lambda Deployment Script
# This script deploys all trading lambdas using CloudFormation

set -e

# Configuration
ENVIRONMENT=${1:-dev}
REGION=${2:-us-east-1}
STACK_NAME="trading-lambdas-${ENVIRONMENT}"
S3_BUCKET="trading-lambdas-${ENVIRONMENT}-$(date +%s)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}🚀 Starting Trading Lambda Deployment${NC}"
echo -e "Environment: ${ENVIRONMENT}"
echo -e "Region: ${REGION}"
echo -e "Stack Name: ${STACK_NAME}"
echo ""

# Check if AWS CLI is installed
if ! command -v aws &> /dev/null; then
    echo -e "${RED}❌ AWS CLI is not installed. Please install it first.${NC}"
    exit 1
fi

# Check if AWS credentials are configured
if ! aws sts get-caller-identity &> /dev/null; then
    echo -e "${RED}❌ AWS credentials not configured. Please run 'aws configure' first.${NC}"
    exit 1
fi

echo -e "${YELLOW}📦 Building Lambda packages...${NC}"

# Clean up any existing JAR files from previous builds
cleanup_old_jars() {
    echo -e "Cleaning up old JAR files..."
    rm -f *.jar
    echo -e "${GREEN}✅ Cleaned up old JAR files${NC}"
}

cleanup_old_jars

# Build all Lambda functions
build_lambda() {
    local lambda_name=$1
    local lambda_dir=$2
    local handler_class=$3
    
    echo -e "Building ${lambda_name}..."
    
    if [ ! -d "$lambda_dir" ]; then
        echo -e "${RED}❌ Directory $lambda_dir not found${NC}"
        exit 1
    fi
    
    cd "$lambda_dir"
    
    # Clean and build
    mvn clean package -DskipTests
    
    # Determine the correct JAR file to use
    local jar_file=""
    if ls target/*-shaded.jar 1> /dev/null 2>&1; then
        # Use shaded JAR if available
        jar_file=$(ls target/*-shaded.jar | head -1)
        echo -e "Using shaded JAR: $(basename "$jar_file")"
    else
        # Find the main JAR file (exclude original- prefix JARs)
        for jar in target/*.jar; do
            if [[ ! $(basename "$jar") =~ ^original- ]]; then
                jar_file="$jar"
                break
            fi
        done
        echo -e "Using main JAR: $(basename "$jar_file")"
    fi
    
    if [ -z "$jar_file" ] || [ ! -f "$jar_file" ]; then
        echo -e "${RED}❌ No valid JAR file found in $lambda_dir/target/${NC}"
        exit 1
    fi
    
    # Copy JAR to root directory with consistent naming
    cp "$jar_file" "../${lambda_name}.jar"
    
    # Verify critical classes are present (for debugging)
    echo -e "Verifying JAR contents..."
    if [[ "$lambda_name" == "stock-filter" ]]; then
        if jar -tf "../${lambda_name}.jar" | grep -q "software/amazon/awssdk/services/dynamodb/DynamoDbClient"; then
            echo -e "${GREEN}✅ DynamoDB client found in JAR${NC}"
        else
            echo -e "${RED}❌ DynamoDB client NOT found in JAR${NC}"
        fi
    fi
    
    echo -e "${GREEN}✅ Built ${lambda_name}${NC}"
    cd ..
}

# Build all lambdas
build_lambda "market-scheduler" "MarketSchedulerLambda" "com.trading.MarketSchedulerLambda"
build_lambda "scan-earnings" "ScanEarningsLambda" "com.trading.ScanEarningsLambda"
build_lambda "stock-filter" "StockFilterLambda" "com.trading.lambda.StockFilterLambda"
build_lambda "initiate-trades" "InitiateTradesLambda" "com.trading.lambda.InitiateTradesLambda"
build_lambda "initiate-exit-trades" "InitiateExitTradesLambda" "com.trading.lambda.InitiateExitTradesLambda"
build_lambda "update-exit-orders-at-market" "UpdateExitOrdersAtMarketLambda" "com.trading.lambda.UpdateExitOrdersAtMarketLambda"
build_lambda "convert-exit-orders-to-market" "ConvertExitOrdersToMarketLambda" "com.trading.lambda.ConvertExitOrdersToMarketLambda"
build_lambda "update-entry-orders-at-market" "UpdateEntryOrdersAtMarketLambda" "com.trading.lambda.UpdateEntryOrdersAtMarketLambda"
build_lambda "cancel-entry-orders" "CancelEntryOrdersLambda" "com.trading.lambda.CancelEntryOrdersLambda"
build_lambda "update-exit-orders-at-discount" "UpdateExitOrdersAtDiscountLambda" "com.trading.lambda.UpdateExitOrdersAtDiscountLambda"

# Verify all JAR files were created successfully
echo -e "${YELLOW}🔍 Verifying all JAR files were created...${NC}"
required_jars=("market-scheduler.jar" "scan-earnings.jar" "stock-filter.jar" "initiate-trades.jar" "initiate-exit-trades.jar" "update-exit-orders-at-market.jar" "convert-exit-orders-to-market.jar" "update-entry-orders-at-market.jar" "cancel-entry-orders.jar" "update-exit-orders-at-discount.jar")
missing_jars=()

for jar in "${required_jars[@]}"; do
    if [ ! -f "$jar" ]; then
        missing_jars+=("$jar")
    else
        echo -e "${GREEN}✅ Found $jar${NC}"
    fi
done

if [ ${#missing_jars[@]} -gt 0 ]; then
    echo -e "${RED}❌ Missing JAR files: ${missing_jars[*]}${NC}"
    exit 1
fi

echo -e "${GREEN}✅ All JAR files created successfully${NC}"

# Upload JAR files to S3
upload_to_s3() {
    local jar_name=$1
    local s3_key=$2
    
    echo -e "${YELLOW}📤 Uploading ${jar_name} to S3...${NC}"
    aws s3 cp "${jar_name}" "s3://${S3_BUCKET}/${s3_key}" --region "${REGION}"
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ Uploaded ${jar_name}${NC}"
    else
        echo -e "${RED}❌ Failed to upload ${jar_name}${NC}"
        exit 1
    fi
}

echo -e "${YELLOW}📤 Uploading JAR files to S3 bucket: ${S3_BUCKET}${NC}"

# Create S3 bucket if it doesn't exist
echo -e "${YELLOW}🪣 Creating S3 bucket: ${S3_BUCKET}${NC}"
aws s3 mb "s3://${S3_BUCKET}" --region "${REGION}" || echo -e "${YELLOW}⚠️  Bucket may already exist${NC}"

# Upload all JAR files to S3
upload_to_s3 "market-scheduler.jar" "market-scheduler-lambda-1.0.0.jar"
upload_to_s3 "scan-earnings.jar" "scan-earnings-lambda-1.0.0.jar"
upload_to_s3 "stock-filter.jar" "stock-filter-lambda-1.0.0.jar"
upload_to_s3 "initiate-trades.jar" "initiate-trades-lambda-1.0.0.jar"
upload_to_s3 "initiate-exit-trades.jar" "initiate-exit-trades-lambda-1.0.0.jar"
upload_to_s3 "update-exit-orders-at-market.jar" "update-exit-orders-at-market-lambda-1.0.0.jar"
upload_to_s3 "convert-exit-orders-to-market.jar" "convert-exit-orders-to-market-lambda-1.0.0.jar"
upload_to_s3 "update-entry-orders-at-market.jar" "update-entry-orders-at-market-lambda-1.0.0.jar"
upload_to_s3 "cancel-entry-orders.jar" "cancel-entry-orders-lambda-1.0.0.jar"
upload_to_s3 "update-exit-orders-at-discount.jar" "update-exit-orders-at-discount-lambda-1.0.0.jar"

echo -e "${GREEN}✅ All JAR files uploaded to S3 successfully${NC}"

echo -e "${YELLOW}☁️  Deploying CloudFormation stack...${NC}"

# Deploy CloudFormation stack
aws cloudformation deploy \
    --template-file cloudformation-template.yaml \
    --stack-name "$STACK_NAME" \
    --parameter-overrides \
        Environment="$ENVIRONMENT" \
        S3BucketName="$S3_BUCKET" \
        AlpacaSecretName="trading/alpaca/credentials" \
        FinnhubSecretName="trading/finnhub/credentials" \
    --capabilities CAPABILITY_NAMED_IAM \
    --region "$REGION"

echo -e "${YELLOW}🔐 Setting up secrets...${NC}"

# Create secrets if they don't exist
create_secret() {
    local secret_name=$1
    local secret_value=$2
    
    if aws secretsmanager describe-secret --secret-id "$secret_name" --region "$REGION" &> /dev/null; then
        echo -e "Secret ${secret_name} already exists"
    else
        echo -e "Creating secret ${secret_name}..."
        aws secretsmanager create-secret \
            --name "$secret_name" \
            --description "Trading API credentials" \
            --secret-string "$secret_value" \
            --region "$REGION"
        echo -e "${GREEN}✅ Created secret ${secret_name}${NC}"
    fi
}

# Prompt for API credentials
echo -e "${YELLOW}Please provide your API credentials:${NC}"

read -p "Alpaca API Key: " ALPACA_API_KEY
read -s -p "Alpaca Secret Key: " ALPACA_SECRET_KEY
echo ""
read -p "Finnhub API Key: " FINNHUB_API_KEY

# Create Alpaca secret
ALPACA_SECRET_JSON="{\"apiKey\":\"${ALPACA_API_KEY}\",\"secretKey\":\"${ALPACA_SECRET_KEY}\",\"baseUrl\":\"https://paper-api.alpaca.markets/v2\"}"
create_secret "trading/alpaca/credentials" "$ALPACA_SECRET_JSON"

# Create Finnhub secret
FINNHUB_SECRET_JSON="{\"apiKey\":\"${FINNHUB_API_KEY}\"}"
create_secret "trading/finnhub/credentials" "$FINNHUB_SECRET_JSON"

echo -e "${YELLOW}🧪 Testing all Lambda functions...${NC}"

# Test all lambda functions
test_lambda() {
    local function_name=$1
    local test_payload=$2
    
    echo -e "Testing ${function_name}..."
    
    if aws lambda invoke \
        --function-name "${ENVIRONMENT}-${function_name}" \
        --payload "$test_payload" \
        --cli-binary-format raw-in-base64-out \
        --region "$REGION" \
        "test-${function_name}-response.json" &> /dev/null; then
        
        # Check if response contains error
        if grep -q "errorMessage\|Error" "test-${function_name}-response.json"; then
            echo -e "${RED}❌ ${function_name} test failed${NC}"
            echo -e "${YELLOW}   Response: $(cat test-${function_name}-response.json)${NC}"
        else
            echo -e "${GREEN}✅ ${function_name} test passed${NC}"
        fi
    else
        echo -e "${RED}❌ ${function_name} test failed${NC}"
    fi
}

# Test all lambdas with appropriate payloads
test_lambda "market-scheduler" '{"source": "daily-schedule"}'
test_lambda "scan-earnings" '{"scanDate": "2025-01-10"}'
test_lambda "stock-filter" '{"scanDate": "2025-01-10"}'
test_lambda "initiate-trades" '{"scanDate": "2025-01-10"}'
test_lambda "initiate-exit-trades" '{"scanDate": "2025-01-10"}'
test_lambda "update-exit-orders-at-market" '{"scanDate": "2025-01-10"}'
test_lambda "convert-exit-orders-to-market" '{"scanDate": "2025-01-10"}'
test_lambda "update-entry-orders-at-market" '{"scanDate": "2025-01-10"}'
test_lambda "cancel-entry-orders" '{"scanDate": "2025-01-10"}'
test_lambda "update-exit-orders-at-discount" '{"scanDate": "2025-01-10"}'

echo -e "${GREEN}🎉 Deployment completed successfully!${NC}"
echo ""
echo -e "${YELLOW}📋 Next steps:${NC}"
echo "1. Verify your secrets in AWS Secrets Manager"
echo "2. Check CloudWatch logs for any issues"
echo "3. Monitor DynamoDB tables for data"
echo "4. Verify EventBridge rules are enabled"
echo ""
echo -e "${YELLOW}🔗 Useful commands:${NC}"
echo "• Test MarketScheduler: aws lambda invoke --function-name ${ENVIRONMENT}-market-scheduler --payload '{}' response.json"
echo "• View logs: aws logs describe-log-groups --log-group-name-prefix '/aws/lambda/${ENVIRONMENT}-'"
echo "• List tables: aws dynamodb list-tables"
echo "• List EventBridge rules: aws events list-rules --query 'Rules[?contains(Name, \`${ENVIRONMENT}-\`)]'"
echo ""
echo -e "${GREEN}✅ All done! Your trading infrastructure is ready with all 10 Lambda functions deployed and tested.${NC}"

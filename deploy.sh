#!/bin/bash

# Trading Lambda Deployment Script
# This script deploys all trading lambdas using CloudFormation

set -e

# Configuration
ENVIRONMENT=${1:-dev}
REGION=${2:-us-east-1}
STACK_NAME="trading-lambdas-${ENVIRONMENT}"

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
build_lambda "stock-filter" "StockFilterLambda" "com.example.StockFilterLambda"
build_lambda "initiate-trades" "InitiateTradesLambda" "com.example.InitiateTradesLambda"
build_lambda "monitor-trades" "MonitorTradesLambda" "com.example.MonitorTradesLambda"
build_lambda "initiate-exit-trades" "InitiateExitTradesLambda" "com.trading.lambda.InitiateExitTradesLambda"

# Verify all JAR files were created successfully
echo -e "${YELLOW}🔍 Verifying all JAR files were created...${NC}"
required_jars=("market-scheduler.jar" "scan-earnings.jar" "stock-filter.jar" "initiate-trades.jar" "monitor-trades.jar" "initiate-exit-trades.jar")
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

echo -e "${YELLOW}☁️  Deploying CloudFormation stack...${NC}"

# Deploy CloudFormation stack
aws cloudformation deploy \
    --template-file cloudformation-template.yaml \
    --stack-name "$STACK_NAME" \
    --parameter-overrides \
        Environment="$ENVIRONMENT" \
        AlpacaSecretName="trading/alpaca/credentials" \
        FinnhubSecretName="trading/finnhub/credentials" \
    --capabilities CAPABILITY_NAMED_IAM \
    --region "$REGION"

echo -e "${YELLOW}📝 Updating Lambda function codes...${NC}"

# Update Lambda function codes
update_lambda_code() {
    local function_name=$1
    local jar_file=$2
    
    if [ -f "$jar_file" ]; then
        echo -e "Updating ${function_name}..."
        aws lambda update-function-code \
            --function-name "${ENVIRONMENT}-${function_name}" \
            --zip-file "fileb://${jar_file}" \
            --region "$REGION"
        echo -e "${GREEN}✅ Updated ${function_name}${NC}"
    else
        echo -e "${RED}❌ JAR file ${jar_file} not found${NC}"
    fi
}

# Update all lambda codes
update_lambda_code "market-scheduler" "market-scheduler.jar"
update_lambda_code "scan-earnings" "scan-earnings.jar"
update_lambda_code "stock-filter" "stock-filter.jar"
update_lambda_code "initiate-trades" "initiate-trades.jar"
update_lambda_code "monitor-trades" "monitor-trades.jar"
update_lambda_code "initiate-exit-trades" "initiate-exit-trades.jar"

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

echo -e "${GREEN}🎉 Deployment completed successfully!${NC}"
echo ""
echo -e "${YELLOW}📋 Next steps:${NC}"
echo "1. Verify your secrets in AWS Secrets Manager"
echo "2. Test the MarketSchedulerLambda manually"
echo "3. Check CloudWatch logs for any issues"
echo "4. Monitor DynamoDB tables for data"
echo ""
echo -e "${YELLOW}🔗 Useful commands:${NC}"
echo "• Test MarketScheduler: aws lambda invoke --function-name ${ENVIRONMENT}-market-scheduler --payload '{}' response.json"
echo "• View logs: aws logs describe-log-groups --log-group-name-prefix '/aws/lambda/${ENVIRONMENT}-'"
echo "• List tables: aws dynamodb list-tables"
echo ""
echo -e "${GREEN}✅ All done! Your trading infrastructure is ready.${NC}"

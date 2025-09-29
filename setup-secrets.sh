#!/bin/bash

# Setup AWS Secrets for Trading Lambdas
# Run this script to create or update your API credentials

set -e

ENVIRONMENT=${1:-dev}
REGION=${2:-us-east-1}

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}🔐 Setting up AWS Secrets for Trading Lambdas${NC}"
echo -e "Environment: ${ENVIRONMENT}"
echo -e "Region: ${REGION}"
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

# Function to create or update secret
create_or_update_secret() {
    local secret_name=$1
    local secret_value=$2
    local description=$3
    
    if aws secretsmanager describe-secret --secret-id "$secret_name" --region "$REGION" &> /dev/null; then
        echo -e "Updating existing secret: ${secret_name}"
        aws secretsmanager update-secret \
            --secret-id "$secret_name" \
            --secret-string "$secret_value" \
            --region "$REGION"
        echo -e "${GREEN}✅ Updated secret: ${secret_name}${NC}"
    else
        echo -e "Creating new secret: ${secret_name}"
        aws secretsmanager create-secret \
            --name "$secret_name" \
            --description "$description" \
            --secret-string "$secret_value" \
            --region "$REGION"
        echo -e "${GREEN}✅ Created secret: ${secret_name}${NC}"
    fi
}

echo -e "${YELLOW}Please provide your API credentials:${NC}"
echo ""

# Get Alpaca credentials
echo -e "${YELLOW}Alpaca API Credentials:${NC}"
read -p "Alpaca API Key: " ALPACA_API_KEY
read -s -p "Alpaca Secret Key: " ALPACA_SECRET_KEY
echo ""
read -p "Alpaca Base URL (press Enter for paper trading): " ALPACA_BASE_URL

# Set default base URL if not provided
if [ -z "$ALPACA_BASE_URL" ]; then
    ALPACA_BASE_URL="https://paper-api.alpaca.markets/v2"
fi

# Get Finnhub credentials
echo ""
echo -e "${YELLOW}Finnhub API Credentials:${NC}"
read -p "Finnhub API Key: " FINNHUB_API_KEY

# Create Alpaca secret
ALPACA_SECRET_JSON="{\"apiKey\":\"${ALPACA_API_KEY}\",\"secretKey\":\"${ALPACA_SECRET_KEY}\",\"baseUrl\":\"${ALPACA_BASE_URL}\"}"
create_or_update_secret "trading/alpaca/credentials" "$ALPACA_SECRET_JSON" "Alpaca API credentials for trading"

# Create Finnhub secret
FINNHUB_SECRET_JSON="{\"apiKey\":\"${FINNHUB_API_KEY}\"}"
create_or_update_secret "trading/finnhub/credentials" "$FINNHUB_SECRET_JSON" "Finnhub API credentials for market data"

echo ""
echo -e "${GREEN}🎉 Secrets setup completed successfully!${NC}"
echo ""
echo -e "${YELLOW}📋 Verification:${NC}"
echo "You can verify your secrets using:"
echo "• aws secretsmanager get-secret-value --secret-id trading/alpaca/credentials --region ${REGION}"
echo "• aws secretsmanager get-secret-value --secret-id trading/finnhub/credentials --region ${REGION}"
echo ""
echo -e "${GREEN}✅ Your API credentials are now securely stored in AWS Secrets Manager.${NC}"

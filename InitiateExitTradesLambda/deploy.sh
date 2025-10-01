#!/bin/bash

# Deployment script for Initiate Exit Trades Lambda

set -e

FUNCTION_NAME="initiate-exit-trades-lambda"
JAR_FILE="target/initiate-exit-trades-lambda-1.0.0.jar"
ROLE_NAME="lambda-execution-role"
REGION="us-east-1"

echo "Deploying Initiate Exit Trades Lambda..."

# Check if JAR file exists
if [ ! -f "$JAR_FILE" ]; then
    echo "JAR file not found. Building first..."
    ./build.sh
fi

# Check if AWS CLI is configured
if ! aws sts get-caller-identity > /dev/null 2>&1; then
    echo "AWS CLI not configured. Please run 'aws configure' first."
    exit 1
fi

# Create IAM role if it doesn't exist
echo "Creating IAM role..."
aws iam create-role \
    --role-name $ROLE_NAME \
    --assume-role-policy-document '{
        "Version": "2012-10-17",
        "Statement": [
            {
                "Effect": "Allow",
                "Principal": {
                    "Service": "lambda.amazonaws.com"
                },
                "Action": "sts:AssumeRole"
            }
        ]
    }' 2>/dev/null || echo "Role already exists"

# Attach policies
echo "Attaching policies..."
aws iam attach-role-policy \
    --role-name $ROLE_NAME \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

aws iam attach-role-policy \
    --role-name $ROLE_NAME \
    --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite

# Wait for role to be ready
echo "Waiting for role to be ready..."
sleep 10

# Get role ARN
ROLE_ARN=$(aws iam get-role --role-name $ROLE_NAME --query 'Role.Arn' --output text)

# Create or update Lambda function
echo "Creating/updating Lambda function..."
aws lambda create-function \
    --function-name $FUNCTION_NAME \
    --runtime java11 \
    --role $ROLE_ARN \
    --handler com.trading.lambda.InitiateExitTradesLambda::handleRequest \
    --zip-file fileb://$JAR_FILE \
    --timeout 30 \
    --memory-size 512 \
    --environment Variables='{ALPACA_SECRET_NAME=trading/alpaca/credentials,PAPER_TRADING=true,AWS_REGION='$REGION'}' \
    2>/dev/null || aws lambda update-function-code \
    --function-name $FUNCTION_NAME \
    --zip-file fileb://$JAR_FILE

echo "Deployment completed!"
echo "Function ARN: $(aws lambda get-function --function-name $FUNCTION_NAME --query 'Configuration.FunctionArn' --output text)"
echo ""
echo "Next steps:"
echo "1. Create the Alpaca credentials secret in AWS Secrets Manager"
echo "2. Test the function with a sample event"
echo "3. Set up CloudWatch Events rule for scheduling (optional)"


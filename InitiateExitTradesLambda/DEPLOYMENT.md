# Initiate Exit Trades Lambda - Deployment Guide

## Overview

This guide provides step-by-step instructions for deploying the InitiateExitTradesLambda function to AWS.

## Prerequisites

1. **AWS CLI configured** with appropriate permissions
2. **Java 11+** installed locally
3. **Maven 3.6+** installed
4. **Alpaca trading account** with API credentials
5. **AWS account** with Lambda, Secrets Manager, and CloudWatch permissions

## Build the Project

```bash
# Navigate to project directory
cd /Users/mikegreim/InitiateExitTradesLambda

# Build the project (creates shaded JAR)
mvn clean package

# Verify JAR was created
ls -la target/initiate-exit-trades-lambda-1.0.0.jar
```

## Deploy to AWS

### Option 1: Using the Deployment Script

```bash
# Make script executable
chmod +x deploy.sh

# Run deployment script
./deploy.sh
```

### Option 2: Manual Deployment

#### 1. Create IAM Role

```bash
# Create trust policy
cat > trust-policy.json << EOF
{
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
}
EOF

# Create role
aws iam create-role \
  --role-name lambda-execution-role \
  --assume-role-policy-document file://trust-policy.json

# Attach policies
aws iam attach-role-policy \
  --role-name lambda-execution-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

aws iam attach-role-policy \
  --role-name lambda-execution-role \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite
```

#### 2. Create Alpaca Credentials Secret

```bash
# Create secret for paper trading
aws secretsmanager create-secret \
  --name alpaca-trading-credentials \
  --description "Alpaca trading API credentials" \
  --secret-string '{
    "keyId": "YOUR_ALPACA_KEY_ID",
    "secretKey": "YOUR_ALPACA_SECRET_KEY",
    "baseUrl": "https://paper-api.alpaca.markets"
  }'

# For live trading, use:
# "baseUrl": "https://api.alpaca.markets"
```

#### 3. Deploy Lambda Function

```bash
# Get role ARN
ROLE_ARN=$(aws iam get-role --role-name lambda-execution-role --query 'Role.Arn' --output text)

# Create Lambda function
aws lambda create-function \
  --function-name initiate-exit-trades-lambda \
  --runtime java11 \
  --role $ROLE_ARN \
  --handler com.trading.lambda.InitiateExitTradesLambda::handleRequest \
  --zip-file fileb://target/initiate-exit-trades-lambda-1.0.0.jar \
  --timeout 30 \
  --memory-size 512 \
  --environment Variables='{
    "ALPACA_SECRET_NAME": "alpaca-trading-credentials",
    "PAPER_TRADING": "true",
    "AWS_REGION": "us-east-1"
  }'
```

#### 4. Update Function Code (for updates)

```bash
aws lambda update-function-code \
  --function-name initiate-exit-trades-lambda \
  --zip-file fileb://target/initiate-exit-trades-lambda-1.0.0.jar
```

## Configuration

### Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `ALPACA_SECRET_NAME` | AWS Secrets Manager secret name | `alpaca-trading-credentials` |
| `PAPER_TRADING` | Enable paper trading mode | `true` or `false` |
| `AWS_REGION` | AWS region for Secrets Manager | `us-east-1` |

### Alpaca Credentials Secret Format

```json
{
  "keyId": "your-alpaca-key-id",
  "secretKey": "your-alpaca-secret-key",
  "baseUrl": "https://paper-api.alpaca.markets"
}
```

## Testing

### Test with Sample Event

```bash
# Test the function
aws lambda invoke \
  --function-name initiate-exit-trades-lambda \
  --payload file://test-event.json \
  response.json

# View response
cat response.json
```

### Monitor Logs

```bash
# View CloudWatch logs
aws logs tail /aws/lambda/initiate-exit-trades-lambda --follow
```

## Scheduling (Optional)

### Using EventBridge

```bash
# Create rule for every 5 minutes during market hours
aws events put-rule \
  --name exit-trades-schedule \
  --schedule-expression "rate(5 minutes)" \
  --description "Trigger exit trades evaluation"

# Add Lambda function as target
aws events put-targets \
  --rule exit-trades-schedule \
  --targets "Id"="1","Arn"="arn:aws:lambda:us-east-1:ACCOUNT:function:initiate-exit-trades-lambda"
```

### Using API Gateway (Optional)

```bash
# Create API Gateway
aws apigateway create-rest-api \
  --name exit-trades-api \
  --description "API for triggering exit trades"

# Add Lambda integration
aws apigateway put-integration \
  --rest-api-id API_ID \
  --resource-id RESOURCE_ID \
  --http-method POST \
  --type AWS_PROXY \
  --integration-http-method POST \
  --uri arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:ACCOUNT:function:initiate-exit-trades-lambda/invocations
```

## Monitoring and Alerting

### CloudWatch Alarms

```bash
# Create error rate alarm
aws cloudwatch put-metric-alarm \
  --alarm-name "ExitTradesLambda-Errors" \
  --alarm-description "Lambda function errors" \
  --metric-name Errors \
  --namespace AWS/Lambda \
  --statistic Sum \
  --period 300 \
  --threshold 1 \
  --comparison-operator GreaterThanOrEqualToThreshold \
  --dimensions Name=FunctionName,Value=initiate-exit-trades-lambda

# Create duration alarm
aws cloudwatch put-metric-alarm \
  --alarm-name "ExitTradesLambda-Duration" \
  --alarm-description "Lambda function duration" \
  --metric-name Duration \
  --namespace AWS/Lambda \
  --statistic Average \
  --period 300 \
  --threshold 25000 \
  --comparison-operator GreaterThanThreshold \
  --dimensions Name=FunctionName,Value=initiate-exit-trades-lambda
```

## Security Considerations

1. **IAM Permissions**: Use least privilege principle
2. **VPC Configuration**: Consider VPC for production
3. **Secrets Management**: Rotate API keys regularly
4. **CloudTrail**: Enable for audit logging
5. **Encryption**: Use KMS for sensitive data

## Troubleshooting

### Common Issues

1. **Timeout Errors**: Increase timeout or memory
2. **Permission Errors**: Check IAM role permissions
3. **Secret Not Found**: Verify secret name and region
4. **API Errors**: Check Alpaca API credentials and rate limits

### Debug Commands

```bash
# Check function configuration
aws lambda get-function --function-name initiate-exit-trades-lambda

# View recent logs
aws logs describe-log-streams --log-group-name /aws/lambda/initiate-exit-trades-lambda

# Test function locally (if needed)
java -cp target/initiate-exit-trades-lambda-1.0.0.jar com.trading.lambda.InitiateExitTradesLambda
```

## Production Checklist

- [ ] Test with paper trading first
- [ ] Configure proper IAM permissions
- [ ] Set up CloudWatch alarms
- [ ] Enable CloudTrail logging
- [ ] Configure VPC if needed
- [ ] Set up monitoring dashboard
- [ ] Test error handling scenarios
- [ ] Document runbooks
- [ ] Set up backup procedures

## Support

For issues or questions:
1. Check CloudWatch logs
2. Review AWS Lambda metrics
3. Verify Alpaca API status
4. Check IAM permissions
5. Review function configuration

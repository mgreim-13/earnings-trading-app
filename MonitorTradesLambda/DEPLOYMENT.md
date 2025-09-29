# MonitorTradesLambda Deployment Guide

## Quick Start

### Prerequisites
- Java 21
- Maven 3.6+
- AWS CLI configured
- SAM CLI installed
- Docker (for local testing)

### 1. Build the Project
```bash
mvn clean package
```

### 2. Deploy to AWS
```bash
# First time deployment (guided)
sam deploy --guided

# Subsequent deployments
sam deploy
```

### 3. Set Up Alpaca Credentials
```bash
aws secretsmanager update-secret \
  --secret-id alpaca-api-keys \
  --secret-string '{"apiKey":"your-alpaca-api-key","secretKey":"your-alpaca-secret-key"}'
```

### 4. Test the Function
```bash
# Test locally
sam local invoke MonitorTradesFunction --event events/test-event.json

# Test in AWS
aws lambda invoke \
  --function-name MonitorTradesFunction \
  --payload '{}' \
  response.json
```

## Configuration

### Environment Variables
- `ORDERS_TABLE`: DynamoDB table name (default: OrdersTable)
- `ALPACA_SECRET_NAME`: Secrets Manager secret name (default: alpaca-api-keys)
- `ALPACA_API_URL`: Alpaca API URL (default: https://paper-api.alpaca.markets)

### IAM Permissions Required
- `dynamodb:Query`, `dynamodb:Scan`, `dynamodb:GetItem`, `dynamodb:PutItem`, `dynamodb:UpdateItem`, `dynamodb:DeleteItem`
- `secretsmanager:GetSecretValue`
- `logs:CreateLogGroup`, `logs:CreateLogStream`, `logs:PutLogEvents`

## Monitoring

### CloudWatch Metrics
- Duration
- Memory utilization
- Error rate
- Throttles

### CloudWatch Logs
- Log Group: `/aws/lambda/MonitorTradesFunction`
- Log Level: INFO (configurable)

## Troubleshooting

### Common Issues
1. **Market Closed**: Function skips execution when market is closed
2. **Invalid Credentials**: Check Secrets Manager configuration
3. **DynamoDB Permissions**: Verify IAM role has required permissions
4. **Network Timeouts**: Check VPC configuration and security groups

### Debug Mode
Enable debug logging by setting log level to DEBUG in CloudWatch.

## Performance Tuning

### Memory Allocation
- Default: 256 MB
- Recommended: 512 MB for high-volume scenarios

### Timeout
- Default: 30 seconds
- Should be sufficient for most use cases

### Concurrency
- Sequential processing of orders
- ~2-5 seconds per invocation
- ~10 orders per invocation typical

## Security

### Best Practices
- Use IAM roles with minimal required permissions
- Store secrets in AWS Secrets Manager
- Enable VPC configuration if needed
- Use least privilege principle

### Network Security
- Configure security groups appropriately
- Use VPC endpoints for AWS services
- Monitor network traffic

## Maintenance

### Regular Tasks
- Monitor CloudWatch metrics
- Review logs for errors
- Update dependencies regularly
- Test with paper trading environment

### Updates
- Deploy new versions using SAM
- Test thoroughly before production
- Use blue-green deployment for critical updates

## Support

For issues or questions:
1. Check CloudWatch logs
2. Review this documentation
3. Check AWS Lambda documentation
4. Contact development team



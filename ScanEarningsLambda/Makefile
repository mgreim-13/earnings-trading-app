# Makefile for Scan Earnings Lambda

.PHONY: help build test clean package deploy-local deploy-dev deploy-staging deploy-prod

# Default target
help:
	@echo "Available targets:"
	@echo "  build         - Build the Maven project"
	@echo "  test          - Run unit tests"
	@echo "  clean         - Clean build artifacts"
	@echo "  package       - Package the application"
	@echo "  deploy-local  - Deploy locally with SAM"
	@echo "  deploy-dev    - Deploy to dev environment"
	@echo "  deploy-staging- Deploy to staging environment"
	@echo "  deploy-prod   - Deploy to production environment"
	@echo "  logs          - View CloudWatch logs"
	@echo "  invoke        - Invoke the Lambda function"

# Build the project
build:
	mvn clean compile

# Run tests
test:
	mvn test

# Clean build artifacts
clean:
	mvn clean

# Package the application
package: clean
	mvn package

# Deploy locally with SAM
deploy-local: package
	sam build
	sam local invoke ScanEarningsLambda --event events/test-event.json --env-vars events/env-vars.json

# Deploy to dev environment
deploy-dev: package
	sam build
	sam deploy --config-env dev

# Deploy to staging environment
deploy-staging: package
	sam build
	sam deploy --config-env staging

# Deploy to production environment
deploy-prod: package
	sam build
	sam deploy --config-env prod

# View CloudWatch logs
logs:
	sam logs -n ScanEarningsLambda --stack-name trading-earnings-lambda-dev --tail

# Invoke the Lambda function
invoke:
	aws lambda invoke \
		--function-name dev-ScanEarningsLambda \
		--payload '{}' \
		response.json && cat response.json

# Start DynamoDB local
dynamodb-local:
	docker run -p 8000:8000 amazon/dynamodb-local

# Create DynamoDB table locally
create-table-local:
	aws dynamodb create-table \
		--table-name earnings-table \
		--attribute-definitions \
			AttributeName=scanDate,AttributeType=S \
			AttributeName=ticker,AttributeType=S \
		--key-schema \
			AttributeName=scanDate,KeyType=HASH \
			AttributeName=ticker,KeyType=RANGE \
		--billing-mode PAY_PER_REQUEST \
		--endpoint-url http://localhost:8000

# Test with local DynamoDB
test-local: dynamodb-local create-table-local
	sleep 5
	sam local invoke ScanEarningsLambda \
		--event events/test-event.json \
		--env-vars events/env-vars.json \
		--parameter-overrides DynamoDbEndpoint=http://localhost:8000

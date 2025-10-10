#!/bin/bash

# Test a single lambda function
FUNCTION_NAME=$1
ENVIRONMENT=${2:-dev}
REGION=${3:-us-east-1}

echo "Testing ${FUNCTION_NAME}..."

# Create proper EventBridge payload
cat > test-payload.json << 'EOF'
{
  "source": "aws.events",
  "detail-type": "Scheduled Event",
  "detail": {}
}
EOF

# Test the lambda
aws lambda invoke \
    --function-name "${ENVIRONMENT}-${FUNCTION_NAME}" \
    --payload file://test-payload.json \
    --region "${REGION}" \
    test-response.json

# Check the response
if [ $? -eq 0 ]; then
    echo "Lambda invoke succeeded"
    echo "Response:"
    cat test-response.json
    echo ""
    
    # Check for errors in response
    if grep -q "errorMessage\|error\|Error" test-response.json; then
        echo "❌ Test failed - error in response"
        exit 1
    else
        echo "✅ Test passed"
        exit 0
    fi
else
    echo "❌ Test failed - lambda invoke failed"
    exit 1
fi

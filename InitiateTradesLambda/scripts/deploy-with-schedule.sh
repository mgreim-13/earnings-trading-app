#!/bin/bash
# Deploy Lambda with 3:45 PM EST scheduling

set -e

echo "🚀 Deploying InitiateTradesLambda with 3:45 PM EST scheduling..."

# Build the project
echo "📦 Building project..."
mvn clean package

# Deploy with SAM
echo "☁️  Deploying to AWS..."
sam deploy --guided

echo "✅ Deployment complete!"
echo ""
echo "📅 Schedule Configuration:"
echo "   - Time: 3:45 PM EST (8:45 PM UTC)"
echo "   - Frequency: Daily"
echo "   - Market Check: Lambda will check if market is open"
echo ""
echo "🔧 To modify schedule:"
echo "   1. Edit template.yaml line 79"
echo "   2. Run: sam deploy"
echo ""
echo "📊 To monitor:"
echo "   - CloudWatch Logs: /aws/lambda/InitiateTradesLambda"
echo "   - EventBridge Rules: Check AWS Console"


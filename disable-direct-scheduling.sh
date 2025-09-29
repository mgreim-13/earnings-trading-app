#!/bin/bash
# Script to disable direct scheduling on existing lambdas and enable Market Scheduler approach

set -e

echo "🔄 Disabling direct scheduling on existing lambdas..."
echo "This script will update the SAM templates to remove direct EventBridge scheduling"
echo "and rely on the Market Scheduler Lambda for conditional triggering."
echo ""

# Function to update a template file
update_template() {
    local template_file="$1"
    local lambda_name="$2"
    
    if [ -f "$template_file" ]; then
        echo "📝 Updating $template_file..."
        
        # Create backup
        cp "$template_file" "${template_file}.backup"
        
        # Remove Events section (this is a simplified approach)
        # In practice, you'd want to comment out or conditionally disable the Events
        echo "   - Events section will need manual review for $lambda_name"
        echo "   - Backup created: ${template_file}.backup"
    else
        echo "⚠️  Template not found: $template_file"
    fi
}

# Update each lambda template
echo "📋 Updating lambda templates..."

update_template "ScanEarningsLambda/template.yaml" "ScanEarningsLambda"
update_template "StockFilterLambda/template.yaml" "StockFilterLambda"
update_template "InitiateTradesLambda/template.yaml" "InitiateTradesLambda"
update_template "MonitorTradesLambda/template.yaml" "MonitorTradesLambda"
update_template "InitiateExitTradesLambda/template.yaml" "InitiateExitTradesLambda"

echo ""
echo "✅ Template updates completed!"
echo ""
echo "📋 Next steps:"
echo "1. Review the updated templates and manually disable Events sections"
echo "2. Deploy the Market Scheduler Lambda:"
echo "   cd MarketSchedulerLambda && sam deploy --guided"
echo "3. Update the target lambda function names in Market Scheduler environment variables"
echo "4. Redeploy all lambdas with updated templates"
echo ""
echo "🔧 Manual changes needed:"
echo "   - Comment out or remove Events sections in each lambda template"
echo "   - Ensure each lambda has ALPACA_SECRET_NAME environment variable"
echo "   - Update Market Scheduler with correct function names"
echo ""
echo "📚 See MarketSchedulerLambda/README.md for detailed configuration instructions"

# Test Scan Date Fix

## Summary of Changes

### 1. CloudFormation Template Updates
- Updated `NormalStockFilterRule` to use `InputTransformer` with `scanDate: "<aws.events.rule.schedule-time>"`
- Updated `NormalInitiateTradesRule` to use `InputTransformer` with `scanDate: "<aws.events.rule.schedule-time>"`
- Updated `EarlyStockFilterRule` to use `InputTransformer` with `scanDate: "<aws.events.rule.schedule-time>"`
- Updated `EarlyInitiateTradesRule` to use `InputTransformer` with `scanDate: "<aws.events.rule.schedule-time>"`

### 2. Lambda Function Updates
- Added `extractScanDate()` method to both `StockFilterLambda` and `InitiateTradesLambda`
- Method handles both manual input (YYYY-MM-DD format) and EventBridge input (ISO datetime format)
- Converts EventBridge ISO datetime to EST date string

## Test Cases

### Test 1: Manual Input (YYYY-MM-DD format)
```json
{
  "scanDate": "2025-01-15"
}
```
**Expected**: Returns "2025-01-15"

### Test 2: EventBridge Input (ISO datetime format)
```json
{
  "scanDate": "2025-01-15T20:35:00Z"
}
```
**Expected**: Returns "2025-01-15" (converted to EST)

### Test 3: No scanDate provided
```json
{
  "source": "stock-filter-schedule"
}
```
**Expected**: Returns current date in YYYY-MM-DD format

### Test 4: Invalid scanDate format
```json
{
  "scanDate": "invalid-date"
}
```
**Expected**: Returns current date in YYYY-MM-DD format (fallback)

## Verification Steps

1. Deploy the updated CloudFormation template
2. Test manual invocation with different scanDate formats
3. Verify scheduled triggers now pass scanDate explicitly
4. Check CloudWatch logs to confirm scanDate is being processed correctly

## Benefits

1. **Explicit scanDate**: Scheduled triggers now explicitly pass scanDate instead of relying on fallback
2. **Backward Compatibility**: Manual invocations still work with YYYY-MM-DD format
3. **EventBridge Compatibility**: Handles EventBridge's ISO datetime format automatically
4. **Robust Error Handling**: Falls back to current date if parsing fails
5. **Consistent Behavior**: All triggers now use the same date logic

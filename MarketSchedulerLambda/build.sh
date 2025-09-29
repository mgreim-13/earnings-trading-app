#!/bin/bash
# Build script for Market Scheduler Lambda

set -e

echo "🏗️  Building Market Scheduler Lambda..."

# Clean and compile
echo "📦 Compiling Java code..."
mvn clean compile

# Package the JAR
echo "📦 Creating JAR file..."
mvn package

echo "✅ Build complete!"
echo ""
echo "📁 Output: target/market-scheduler-lambda-1.0.0.jar"
echo ""
echo "🚀 To deploy:"
echo "   sam deploy --guided"

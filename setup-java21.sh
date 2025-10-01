#!/bin/bash

# Setup script to use Java 21 for the TradingAWS project
# Run this script before working on the project: source setup-java21.sh

export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH=$JAVA_HOME/bin:$PATH

echo "Java 21 environment set up successfully!"
echo "Java version: $(java -version 2>&1 | head -n 1)"
echo "JAVA_HOME: $JAVA_HOME"
echo ""
echo "To use this environment in your current shell, run:"
echo "source /Users/mikegreim/TradingAWS/setup-java21.sh"

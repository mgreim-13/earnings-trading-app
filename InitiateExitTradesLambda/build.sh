#!/bin/bash

# Build script for Initiate Exit Trades Lambda

echo "Building Initiate Exit Trades Lambda..."

# Clean and compile
mvn clean compile

if [ $? -eq 0 ]; then
    echo "Compilation successful!"
    
    # Run tests
    echo "Running tests..."
    mvn test
    
    if [ $? -eq 0 ]; then
        echo "Tests passed!"
        
        # Package the JAR
        echo "Packaging JAR..."
        mvn package
        
        if [ $? -eq 0 ]; then
            echo "Build completed successfully!"
            echo "JAR file created: target/initiate-exit-trades-lambda-1.0.0.jar"
        else
            echo "Package failed!"
            exit 1
        fi
    else
        echo "Tests failed!"
        exit 1
    fi
else
    echo "Compilation failed!"
    exit 1
fi


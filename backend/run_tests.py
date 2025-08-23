#!/usr/bin/env python3
"""
Test runner script for the trading application.
Provides different test configurations and generates comprehensive reports.
"""

import os
import sys
import subprocess
import argparse
from pathlib import Path


def run_command(cmd, description):
    """Run a command and handle errors."""
    print(f"\n🚀 {description}")
    print(f"Command: {' '.join(cmd)}")
    print("-" * 80)
    
    try:
        result = subprocess.run(cmd, check=True, capture_output=True, text=True)
        print("✅ Command completed successfully")
        if result.stdout:
            print("Output:")
            print(result.stdout)
        return True
    except subprocess.CalledProcessError as e:
        print(f"❌ Command failed with exit code {e.returncode}")
        if e.stdout:
            print("Stdout:")
            print(e.stdout)
        if e.stderr:
            print("Stderr:")
            print(e.stderr)
        return False


def install_test_dependencies():
    """Install test dependencies."""
    print("📦 Installing test dependencies...")
    cmd = [sys.executable, "-m", "pip", "install", "-r", "requirements-test.txt"]
    return run_command(cmd, "Installing test dependencies")


def run_unit_tests():
    """Run unit tests only."""
    print("🧪 Running unit tests...")
    cmd = [
        sys.executable, "-m", "pytest",
        "tests/",
        "-m", "unit",
        "--tb=short",
        "--verbose"
    ]
    return run_command(cmd, "Running unit tests")


def run_integration_tests():
    """Run integration tests only."""
    print("🔗 Running integration tests...")
    cmd = [
        sys.executable, "-m", "pytest",
        "tests/",
        "-m", "integration",
        "--tb=short",
        "--verbose"
    ]
    return run_command(cmd, "Running integration tests")


def run_fast_tests():
    """Run fast tests only."""
    print("⚡ Running fast tests...")
    cmd = [
        sys.executable, "-m", "pytest",
        "tests/",
        "-m", "fast",
        "--tb=short",
        "--verbose"
    ]
    return run_command(cmd, "Running fast tests")


def run_all_tests():
    """Run all tests with comprehensive reporting."""
    print("🎯 Running all tests with comprehensive reporting...")
    cmd = [
        sys.executable, "-m", "pytest",
        "tests/",
        "--tb=short",
        "--verbose",
        "--cov=.",
        "--cov-report=html",
        "--cov-report=term-missing",
        "--cov-report=xml",
        "--html=test-results/report.html",
        "--json-report",
        "--junitxml=test-results/junit.xml",
        "--durations=10"
    ]
    return run_command(cmd, "Running all tests with comprehensive reporting")


def run_coverage_report():
    """Generate coverage report."""
    print("📊 Generating coverage report...")
    cmd = [
        sys.executable, "-m", "coverage",
        "report",
        "--show-missing"
    ]
    return run_command(cmd, "Generating coverage report")


def run_benchmark_tests():
    """Run benchmark tests."""
    print("📈 Running benchmark tests...")
    cmd = [
        sys.executable, "-m", "pytest",
        "tests/",
        "--benchmark-only",
        "--benchmark-skip",
        "--tb=short"
    ]
    return run_command(cmd, "Running benchmark tests")


def run_specific_test_file(test_file):
    """Run tests from a specific file."""
    print(f"🎯 Running tests from {test_file}...")
    cmd = [
        sys.executable, "-m", "pytest",
        test_file,
        "--tb=short",
        "--verbose"
    ]
    return run_command(cmd, f"Running tests from {test_file}")


def run_specific_test_function(test_path):
    """Run a specific test function."""
    print(f"🎯 Running specific test: {test_path}...")
    cmd = [
        sys.executable, "-m", "pytest",
        test_path,
        "--tb=short",
        "--verbose"
    ]
    return run_command(cmd, f"Running specific test: {test_path}")


def create_test_directories():
    """Create necessary test directories."""
    directories = [
        "test-results",
        "htmlcov",
        ".coverage"
    ]
    
    for directory in directories:
        Path(directory).mkdir(exist_ok=True)
        print(f"📁 Created directory: {directory}")


def main():
    """Main test runner function."""
    parser = argparse.ArgumentParser(description="Trading Application Test Runner")
    parser.add_argument(
        "--install-deps",
        action="store_true",
        help="Install test dependencies"
    )
    parser.add_argument(
        "--unit",
        action="store_true",
        help="Run unit tests only"
    )
    parser.add_argument(
        "--integration",
        action="store_true",
        help="Run integration tests only"
    )
    parser.add_argument(
        "--fast",
        action="store_true",
        help="Run fast tests only"
    )
    parser.add_argument(
        "--benchmark",
        action="store_true",
        help="Run benchmark tests"
    )
    parser.add_argument(
        "--coverage",
        action="store_true",
        help="Generate coverage report"
    )
    parser.add_argument(
        "--file",
        type=str,
        help="Run tests from specific file"
    )
    parser.add_argument(
        "--test",
        type=str,
        help="Run specific test function (format: file.py::TestClass::test_function)"
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="Run all tests with comprehensive reporting"
    )
    
    args = parser.parse_args()
    
    # Create test directories
    create_test_directories()
    
    # Set testing environment variables
    os.environ['TESTING_MODE'] = 'true'
    # Removed: LIVE_TRADING_ALLOWED and PREVENT_LIVE_TRADING_IN_TESTS - consolidated into TESTING_MODE
    
    success = True
    
    try:
        if args.install_deps:
            success &= install_test_dependencies()
        
        if args.unit:
            success &= run_unit_tests()
        elif args.integration:
            success &= run_integration_tests()
        elif args.fast:
            success &= run_fast_tests()
        elif args.benchmark:
            success &= run_benchmark_tests()
        elif args.coverage:
            success &= run_coverage_report()
        elif args.file:
            success &= run_specific_test_file(args.file)
        elif args.test:
            success &= run_specific_test_function(args.test)
        elif args.all:
            success &= run_all_tests()
        else:
            # Default: run all tests
            success &= run_all_tests()
        
        if success:
            print("\n🎉 All tests completed successfully!")
            print("\n📊 Test results available in:")
            print("   - test-results/report.html (HTML report)")
            print("   - test-results/junit.xml (JUnit XML)")
            print("   - htmlcov/ (Coverage report)")
            print("   - .coverage (Coverage data)")
        else:
            print("\n❌ Some tests failed!")
            sys.exit(1)
            
    except KeyboardInterrupt:
        print("\n⚠️ Tests interrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"\n💥 Unexpected error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()

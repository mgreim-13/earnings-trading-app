#!/usr/bin/env python3
"""
Test setup verification script.
Verifies that the testing environment is properly configured.
"""

import os
import sys
import importlib
from pathlib import Path


def check_python_version():
    """Check Python version compatibility."""
    print("🐍 Checking Python version...")
    version = sys.version_info
    if version.major < 3 or (version.major == 3 and version.minor < 8):
        print(f"❌ Python 3.8+ required, found {version.major}.{version.minor}")
        return False
    print(f"✅ Python {version.major}.{version.minor}.{version.micro} - Compatible")
    return True


def check_test_dependencies():
    """Check if test dependencies are available."""
    print("\n📦 Checking test dependencies...")
    
    required_packages = [
        'pytest',
        'pytest_asyncio',
        'pytest_mock',
        'pytest_cov',
        'pytest_html',
        'coverage'
    ]
    
    missing_packages = []
    for package in required_packages:
        try:
            importlib.import_module(package.replace('-', '_'))
            print(f"✅ {package} - Available")
        except ImportError:
            print(f"❌ {package} - Missing")
            missing_packages.append(package)
    
    if missing_packages:
        print(f"\n⚠️ Missing packages: {', '.join(missing_packages)}")
        print("Run: python run_tests.py --install-deps")
        return False
    
    return True


def check_project_structure():
    """Check if project structure is correct."""
    print("\n📁 Checking project structure...")
    
    required_files = [
        'tests/__init__.py',
        'tests/conftest.py',
        'tests/test_trading_safety.py',
        'tests/test_database.py',
        'tests/test_base_repository.py',
        'tests/test_utils.py',
        'tests/test_services.py',
        'tests/test_api.py',
        'tests/test_core.py',
        'tests/test_repositories.py',
        'pytest.ini',
        'requirements-test.txt',
        'run_tests.py'
    ]
    
    missing_files = []
    for file_path in required_files:
        if Path(file_path).exists():
            print(f"✅ {file_path} - Found")
        else:
            print(f"❌ {file_path} - Missing")
            missing_files.append(file_path)
    
    if missing_files:
        print(f"\n⚠️ Missing files: {', '.join(missing_files)}")
        return False
    
    return True


def check_environment_variables():
    """Check if testing environment variables are set."""
    print("\n🔧 Checking environment variables...")
    
    required_vars = {
        'TESTING_MODE': 'true',
        'PREVENT_LIVE_TRADING_IN_TESTS': 'true',
        'LIVE_TRADING_ALLOWED': 'false'
    }
    
    missing_vars = []
    for var, expected_value in required_vars.items():
        actual_value = os.environ.get(var)
        if actual_value == expected_value:
            print(f"✅ {var}={actual_value} - Correct")
        else:
            print(f"❌ {var}={actual_value} - Expected {expected_value}")
            missing_vars.append(var)
    
    if missing_vars:
        print(f"\n⚠️ Incorrect environment variables: {', '.join(missing_vars)}")
        return False
    
    return True


def check_imports():
    """Check if main modules can be imported."""
    print("\n📚 Checking module imports...")
    
    modules_to_test = [
        'trading_safety',
        'core.database',
        'core.alpaca_client',
        'core.earnings_scanner',
        'services.scheduler',
        'services.order_monitor',
        'services.data_manager',
        'services.scan_manager',
        'services.trade_executor',
        'repositories.base_repository',
        'repositories.trade_repository',
        'repositories.scan_repository',
        'repositories.settings_repository',
        'repositories.trade_selections_repository',
        'utils.cache_service',
        'utils.filters',
        'utils.yfinance_cache',
        'api.app'
    ]
    
    failed_imports = []
    for module in modules_to_test:
        try:
            importlib.import_module(module)
            print(f"✅ {module} - Imported successfully")
        except ImportError as e:
            print(f"❌ {module} - Import failed: {e}")
            failed_imports.append(module)
    
    if failed_imports:
        print(f"\n⚠️ Failed imports: {', '.join(failed_imports)}")
        return False
    
    return True


def run_simple_test():
    """Run a simple test to verify pytest works."""
    print("\n🧪 Running simple test...")
    
    try:
        import pytest
        result = pytest.main(['--version'])
        if result == 0:
            print("✅ Pytest is working correctly")
            return True
        else:
            print("❌ Pytest execution failed")
            return False
    except Exception as e:
        print(f"❌ Pytest error: {e}")
        return False


def main():
    """Main verification function."""
    print("🔍 Trading Application Test Setup Verification")
    print("=" * 60)
    
    checks = [
        ("Python Version", check_python_version),
        ("Test Dependencies", check_test_dependencies),
        ("Project Structure", check_project_structure),
        ("Environment Variables", check_environment_variables),
        ("Module Imports", check_imports),
        ("Pytest Functionality", run_simple_test)
    ]
    
    results = []
    for check_name, check_func in checks:
        try:
            result = check_func()
            results.append((check_name, result))
        except Exception as e:
            print(f"❌ {check_name} - Error: {e}")
            results.append((check_name, False))
    
    # Summary
    print("\n" + "=" * 60)
    print("📊 VERIFICATION SUMMARY")
    print("=" * 60)
    
    passed = 0
    total = len(results)
    
    for check_name, result in results:
        status = "✅ PASS" if result else "❌ FAIL"
        print(f"{check_name:<25} {status}")
        if result:
            passed += 1
    
    print("-" * 60)
    print(f"Overall: {passed}/{total} checks passed")
    
    if passed == total:
        print("\n🎉 All checks passed! Your test environment is ready.")
        print("\nNext steps:")
        print("1. Run tests: python run_tests.py --all")
        print("2. Check coverage: python run_tests.py --coverage")
        print("3. Run specific tests: python run_tests.py --unit")
    else:
        print(f"\n⚠️ {total - passed} check(s) failed. Please fix the issues above.")
        print("\nCommon solutions:")
        print("1. Install dependencies: python run_tests.py --install-deps")
        print("2. Check file paths and permissions")
        print("3. Verify Python environment")
        sys.exit(1)


if __name__ == "__main__":
    main()

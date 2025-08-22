#!/usr/bin/env python3
"""
Start script for the Earnings Calendar Spread Trading Backend
"""

import os
import sys
import subprocess
import time
import requests

def check_backend_health():
    """Check if the backend is healthy."""
    try:
        # Import config to get the correct port
        sys.path.append(os.path.join(os.path.dirname(__file__), "backend"))
        import config
        port = config.SERVER_PORT
        response = requests.get(f"http://localhost:{port}/health", timeout=5)
        return response.status_code == 200
    except:
        return False

def start_backend():
    """Start the backend server."""
    print("🚀 Starting Earnings Calendar Spread Trading Backend...")
    
    # Change to backend directory
    backend_dir = os.path.join(os.path.dirname(__file__), "backend")
    os.chdir(backend_dir)
    
    # Check if virtual environment exists
    venv_path = os.path.join(backend_dir, "venv")
    if not os.path.exists(venv_path):
        print("❌ Virtual environment not found. Please run the setup first.")
        return False
    
    # Check if .env file exists
    env_path = os.path.join(backend_dir, ".env")
    if not os.path.exists(env_path):
        print("⚠️  .env file not found. Please create one with your API keys.")
        print("   Copy env_example.txt to .env and add your API keys.")
        return False
    
    # Load configuration to check trading mode
    try:
        sys.path.append(backend_dir)
        import config
        # Get current trading mode from the new credential system
        current_creds = config.get_current_alpaca_credentials()
        trading_mode = "PAPER" if current_creds['paper_trading'] else "LIVE"
        print(f"🔧 Trading mode: {trading_mode}")
        if not current_creds['paper_trading']:
            print("⚠️  WARNING: Live trading is enabled! This will execute real trades!")
            print("   Use the settings page to switch to paper trading mode.")
            confirm = input("   Type 'YES' to continue with live trading: ")
            if confirm != "YES":
                print("❌ Aborting startup - live trading not confirmed")
                return False
    except Exception as e:
        print(f"⚠️  Could not load configuration: {e}")
        print("   Proceeding with default settings...")
    
    # Start the backend
    try:
        port = getattr(config, 'SERVER_PORT', 8000)
        host = getattr(config, 'SERVER_HOST', '0.0.0.0')
        print(f"🔧 Starting FastAPI backend on http://{host}:{port}")
        print(f"📚 API documentation will be available at http://localhost:{port}/docs")
        
        # Start the server
        process = subprocess.Popen([
            sys.executable, "-m", "uvicorn", "app:app", 
            "--host", host, "--port", str(port), "--reload"
        ], cwd=backend_dir)
        
        # Wait for backend to start
        print("⏳ Waiting for backend to start...")
        for i in range(30):  # Wait up to 30 seconds
            if check_backend_health():
                print("✅ Backend started successfully!")
                print(f"🔧 Backend PID: {process.pid}")
                print(f"📱 Frontend can now connect to http://localhost:{port}")
                return process
            time.sleep(1)
            if i % 5 == 0:
                print(f"⏳ Still waiting... ({i+1}/30 seconds)")
        
        print("❌ Backend failed to start within 30 seconds")
        process.terminate()
        return False
        
    except KeyboardInterrupt:
        print("\n🛑 Shutting down backend...")
        if 'process' in locals():
            process.terminate()
        return False
    except Exception as e:
        print(f"❌ Failed to start backend: {e}")
        return False

def main():
    """Main function."""
    print("=" * 60)
    print("🎯 Earnings Calendar Spread Trading Application")
    print("=" * 60)
    
    # Start backend
    backend_process = start_backend()
    if not backend_process:
        print("❌ Failed to start backend")
        return 1
    
    try:
        print("\n" + "=" * 60)
        print("🎉 Backend is running!")
        print("📱 To start the frontend, open a new terminal and run:")
        print("   cd frontend && npm start")
        print("\n🛑 Press Ctrl+C to stop the backend")
        print("=" * 60)
        
        # Keep the process running
        backend_process.wait()
        
    except KeyboardInterrupt:
        print("\n🛑 Shutting down...")
        backend_process.terminate()
        backend_process.wait()
        print("✅ Backend stopped")
    
    return 0

if __name__ == "__main__":
    sys.exit(main())

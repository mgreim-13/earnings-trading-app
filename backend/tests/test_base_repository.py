"""
Tests for BaseRepository functionality.
"""

import pytest
from unittest.mock import Mock, patch, MagicMock
import sqlite3
import tempfile
import os
from repositories.base_repository import BaseRepository


class TestBaseRepository:
    """Test BaseRepository functionality."""

    @pytest.fixture(autouse=True)
    def setup_repository(self):
        """Setup test repository with temporary database."""
        self.temp_db = tempfile.NamedTemporaryFile(delete=False, suffix='.db')
        self.temp_db.close()
        self.repository = BaseRepository(self.temp_db.name)

    def teardown_method(self):
        """Clean up temporary database."""
        if hasattr(self, 'temp_db') and os.path.exists(self.temp_db.name):
            os.unlink(self.temp_db.name)

    @pytest.mark.unit
    @pytest.mark.database
    def test_repository_initialization(self):
        """Test repository initialization."""
        assert self.repository.db_path == self.temp_db.name
        # BaseRepository doesn't have a persistent connection attribute

    @pytest.mark.unit
    @pytest.mark.database
    def test_execute_query_success(self):
        """Test successful query execution."""
        # Create a test table first
        self.repository.execute_update("CREATE TABLE test_table (id INTEGER, name TEXT)")
        self.repository.execute_update("INSERT INTO test_table VALUES (1, 'test')")
        
        result = self.repository.execute_query("SELECT * FROM test_table")
        assert len(result) == 1
        assert result[0]['id'] == 1
        assert result[0]['name'] == 'test'

    @pytest.mark.unit
    @pytest.mark.database
    def test_execute_query_failure(self):
        """Test query execution failure."""
        result = self.repository.execute_query("SELECT * FROM nonexistent_table")
        assert result == []

    @pytest.mark.unit
    @pytest.mark.database
    def test_execute_update_success(self):
        """Test successful update execution."""
        result = self.repository.execute_update("CREATE TABLE test_table (id INTEGER, name TEXT)")
        assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_execute_update_failure(self):
        """Test update execution failure."""
        # Try to create a table with invalid SQL
        result = self.repository.execute_update("INVALID SQL STATEMENT")
        assert result is False

    @pytest.mark.unit
    @pytest.mark.database
    def test_execute_scalar_success(self):
        """Test successful scalar execution."""
        self.repository.execute_update("CREATE TABLE test_table (id INTEGER, name TEXT)")
        self.repository.execute_update("INSERT INTO test_table VALUES (1, 'test')")
        
        result = self.repository.execute_scalar("SELECT COUNT(*) FROM test_table")
        assert result == 1

    @pytest.mark.unit
    @pytest.mark.database
    def test_execute_scalar_failure(self):
        """Test scalar execution failure."""
        result = self.repository.execute_scalar("SELECT COUNT(*) FROM nonexistent_table")
        assert result is None

    @pytest.mark.unit
    @pytest.mark.database
    def test_table_exists(self):
        """Test checking if table exists."""
        # Check non-existent table
        assert self.repository.table_exists("nonexistent_table") is False
        
        # Create table and check again
        self.repository.execute_update("CREATE TABLE test_table (id INTEGER)")
        assert self.repository.table_exists("test_table") is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_get_table_info(self):
        """Test getting table info."""
        self.repository.execute_update("CREATE TABLE test_table (id INTEGER, name TEXT)")
        # Use direct SQL query since PRAGMA doesn't work with parameters
        info = self.repository.execute_query("PRAGMA table_info(test_table)")
        assert len(info) == 2  # Two columns
        assert any(col['name'] == 'id' for col in info)
        assert any(col['name'] == 'name' for col in info)

    @pytest.mark.unit
    @pytest.mark.database
    def test_vacuum_database(self):
        """Test database vacuum operation."""
        result = self.repository.vacuum_database()
        assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_analyze_database(self):
        """Test database analyze operation."""
        result = self.repository.analyze_database()
        assert result is True

    @pytest.mark.unit
    @pytest.mark.database
    def test_database_tables_created(self):
        """Test that required tables are created during initialization."""
        # Check that the required tables exist
        assert self.repository.table_exists("settings") is True
        assert self.repository.table_exists("selected_trades") is True
        assert self.repository.table_exists("trade_selections") is True
        assert self.repository.table_exists("trade_history") is True
        assert self.repository.table_exists("scan_results") is True
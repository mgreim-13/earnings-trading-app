import React, { useState, useEffect } from 'react';
import {
  Box,
  Typography,
  Card,
  CardContent,
  Grid,
  Switch,
  FormControlLabel,
  TextField,
  Button,
  Alert,

  Divider,
  Chip,
  List,
  ListItem,
  ListItemText,
  ListItemIcon,
  Paper,
} from '@mui/material';
import CuteMoneyBagLoader from '../components/CuteMoneyBagLoader';
import CuteInlineLoader from '../components/CuteInlineLoader';
import {
  PlayArrow as PlayIcon,
  Stop as StopIcon,
  Schedule as ScheduleIcon,
  Settings as SettingsIcon,
  Save as SaveIcon,
  Refresh as RefreshIcon,
} from '@mui/icons-material';
import { settingsAPI, schedulerAPI } from '../services/api';

const Settings = () => {
  const [settings, setSettings] = useState({
    auto_trading_enabled: false,
    risk_percentage: 1.0,
    paper_trading_enabled: true,
  });
  const [schedulerStatus, setSchedulerStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);

  const fetchSettings = async () => {
    try {
      setLoading(true);
      setError(null);

      const [settingsRes, schedulerRes] = await Promise.all([
        settingsAPI.getSettings(),
        schedulerAPI.getStatus(),
      ]);

      const settingsData = settingsRes.data.data;
      setSettings({
        auto_trading_enabled: settingsData.auto_trading_enabled === 'true',
        risk_percentage: settingsData.risk_percentage || 1.0,
        paper_trading_enabled: settingsData.paper_trading_enabled === 'true',
      });
      setSchedulerStatus(schedulerRes.data.data);
    } catch (err) {
      console.error('Failed to fetch settings:', err);
      setError('Failed to load settings. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchSettings();
  }, []);

  const handleSettingChange = (key, value) => {
    setSettings(prev => ({
      ...prev,
      [key]: value,
    }));
  };

  const handleAutoTradingToggle = async (enabled) => {
    try {
      setError(null);
      setSuccess(null);
      
      handleSettingChange('auto_trading_enabled', enabled);
      
      if (enabled) {
        await schedulerAPI.start();
        setSuccess('Automated trading enabled!');
      } else {
        await schedulerAPI.stop();
        setSuccess('Automated trading disabled!');
      }
      
      const schedulerRes = await schedulerAPI.getStatus();
      setSchedulerStatus(schedulerRes.data.data);
      
    } catch (err) {
      console.error('Failed to toggle auto-trading:', err);
      setError('Failed to update automated trading setting.');
      handleSettingChange('auto_trading_enabled', !enabled);
    }
  };

  const handleSaveAllSettings = async () => {
    try {
      setSaving(true);
      setError(null);
      setSuccess(null);

      await Promise.all([
        settingsAPI.updateSetting('auto_trading_enabled', settings.auto_trading_enabled.toString()),
        settingsAPI.updateSetting('risk_percentage', settings.risk_percentage.toString()),
        settingsAPI.updateSetting('paper_trading_enabled', settings.paper_trading_enabled.toString()),
      ]);

      setSuccess('Settings saved successfully!');
      fetchSettings();
    } catch (err) {
      console.error('Failed to save settings:', err);
      setError('Failed to save settings.');
    } finally {
      setSaving(false);
    }
  };

  const formatNextRun = (nextRunTime) => {
    if (!nextRunTime) return 'Not scheduled';
    try {
      return new Date(nextRunTime).toLocaleString();
    } catch {
      return 'Invalid date';
    }
  };

  if (loading) {
    return (
      <Box display="flex" justifyContent="center" alignItems="center" minHeight="400px">
        <CuteMoneyBagLoader 
          size="large" 
          message="Loading your trading settings... ⚙️" 
        />
      </Box>
    );
  }

  return (
    <Box>
      <Typography variant="h4" component="h1" mb={4}>
        Settings
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {error}
        </Alert>
      )}

      {success && (
        <Alert severity="success" sx={{ mb: 3 }}>
          {success}
        </Alert>
      )}

      <Grid container spacing={4}>
        {/* Trading Settings */}
        <Grid item xs={12} md={6}>
          <Card elevation={2}>
            <CardContent sx={{ p: 3 }}>
              <Box display="flex" alignItems="center" mb={3}>
                <SettingsIcon color="primary" sx={{ mr: 1.5 }} />
                <Typography variant="h5" component="h2">
                  Trading Settings
                </Typography>
              </Box>

              <Box sx={{ mb: 3 }}>
                <FormControlLabel
                  control={
                    <Switch
                      checked={settings.auto_trading_enabled}
                      onChange={(e) => handleAutoTradingToggle(e.target.checked)}
                      color="primary"
                      size="medium"
                    />
                  }
                  label="Enable Automated Trading"
                />
              </Box>

              <Box sx={{ mb: 3 }}>
                <TextField
                  fullWidth
                  label="Risk Percentage"
                  type="number"
                  value={settings.risk_percentage}
                  onChange={(e) => handleSettingChange('risk_percentage', parseFloat(e.target.value) || 1.0)}
                  inputProps={{ min: 0.1, max: 10, step: 0.1 }}
                  helperText="Account value percentage per position"
                  variant="outlined"
                />
              </Box>

              <Box sx={{ mb: 4 }}>
                <FormControlLabel
                  control={
                    <Switch
                      checked={settings.paper_trading_enabled}
                      onChange={(e) => handleSettingChange('paper_trading_enabled', e.target.checked)}
                      color="primary"
                      size="medium"
                    />
                  }
                  label="Paper Trading Mode"
                />
                <Typography variant="body2" color="text.secondary" sx={{ mt: 1, ml: 4 }}>
                  Current: {settings.paper_trading_enabled ? 'PAPER' : 'LIVE'}
                </Typography>
              </Box>

              <Button
                variant="contained"
                startIcon={<SaveIcon />}
                onClick={handleSaveAllSettings}
                disabled={saving}
                fullWidth
                size="large"
                sx={{ py: 1.5 }}
              >
                {saving ? (
                  <Box display="flex" alignItems="center" gap={1}>
                    <CuteInlineLoader size="small" animation="pulse" emoji="💾" />
                    Saving your settings...
                  </Box>
                ) : 'Save All Settings'}
              </Button>

              {!settings.paper_trading_enabled && (
                <Alert severity="warning" sx={{ mt: 2 }}>
                  Live trading mode enabled
                </Alert>
              )}
            </CardContent>
          </Card>
        </Grid>

        {/* Schedule Information */}
        <Grid item xs={12} md={6}>
          <Card elevation={2}>
            <CardContent sx={{ p: 3 }}>
              <Typography variant="h5" component="h2" mb={3}>
                Schedule Information
              </Typography>
              
              <Grid container spacing={3}>
                <Grid item xs={6} sm={3}>
                  <Box textAlign="center">
                    <Typography variant="body2" color="text.secondary">Daily Scan</Typography>
                    <Typography variant="h6" fontWeight="bold">3:00 PM ET</Typography>
                  </Box>
                </Grid>
                <Grid item xs={6} sm={3}>
                  <Box textAlign="center">
                    <Typography variant="body2" color="text.secondary">Trade Entry</Typography>
                    <Typography variant="h6" fontWeight="bold">3:45 PM ET</Typography>
                  </Box>
                </Grid>
                <Grid item xs={6} sm={3}>
                  <Box textAlign="center">
                    <Typography variant="body2" color="text.secondary">Trade Exit</Typography>
                    <Typography variant="h6" fontWeight="bold">9:45 AM ET</Typography>
                  </Box>
                </Grid>
                <Grid item xs={6} sm={3}>
                  <Box textAlign="center">
                    <Typography variant="body2" color="text.secondary">Data Cleanup</Typography>
                    <Typography variant="h6" fontWeight="bold">Sun 2:00 AM ET</Typography>
                  </Box>
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
};

export default Settings;

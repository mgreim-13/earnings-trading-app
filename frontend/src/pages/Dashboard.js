import React, { useState, useEffect, useCallback } from 'react';
import {
  Grid,
  Card,
  CardContent,
  Typography,
  Box,
  Chip,

  Alert,
  IconButton,
  Tooltip,
  Container,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
} from '@mui/material';
import CuteMoneyBagLoader from '../components/CuteMoneyBagLoader';
import {
  Refresh as RefreshIcon,
  TrendingUp as TrendingUpIcon,
  TrendingDown as TrendingDownIcon,
  AccountBalance as AccountIcon,
  ShowChart as ChartIcon,
  History as HistoryIcon,
} from '@mui/icons-material';
import { format } from 'date-fns';
import { dashboardAPI } from '../services/api';

const Dashboard = () => {
  const [accountInfo, setAccountInfo] = useState(null);
  const [positions, setPositions] = useState([]);
  const [recentTrades, setRecentTrades] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);


  const fetchDashboardData = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      const [accountRes, positionsRes, tradesRes] = await Promise.all([
        dashboardAPI.getAccountInfo(),
        dashboardAPI.getPositions(),
        dashboardAPI.getRecentTrades(),
      ]);

      setAccountInfo(accountRes.data.data);
      setPositions(positionsRes.data.data);
      setRecentTrades(tradesRes.data.data);
    } catch (err) {
      console.error('Failed to fetch dashboard data:', err);
      setError('Failed to load dashboard data. Please try again.');
    } finally {
      setLoading(false);
    }
  }, []);



 // No dependencies needed

  useEffect(() => {
    fetchDashboardData();
  }, [fetchDashboardData]); // Include fetchDashboardData in dependencies

  const formatCurrency = (value) => {
    if (value === null || value === undefined) return 'N/A';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
    }).format(value);
  };



  const getPnLColor = (pnl) => {
    if (pnl === null || pnl === undefined) return 'default';
    return pnl >= 0 ? 'success' : 'error';
  };

  const getPnLIcon = (pnl) => {
    if (pnl === null || pnl === undefined) return null;
    return pnl >= 0 ? <TrendingUpIcon /> : <TrendingDownIcon />;
  };



  if (loading) {
    return (
      <Box display="flex" justifyContent="center" alignItems="center" minHeight="400px">
        <CuteMoneyBagLoader 
          size="large" 
          message="Loading your trading dashboard... 📊" 
        />
      </Box>
    );
  }

  if (error) {
    return (
      <Alert severity="error" action={
        <IconButton color="inherit" size="small" onClick={fetchDashboardData}>
          <RefreshIcon />
        </IconButton>
      }>
        {error}
      </Alert>
    );
  }

  return (
    <Container maxWidth="xl">
      <Box display="flex" justifyContent="space-between" alignItems="center" mb={3}>
        <Typography variant="h4" component="h1">
          Dashboard
        </Typography>
        <Tooltip title="Refresh Data">
          <IconButton onClick={fetchDashboardData} color="primary">
            <RefreshIcon />
          </IconButton>
        </Tooltip>
      </Box>

      {/* Account Summary Cards */}
      <Grid container spacing={3} mb={4}>
        <Grid item xs={12} sm={6} md={3}>
          <Card>
            <CardContent>
              <Box display="flex" alignItems="center" mb={1}>
                <AccountIcon color="primary" sx={{ mr: 1 }} />
                <Typography variant="h6" component="div">
                  Portfolio Value
                </Typography>
              </Box>
              <Typography variant="h4" component="div" color="primary">
                {formatCurrency(accountInfo?.portfolio_value)}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Total Account Value
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} sm={6} md={3}>
          <Card>
            <CardContent>
              <Box display="flex" alignItems="center" mb={1}>
                <ChartIcon color="primary" sx={{ mr: 1 }} />
                <Typography variant="h6" component="div">
                  Buying Power
                </Typography>
              </Box>
              <Typography variant="h4" component="div" color="primary">
                {formatCurrency(accountInfo?.buying_power)}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Available for Trading
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} sm={6} md={3}>
          <Card>
            <CardContent>
              <Box display="flex" alignItems="center" mb={1}>
                <TrendingUpIcon color="primary" sx={{ mr: 1 }} />
                <Typography variant="h6" component="div">
                  Cash
                </Typography>
              </Box>
              <Typography variant="h4" component="div" color="primary">
                {formatCurrency(accountInfo?.cash)}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Available Cash
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} sm={6} md={3}>
          <Card>
            <CardContent>
              <Box display="flex" alignItems="center" mb={1}>
                <TrendingDownIcon color="primary" sx={{ mr: 1 }} />
                <Typography variant="h6" component="div">
                  Day Trades
                </Typography>
              </Box>
              <Typography variant="h4" component="div" color="primary">
                {accountInfo?.daytrade_count || 0}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Used Today
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>








              

      <Grid container spacing={3}>
        {/* Recent Trades */}
        <Grid item xs={12} lg={6}>
          <Card>
            <CardContent>
              <Box display="flex" alignItems="center" mb={2}>
                <HistoryIcon color="primary" sx={{ mr: 1 }} />
                <Typography variant="h5" component="h2">
                  Recent Trades
                </Typography>
              </Box>
              
              {recentTrades.length > 0 ? (
                <TableContainer component={Paper} variant="outlined">
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>Ticker</TableCell>
                        <TableCell>Type</TableCell>
                        <TableCell>Price</TableCell>
                        <TableCell>Quantity</TableCell>
                        <TableCell>Cost</TableCell>
                        <TableCell>Date</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {recentTrades.slice(0, 10).map((trade) => (
                        <TableRow key={trade.id}>
                          <TableCell>
                            <Typography variant="body2" fontWeight="bold">
                              {trade.ticker}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Chip
                              label={trade.trade_type === 'buy' ? 'BUY' : 'SELL'}
                              color={trade.trade_type === 'buy' ? 'success' : 'error'}
                              size="small"
                            />
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2">
                              {formatCurrency(trade.entry_price)}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2">
                              {trade.quantity}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2" color="text.secondary">
                              {(() => {
                                try {
                                  const cost = trade.cost_basis || trade.pnl;
                                  if (cost !== null && cost !== undefined) {
                                    return formatCurrency(cost);
                                  }
                                  return 'N/A';
                                } catch (error) {
                                  console.error('Cost formatting error:', error, trade.cost_basis, trade.pnl);
                                  return 'Error';
                                }
                              })()}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2">
                              {(() => {
                                try {
                                  if (trade.entry_time) {
                                    return format(new Date(trade.entry_time), 'MM/dd HH:mm');
                                  }
                                  return 'N/A';
                                } catch (error) {
                                  console.error('Date parsing error:', error, trade.entry_time);
                                  return 'Invalid Date';
                                }
                              })()}
                            </Typography>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              ) : (
                <Box>
                  <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 2 }}>
                    No recent trades found
                  </Typography>
                </Box>
              )}
            </CardContent>
          </Card>
        </Grid>

        {/* Current Positions */}
        <Grid item xs={12} lg={6}>
          <Card>
            <CardContent>
              <Box display="flex" alignItems="center" mb={2}>
                <TrendingUpIcon color="primary" sx={{ mr: 1 }} />
                <Typography variant="h5" component="h2">
                  Current Positions
                </Typography>
              </Box>
              
              {positions.length > 0 ? (
                <TableContainer component={Paper} variant="outlined">
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>Ticker</TableCell>
                        <TableCell>Quantity</TableCell>
                        <TableCell>Entry Price</TableCell>
                        <TableCell>Current Value</TableCell>
                        <TableCell>Unrealized P&L</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {positions.map((position) => (
                        <TableRow key={position.asset_id}>
                          <TableCell>
                            <Typography variant="body2" fontWeight="bold">
                              {position.symbol}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2">
                              {position.qty}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2">
                              {formatCurrency(position.avg_entry_price)}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2">
                              {formatCurrency(position.market_value)}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Chip
                              icon={getPnLIcon(position.unrealized_pl)}
                              label={formatCurrency(position.unrealized_pl)}
                              color={getPnLColor(position.unrealized_pl)}
                              size="small"
                            />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              ) : (
                <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 2 }}>
                  No open positions
                </Typography>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* Account Details */}
      <Grid container spacing={3} mt={2}>
        <Grid item xs={12}>
          <Card>
            <CardContent>
              <Typography variant="h6" component="h3" mb={2}>
                Account Details
              </Typography>
              <Grid container spacing={2}>
                <Grid item xs={12} sm={6} md={3}>
                  <Typography variant="body2" color="text.secondary">
                    Account Status
                  </Typography>
                  <Typography variant="body1" fontWeight="bold">
                    {accountInfo?.status || 'N/A'}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6} md={3}>
                  <Typography variant="body2" color="text.secondary">
                    Pattern Day Trader
                  </Typography>
                  <Typography variant="body1" fontWeight="bold">
                    {accountInfo?.pattern_day_trader ? 'Yes' : 'No'}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6} md={3}>
                  <Typography variant="body2" color="text.secondary">
                    Shorting Enabled
                  </Typography>
                  <Typography variant="body1" fontWeight="bold">
                    {accountInfo?.shorting_enabled ? 'Yes' : 'No'}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6} md={3}>
                  <Typography variant="body2" color="text.secondary">
                    Trading Blocked
                  </Typography>
                  <Typography variant="body1" fontWeight="bold">
                    {accountInfo?.trading_blocked ? 'Yes' : 'No'}
                  </Typography>
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Container>
  );
};

export default Dashboard;

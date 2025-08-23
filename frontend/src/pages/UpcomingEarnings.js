import React, { useState, useEffect } from 'react';
import {
  Box,
  Typography,
  Card,
  CardContent,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Chip,

  Alert,
  IconButton,
  Tooltip,
  Grid,
  Checkbox,
  Button,
  Accordion,
  AccordionSummary,
  AccordionDetails,
  Snackbar,
} from '@mui/material';
import CuteMoneyBagLoader from '../components/CuteMoneyBagLoader';
import CuteInlineLoader from '../components/CuteInlineLoader';
import {
  Refresh as RefreshIcon,
  ExpandMore as ExpandMoreIcon,
  CheckCircle as CheckCircleIcon,
  Cancel as CancelIcon,
  PlayArrow as PlayIcon,
} from '@mui/icons-material';
import { format } from 'date-fns';
import { dashboardAPI, tradeSelectionAPI, cacheAPI } from '../services/api';

const UpcomingEarnings = () => {
  const [earnings, setEarnings] = useState([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState(null);
  const [filterWeights, setFilterWeights] = useState({});

  const [lastRefresh, setLastRefresh] = useState(null);
  const [cacheStatus, setCacheStatus] = useState('fresh');
  const [loadingDuration, setLoadingDuration] = useState(0);
  const [tradeSelections, setTradeSelections] = useState({});
  const [selectionLoading, setSelectionLoading] = useState({});
  const [snackbar, setSnackbar] = useState({ open: false, message: '', severity: 'success' });

  // Load trade selections from database
  const loadTradeSelections = async () => {
    try {
      const response = await tradeSelectionAPI.getTradeSelections();
      if (response.data?.success && response.data?.data) {
        const selections = {};
        response.data.data.forEach(selection => {
          const key = `${selection.ticker}_${selection.earnings_date}`;
          selections[key] = selection;
        });
        setTradeSelections(selections);
        
        // Clean up old selections that are no longer relevant
        cleanupOldSelections(selections);
      }
    } catch (err) {
      setSnackbar({
        open: true,
        message: 'Failed to load trade selections',
        severity: 'error'
      });
    }
  };

  // Clean up old selections that are no longer in the current earnings list
  const cleanupOldSelections = async (selections) => {
    if (!earnings.length) return; // Wait for earnings to load
    
    const visibleTickers = new Set(earnings.map(e => `${e.symbol}_${e.date}`));
    const oldSelections = Object.entries(selections)
      .filter(([key, selection]) => 
        !visibleTickers.has(key) && selection.is_selected
      );
    
    if (oldSelections.length > 0) {
      // Deselect old selections
      for (const [key, selection] of oldSelections) {
        try {
          await tradeSelectionAPI.selectTrade(selection.ticker, selection.earnings_date, false);
        } catch (err) {
          // Silent cleanup failure
        }
      }
      
      // Reload selections after cleanup
      await loadTradeSelections();
    }
  };

  // Handle trade selection checkbox change
  const handleTradeSelectionChange = async (ticker, earningsDate, isSelected) => {
    const key = `${ticker}_${earningsDate}`;
    
    // Set loading state for this specific selection
    setSelectionLoading(prev => ({ ...prev, [key]: true }));
    
    try {
      const response = await tradeSelectionAPI.selectTrade(ticker, earningsDate, isSelected);
      if (response.data?.success) {
        // Update local state
        setTradeSelections(prev => ({
          ...prev,
          [key]: {
            ticker,
            earnings_date: earningsDate,
            is_selected: isSelected,
            updated_at: new Date().toISOString()
          }
        }));
        
        // Show success message
        setSnackbar({
          open: true,
          message: `${ticker} ${isSelected ? 'selected' : 'deselected'} for trading`,
          severity: 'success'
        });
      } else {
        // Show error message
        setSnackbar({
          open: true,
          message: `Failed to ${isSelected ? 'select' : 'deselect'} ${ticker}: ${response.data?.error || 'Unknown error'}`,
          severity: 'error'
        });
      }
    } catch (err) {
      setSnackbar({
        open: true,
        message: `Failed to ${isSelected ? 'select' : 'deselect'} ${ticker}`,
        severity: 'error'
      });
    } finally {
      // Clear loading state
      setSelectionLoading(prev => ({ ...prev, [key]: false }));
    }
  };

  // Check if a stock is selected
  const isStockSelected = (ticker, earningsDate) => {
    const key = `${ticker}_${earningsDate}`;
    return tradeSelections[key]?.is_selected || false;
  };

  // Check if a stock is auto-selected (for display purposes)
  const isStockAutoSelected = (ticker, earningsDate) => {
    // Since we now treat all selected stocks the same way, 
    // we can determine if it was auto-selected by checking if it has a high score
    const earning = earnings.find(e => e.symbol === ticker && e.date === earningsDate);
    if (earning?.scan_result?.total_score) {
      return earning.scan_result.total_score >= 80;
    }
    return false;
  };

  // Check if selection is loading
  const isSelectionLoading = (ticker, earningsDate) => {
    const key = `${ticker}_${earningsDate}`;
    return selectionLoading[key] || false;
  };

  // Handle snackbar close
  const handleCloseSnackbar = () => {
    setSnackbar(prev => ({ ...prev, open: false }));
  };

  const fetchEarnings = async () => {
    try {
      setLoading(true);
      setError(null);
      const startTime = Date.now();
      setLoadingDuration(0);

      const response = await dashboardAPI.getUpcomingEarningsWithScan();
      
      // Extract filter weights from API response
      if (response.data.filter_weights) {
        setFilterWeights(response.data.filter_weights);
      }
      
      // Sort earnings by date (earliest first) - avoid timezone issues
      const sortedEarnings = response.data.data.sort((a, b) => {
        // Parse dates without timezone issues
        const [yearA, monthA, dayA] = a.date.split('-').map(Number);
        const [yearB, monthB, dayB] = b.date.split('-').map(Number);
        const dateA = new Date(yearA, monthA - 1, dayA);
        const dateB = new Date(yearB, monthB - 1, dayB);
        return dateA - dateB;
      });
      
      setEarnings(sortedEarnings);
      
      // Update cache status
      const now = new Date();
      setLastRefresh(now);
      
      // Check if we have scan results (indicating fresh data)
      const hasScanResults = sortedEarnings.some(earning => earning.scan_result);
      setCacheStatus(hasScanResults ? 'fresh' : 'cached');
      
      // Calculate loading duration
      const endTime = Date.now();
      const duration = endTime - startTime;
      setLoadingDuration(duration);
      
    } catch (err) {
      // Provide more specific error messages
      let errorMessage = 'Failed to load earnings data. Please try again.';
      
      if (err.code === 'ECONNABORTED' || err.message?.includes('timeout')) {
        errorMessage = 'Request timed out. The scan is taking longer than expected. Please try again or wait for cached results.';
      } else if (err.response?.status === 500) {
        errorMessage = 'Server error occurred during scan. Please try again in a few moments.';
      } else if (err.response?.status === 404) {
        errorMessage = 'Earnings data endpoint not found. Please check your connection.';
      } else if (!navigator.onLine) {
        errorMessage = 'No internet connection. Please check your network and try again.';
      }
      
      setError(errorMessage);
      setCacheStatus('error');
    } finally {
      setLoading(false);
    }
  };

  const forceRefreshScan = async () => {
    console.log('🔍 Force refresh scan button clicked!');
    try {
      console.log('🔍 Setting refreshing state...');
      setRefreshing(true);
      setError(null);
      setCacheStatus('refreshing');
      
      console.log('🔍 Clearing cache...');
      // Clear the cache first to force fresh scan results
      const cacheResponse = await cacheAPI.clearCache();
      console.log('🔍 Cache clear response:', cacheResponse);
      console.log('🔍 Cache cleared, fetching fresh scan results...');
      
      // Then fetch fresh earnings data
      console.log('🔍 Fetching fresh earnings...');
      await fetchEarnings();
      console.log('🔍 Fresh earnings fetched successfully!');
      
    } catch (err) {
      console.error('❌ Failed to force refresh scan:', err);
      setError('Failed to clear cache and refresh scan. Please try again.');
      setCacheStatus('error');
    } finally {
      setRefreshing(false);
    }
  };

  // Load trade selections when component mounts
  useEffect(() => {
    loadTradeSelections();
  }, []);

  // Clean up old selections when earnings change
  useEffect(() => {
    if (earnings.length > 0 && Object.keys(tradeSelections).length > 0) {
      cleanupOldSelections(tradeSelections);
    }
  }, [earnings]);

  useEffect(() => {
    fetchEarnings();
  }, []);

  const getEarningsTimeColor = (hour) => {
    if (hour === 'bmo') return 'primary'; // Before market open
    if (hour === 'amc') return 'secondary'; // After market close
    if (hour === 'dmt') return 'success'; // During market trading
    if (!hour || hour === '') return 'default'; // Handle blank/empty values
    return 'default';
  };

  const getEarningsTimeLabel = (hour) => {
    if (hour === 'bmo') return 'Pre-Market';
    if (hour === 'amc') return 'After Hours';
    if (hour === 'dmt') return 'Market Hours';
    if (hour === 'tna') return 'TBA';
    if (!hour || hour === '') return 'TBA'; // Handle blank/empty values
    return hour;
  };

  const formatDate = (dateStr) => {
    try {
      // Parse date string and create date object without timezone issues
      const [year, month, day] = dateStr.split('-').map(Number);
      const date = new Date(year, month - 1, day); // month is 0-indexed
      return format(date, 'MMM dd, yyyy');
    } catch {
      return dateStr;
    }
  };

  const formatCurrency = (value) => {
    if (value === null || value === undefined) return 'N/A';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
    }).format(value);
  };

  const formatScore = (score) => {
    if (score === null || score === undefined) return 'N/A';
    return `${score.toFixed(1)}%`;
  };

  const formatMetric = (value, decimalPlaces = 2) => {
    if (value === null || value === undefined) return 'N/A';
    return value.toFixed(decimalPlaces);
  };

  const getRecommendationColor = (recommendation) => {
    switch (recommendation) {
      case 'recommended':
        return 'success';
      case 'consider':
        return 'warning';
      case 'avoid':
        return 'error';
      case 'skip':
        return 'default';
      default:
        return 'default';
    }
  };

  const getRecommendationIcon = (recommendation) => {
    switch (recommendation) {
      case 'recommended':
        return <CheckCircleIcon />;
      case 'consider':
        return <PlayIcon />;
      case 'avoid':
        return <CancelIcon />;
      default:
        return null;
    }
  };

  const getFilterWeight = (filterName) => {
    // Use filter weights from API response, fallback to hardcoded values if not available
    const weights = filterWeights && Object.keys(filterWeights).length > 0 
      ? filterWeights 
              : {
          'avg_volume': 0.10,
          'iv30_rv30': 0.22,
          'ts_slope_0_45': 0.18,
          'hist_earn_vol': 0.16,
          'option_liquidity': 0.18,
          'iv_percentile': 0.10,
          'beta': 0.03,
          'short': 0.02,
          'rsi': 0.01,
        };
    
    return weights[filterName] || 0;
  };

  const getFilterStatus = (score) => {
    if (score === null || score === undefined) return 'missing';
    if (score === 0) return 'neutral';
    if (score > 0.8) return 'strong';
    if (score > 0.5) return 'moderate';
    return 'weak';
  };

  const getStatusColor = (status) => {
    switch (status) {
      case 'strong':
        return 'success';
      case 'moderate':
        return 'warning';
      case 'weak':
        return 'info';
      case 'neutral':
        return 'default';
      case 'missing':
        return 'error';
      default:
        return 'default';
    }
  };

  const formatFilterName = (filterName) => {
    switch (filterName) {
      case 'iv_percentile':
        return 'IV Percentile';
      case 'beta':
        return 'Beta';
      case 'short':
        return 'Short %';
      case 'rsi':
        return 'RSI';

      default:
        return filterName.replace(/_/g, ' ').replace(/\b\w/g, l => l.toUpperCase());
    }
  };

  // Compute recommendation based on total score and liquidity requirement (same logic as backend)
  const computeRecommendation = (totalScore, optionLiquidityScore = null) => {
    if (totalScore === null || totalScore === undefined) return 'skip';
    
    // Check liquidity requirement first - if below 0.6, mark as avoid regardless of total score
    if (optionLiquidityScore !== null && optionLiquidityScore < 0.6) {
      return 'avoid';
    }
    
    // Normal scoring logic
    if (totalScore > 80) return 'recommended';
    if (totalScore > 60) return 'consider';
    return 'avoid';
  };

  // Get computed recommendations for all earnings
  const getComputedRecommendations = () => {
    return earnings.map(earning => {
      if (!earning.scan_result) return { ...earning, computedRecommendation: 'skip' };
      
      const totalScore = earning.scan_result.total_score;
      const optionLiquidityScore = earning.scan_result?.scores?.option_liquidity;
      const computedRecommendation = computeRecommendation(totalScore, optionLiquidityScore);
      
      return {
        ...earning,
        computedRecommendation
      };
    });
  };

  // Get earnings grouped by recommendation type and sorted by date
  const getEarningsByRecommendation = () => {
    const computedEarnings = getComputedRecommendations();
    
    // Group by recommendation type
    const grouped = {
      recommended: [],
      consider: [],
      avoid: [],
      skip: []
    };
    
    computedEarnings.forEach(earning => {
      const recommendation = earning.computedRecommendation;
      if (grouped[recommendation]) {
        grouped[recommendation].push(earning);
      }
    });
    
    // Sort each group by date (ascending)
    Object.keys(grouped).forEach(key => {
      grouped[key].sort((a, b) => {
        const [yearA, monthA, dayA] = a.date.split('-').map(Number);
        const [yearB, monthB, dayB] = b.date.split('-').map(Number);
        const dateA = new Date(yearA, monthA - 1, dayA);
        const dateB = new Date(yearB, monthB - 1, dayB);
        return dateA - dateB;
      });
    });
    
    return grouped;
  };

  if (loading) {
    return (
      <Box display="flex" justifyContent="center" alignItems="center" minHeight="400px">
        <CuteMoneyBagLoader 
          size="large" 
          message="Scanning for earnings opportunities... 🔍" 
        />
      </Box>
    );
  }

  if (error) {
    return (
      <Alert severity="error" action={
        <IconButton color="inherit" size="small" onClick={forceRefreshScan}>
          <RefreshIcon />
        </IconButton>
      }>
        {error}
      </Alert>
    );
  }

  return (
    <Box>
      <Box display="flex" justifyContent="space-between" alignItems="center" mb={3}>
        <Typography variant="h4" component="h1">
          Daily Trade Recommendations
        </Typography>
        <Box display="flex" alignItems="center" gap={2}>
            {/* Cache Status */}
            {lastRefresh && (
              <Chip
                label={`Cache: ${cacheStatus === 'fresh' ? 'Fresh' : 'Cached'}`}
                color={cacheStatus === 'fresh' ? 'success' : 'warning'}
                size="small"
                variant="outlined"
              />
            )}
            
            <Tooltip title="Force Refresh Scan (Clears Cache)">
              <IconButton 
                onClick={() => {
                  console.log('🔍 Button clicked!');
                  forceRefreshScan();
                }}
                color="primary"
                disabled={refreshing}
              >
                {refreshing ? <CuteInlineLoader size="small" animation="spin" emoji="🔄" /> : <RefreshIcon />}
              </IconButton>
            </Tooltip>
        </Box>
      </Box>

      {/* Scan Summary */}
      <Box mb={3}>
        <Typography variant="h6" component="h3" mb={2}>
          Scan Summary
        </Typography>
        <Grid container spacing={2}>
          <Grid item xs={12} sm={6} md={3}>
            <Card variant="outlined">
              <CardContent sx={{ textAlign: 'center', py: 2 }}>
                <Typography variant="h4" color="success.main" fontWeight="bold">
                  {getComputedRecommendations().filter(e => e.computedRecommendation === 'recommended').length}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Recommended
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <Card variant="outlined">
              <CardContent sx={{ textAlign: 'center', py: 2 }}>
                <Typography variant="h4" color="warning.main" fontWeight="bold">
                  {getComputedRecommendations().filter(e => e.computedRecommendation === 'consider').length}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Consider
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <Card variant="outlined">
              <CardContent sx={{ textAlign: 'center', py: 2 }}>
                <Typography variant="h4" color="error.main" fontWeight="bold">
                  {getComputedRecommendations().filter(e => e.computedRecommendation === 'avoid').length}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Avoid
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <Card variant="outlined">
              <CardContent sx={{ textAlign: 'center', py: 2 }}>
                <Typography variant="h4" color="text.secondary" fontWeight="bold">
                  {getComputedRecommendations().filter(e => e.computedRecommendation === 'skip').length}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Skipped
                </Typography>
              </CardContent>
            </Card>
          </Grid>
        </Grid>
      </Box>

      {/* Earnings by Recommendation Type */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Typography variant="h6" component="h2" mb={2}>
            Calendar Spread Candidates
          </Typography>
          
          {/* Calendar Spread Strategy Explanation */}
          <Alert severity="info" sx={{ mb: 2 }}>
            <Typography variant="body2">
              <strong>Calendar Spread Strategy:</strong> Sell a short-term call option with expiration closest to the earnings announcement 
              and simultaneously buy a longer-term call option with the same strike price. The position is entered at 3:45 PM ET on the trading day and automatically closed at 9:45 AM ET 
              the following trading day. Only stocks with sufficient option liquidity (score ≥ 0.6) are considered to ensure smooth execution.
            </Typography>
          </Alert>
          
          {/* Trade Execution Controls */}
          <Box sx={{ mb: 2, display: 'flex', gap: 2, alignItems: 'center', flexWrap: 'wrap' }}>
            <Typography variant="body2" color="text.secondary">
              {(() => {
                const totalSelections = Object.values(tradeSelections).filter(s => s.is_selected).length;
                return `${totalSelections} stocks selected for automatic trading`;
              })()}
            </Typography>
            
            {/* Management Buttons - Removed as per new unified selection system */}
          </Box>
          
          {earnings.length > 0 ? (
            <Box>
              {Object.entries(getEarningsByRecommendation()).map(([recommendationType, earningsList]) => {
                if (earningsList.length === 0) return null;

                const getRecommendationColor = (type) => {
                  switch (type) {
                    case 'recommended': return 'success';
                    case 'consider': return 'warning';
                    case 'avoid': return 'error';
                    case 'skip': return 'default';
                    default: return 'default';
                  }
                };

                const getRecommendationLabel = (type) => {
                  switch (type) {
                    case 'recommended': return 'Recommended';
                    case 'consider': return 'Consider';
                    case 'avoid': return 'Avoid';
                    case 'skip': return 'Skipped';
                    default: return type;
                  }
                };

                return (
                  <Box key={recommendationType} mb={3}>
                    <Typography
                      variant="h6"
                      sx={{ 
                        mb: 2, 
                        display: 'flex', 
                        alignItems: 'center', 
                        gap: 1,
                        color: (theme) => {
                          switch (recommendationType) {
                            case 'recommended': return theme.palette.success.main;
                            case 'consider': return theme.palette.warning.main;
                            case 'avoid': return theme.palette.error.main;
                            case 'skip': return theme.palette.text.secondary;
                            default: return theme.palette.text.secondary;
                          }
                        }
                      }}
                    >
                      {getRecommendationIcon(recommendationType)}
                      {getRecommendationLabel(recommendationType)} ({earningsList.length})
                    </Typography>

                    <TableContainer component={Paper} variant="outlined">
                      <Table size="small">
                        <TableHead>
                          <TableRow>
                            <TableCell sx={{ minWidth: 60, fontWeight: 'bold' }}>Select</TableCell>
                            <TableCell sx={{ minWidth: 80, fontWeight: 'bold' }}>Ticker</TableCell>
                            <TableCell sx={{ minWidth: 100, fontWeight: 'bold' }}>Earnings Date</TableCell>
                            <TableCell sx={{ minWidth: 140, fontWeight: 'bold' }}>Time</TableCell>
                            <TableCell sx={{ minWidth: 120, fontWeight: 'bold' }}>Recommendation</TableCell>
                            <TableCell sx={{ minWidth: 100, fontWeight: 'bold' }}>Total Score</TableCell>
                            <TableCell sx={{ minWidth: 120, fontWeight: 'bold' }}>Expected Move</TableCell>
                            <TableCell sx={{ minWidth: 120, fontWeight: 'bold' }}>Current Price</TableCell>
                            <TableCell sx={{ minWidth: 120, fontWeight: 'bold' }}>Filter Details</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {earningsList.map((earning, index) => {
                            const selectionKey = `${earning.symbol}_${earning.date}`;
                            const isSelected = isStockSelected(earning.symbol, earning.date);
                            const isAutoSelected = isStockAutoSelected(earning.symbol, earning.date);
                            const isLoading = isSelectionLoading(earning.symbol, earning.date);
                            
                            return (
                              <TableRow key={`${recommendationType}-${index}`} hover>
                                <TableCell>
                                  <Checkbox
                                    checked={isSelected}
                                    onChange={(e) => handleTradeSelectionChange(earning.symbol, earning.date, e.target.checked)}
                                    color="primary"
                                    size="small"
                                    title={isAutoSelected ? "High-scoring stock (Score 80+)" : "Select for trade execution"}
                                    disabled={isLoading}
                                  />
                                </TableCell>
                                <TableCell>
                                  <Typography variant="body2" fontWeight="bold" color="primary">
                                    {earning.symbol}
                                  </Typography>
                                </TableCell>
                              <TableCell>
                                <Typography variant="body2">
                                  {formatDate(earning.date)}
                                </Typography>
                              </TableCell>
                              <TableCell>
                                <Chip
                                  label={getEarningsTimeLabel(earning.hour)}
                                  color={getEarningsTimeColor(earning.hour)}
                                  size="small"
                                />
                              </TableCell>
                              <TableCell>
                                {earning.scan_result ? (
                                  <Chip
                                                    icon={getRecommendationIcon(computeRecommendation(earning.scan_result.total_score, earning.scan_result?.scores?.option_liquidity))}
                label={computeRecommendation(earning.scan_result.total_score, earning.scan_result?.scores?.option_liquidity)}
                color={getRecommendationColor(computeRecommendation(earning.scan_result.total_score, earning.scan_result?.scores?.option_liquidity))}
                                    size="small"
                                  />
                                ) : (
                                  <Typography variant="body2" color="text.secondary">
                                    Not Scanned
                                  </Typography>
                                )}
                              </TableCell>
                              <TableCell>
                                <Typography variant="body2" fontWeight="bold">
                                  {earning.scan_result ? formatScore(earning.scan_result.total_score) : 'N/A'}
                                </Typography>
                              </TableCell>
                              <TableCell>
                                <Typography variant="body2">
                                  {earning.scan_result?.expected_move !== undefined && earning.scan_result?.expected_move !== null
                                    ? earning.scan_result.expected_move
                                    : 'N/A'}
                                </Typography>
                              </TableCell>
                              <TableCell>
                                <Typography variant="body2">
                                  {earning.scan_result ? formatCurrency(earning.scan_result.underlying_price) : 'N/A'}
                                </Typography>
                              </TableCell>
                              <TableCell>
                                {earning.scan_result ? (
                                  <Accordion size="small">
                                    <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                                      <Typography variant="body2">View Details</Typography>
                                    </AccordionSummary>
                                    <AccordionDetails>
                                      {computeRecommendation(earning.scan_result.total_score, earning.scan_result?.scores?.option_liquidity) === 'skip' ? (
                                        <Typography variant="body2" color="text.secondary">
                                          {earning.scan_result.skip_reason}
                                        </Typography>
                                      ) : (
                                        <Box>
                                          <Typography variant="body2" fontWeight="bold" mb={2}>
                                            Filter Analysis Results:
                                          </Typography>

                                          {/* Filter Scores Table */}
                                          {earning.scan_result.scores && (
                                            <>
                                              <TableContainer component={Paper} variant="outlined" sx={{ mb: 2 }}>
                                                <Table size="small">
                                                  <TableHead>
                                                    <TableRow>
                                                      <TableCell>Filter</TableCell>
                                                      <TableCell>Score</TableCell>
                                                      <TableCell>Weight</TableCell>
                                                      <TableCell>Weighted Score</TableCell>
                                                      <TableCell>Status</TableCell>
                                                    </TableRow>
                                                  </TableHead>
                                                  <TableBody>
                                                    {Object.entries(earning.scan_result.scores).map(([filter, score]) => {
                                                      const weight = getFilterWeight(filter);
                                                      const weightedScore = score * weight * 100;
                                                      const status = getFilterStatus(score);

                                                      return (
                                                        <TableRow key={filter}>
                                                          <TableCell>
                                                            <Typography variant="body2" fontWeight="medium">
                                                              {formatFilterName(filter)}
                                                            </Typography>
                                                          </TableCell>
                                                          <TableCell>
                                                            <Typography variant="body2">
                                                              {score.toFixed(3)}
                                                            </Typography>
                                                          </TableCell>
                                                          <TableCell>
                                                            <Typography variant="body2" color="text.secondary">
                                                              {(weight * 100).toFixed(1)}%
                                                            </Typography>
                                                          </TableCell>
                                                          <TableCell>
                                                            <Typography variant="body2" fontWeight="bold">
                                                              {weightedScore.toFixed(1)}%
                                                            </Typography>
                                                          </TableCell>
                                                          <TableCell>
                                                            <Chip
                                                              label={status}
                                                              color={getStatusColor(status)}
                                                              size="small"
                                                              variant="outlined"
                                                            />
                                                          </TableCell>
                                                        </TableRow>
                                                      );
                                                    })}
                                                  </TableBody>
                                                </Table>
                                              </TableContainer>
                                            </>
                                          )}
                                        </Box>
                                      )}
                                    </AccordionDetails>
                                  </Accordion>
                                ) : (
                                  <Typography variant="body2" color="text.secondary">
                                    No scan data
                                  </Typography>
                                )}
                              </TableCell>
                            </TableRow>
                          );
                        })}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  </Box>
                );
              })}
            </Box>
          ) : (
            <Box textAlign="center" py={2}>
              <Typography variant="body1" color="text.secondary">
                No earnings data available
              </Typography>
            </Box>
          )}
        </CardContent>
      </Card>



      {/* Filter Information */}
      <Box mt={3}>
        <Typography variant="body2" color="text.secondary">
          <strong>Note:</strong> Only stocks with confirmed timing (Pre-Market or After Hours) are shown. Stocks without 
          specific timing are excluded from both the earnings calendar and trading scan logic. Quarter values show fiscal 
          quarters (companies may report Q2 2026 earnings in August 2025 due to different fiscal year ends).
        </Typography>
        
        {/* Cache Information */}
        <Box mt={2}>
          <Typography variant="body2" color="text.secondary">
            <strong>Cache Information:</strong> Scan results are cached for 5 minutes to improve performance. 
            Fresh data indicates recent scans, while cached data shows results from within the last 5 minutes. 
            Use the refresh button to clear the cache and force a fresh scan of all stocks.
          </Typography>
        </Box>
      </Box>

      {/* Snackbar for trade selection updates */}
      <Snackbar
        open={snackbar.open}
        autoHideDuration={6000}
        onClose={handleCloseSnackbar}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
      >
        <Alert onClose={handleCloseSnackbar} severity={snackbar.severity} sx={{ width: '100%' }}>
          {snackbar.message}
        </Alert>
      </Snackbar>
    </Box>
  );
};

export default UpcomingEarnings;

import axios from 'axios';

const API_BASE_URL = process.env.REACT_APP_API_URL || 'http://localhost:8000';
const API_TIMEOUT = process.env.REACT_APP_API_TIMEOUT || 300000; // 5 minutes default

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: API_TIMEOUT,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor for logging
api.interceptors.request.use(
  (config) => {
    console.log(`API Request: ${config.method?.toUpperCase()} ${config.url}`);
    return config;
  },
  (error) => {
    console.error('API Request Error:', error);
    return Promise.reject(error);
  }
);

// Response interceptor for error handling
api.interceptors.response.use(
  (response) => {
    return response;
  },
  (error) => {
    console.error('API Response Error:', error);
    if (error.response?.status === 401) {
      // Handle unauthorized access
      console.error('Unauthorized access');
    }
    return Promise.reject(error);
  }
);

// Dashboard API calls
export const dashboardAPI = {
  getAccountInfo: () => api.get('/dashboard/account'),
  getPositions: () => api.get('/dashboard/positions'),
  getRecentTrades: (limit = 50) => api.get(`/dashboard/recent-trades?limit=${limit}&t=${Date.now()}`),
  getUpcomingEarnings: () => api.get('/dashboard/upcoming-earnings'),
  getUpcomingEarningsWithScan: () => api.get('/dashboard/upcoming-earnings-with-scan'),
};

// Settings API calls
export const settingsAPI = {
  getSettings: () => api.get('/settings'),
  updateSetting: (key, value) => api.put('/settings', { key, value }),
};

// Cache management API calls
export const cacheAPI = {
  clearCache: () => api.post('/cache/clear'),
  clearCorruptedCache: () => api.post('/cache/clear-corrupted'),
  getCacheStats: () => api.get('/cache/stats'),
};



// Trade management API calls
export const tradesAPI = {
  getSelectedTrades: (status = null) => {
    const params = status ? `?status=${status}` : '';
    return api.get(`/trades/selected${params}`);
  },
  selectTrades: (tradeIds) => api.post('/trades/select', { trade_ids: tradeIds }),

};

// Trade selection API calls
export const tradeSelectionAPI = {
  getTradeSelections: () => api.get('/trades/selections'),
  getTradeSelectionStats: () => api.get('/trades/selections/stats'),
  selectTrade: (ticker, earningsDate, isSelected) => 
    api.post('/trades/select-stock', { ticker, earnings_date: earningsDate, is_selected: isSelected }),
};

// Scheduler API calls
export const schedulerAPI = {
  getStatus: () => api.get('/scheduler/status'),
  start: () => api.post('/scheduler/start'),
  stop: () => api.post('/scheduler/stop'),
};

// Market data API calls
export const marketAPI = {
  getCurrentPrice: (symbol) => api.get(`/market/price/${symbol}`),
  getOptionsChain: (symbol, expiration) => api.get(`/market/options/${symbol}?expiration=${expiration}`),
  calculateCalendarSpread: (symbol, shortExp, longExp, optionType = 'put') =>
    api.get(`/market/calendar-spread/${symbol}?short_exp=${shortExp}&long_exp=${longExp}&option_type=${optionType}`),
};

// Health check
export const healthAPI = {
  check: () => api.get('/health'),
};

export default api;

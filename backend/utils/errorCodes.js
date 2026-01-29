// FamilyGuard Pro - Error Codes and Messages
// Professional error handling for better user experience

const ErrorCodes = {
  // Authentication Errors (AUTH_xxx)
  AUTH_MISSING_FIELDS: {
    code: 'AUTH_001',
    status: 400,
    message: 'Please fill in all required fields',
    detail: 'Email, password, and name are required for registration'
  },
  AUTH_MISSING_LOGIN_FIELDS: {
    code: 'AUTH_002',
    status: 400,
    message: 'Please enter your email and password',
    detail: 'Both email and password are required to login'
  },
  AUTH_INVALID_EMAIL: {
    code: 'AUTH_003',
    status: 400,
    message: 'Please enter a valid email address',
    detail: 'The email format is invalid'
  },
  AUTH_WEAK_PASSWORD: {
    code: 'AUTH_004',
    status: 400,
    message: 'Password is too weak',
    detail: 'Password must be at least 6 characters long'
  },
  AUTH_EMAIL_EXISTS: {
    code: 'AUTH_005',
    status: 409,
    message: 'This email is already registered',
    detail: 'An account with this email already exists. Please login or use a different email'
  },
  AUTH_USER_NOT_FOUND: {
    code: 'AUTH_006',
    status: 401,
    message: 'Account not found',
    detail: 'No account exists with this email. Please check your email or register'
  },
  AUTH_WRONG_PASSWORD: {
    code: 'AUTH_007',
    status: 401,
    message: 'Incorrect password',
    detail: 'The password you entered is incorrect. Please try again'
  },
  AUTH_INVALID_CREDENTIALS: {
    code: 'AUTH_008',
    status: 401,
    message: 'Invalid email or password',
    detail: 'Please check your credentials and try again'
  },
  AUTH_TOKEN_MISSING: {
    code: 'AUTH_009',
    status: 401,
    message: 'Please login to continue',
    detail: 'Authentication token is missing. Please login again'
  },
  AUTH_TOKEN_EXPIRED: {
    code: 'AUTH_010',
    status: 401,
    message: 'Your session has expired',
    detail: 'Please login again to continue'
  },
  AUTH_TOKEN_INVALID: {
    code: 'AUTH_011',
    status: 401,
    message: 'Invalid session',
    detail: 'Your authentication token is invalid. Please login again'
  },
  AUTH_ACCOUNT_DISABLED: {
    code: 'AUTH_012',
    status: 403,
    message: 'Account has been disabled',
    detail: 'Your account has been disabled. Please contact support'
  },

  // Pairing Errors (PAIR_xxx)
  PAIR_CODE_REQUIRED: {
    code: 'PAIR_001',
    status: 400,
    message: 'Please enter the pairing code',
    detail: 'A valid pairing code is required to connect devices'
  },
  PAIR_CODE_INVALID: {
    code: 'PAIR_002',
    status: 400,
    message: 'Invalid pairing code',
    detail: 'The pairing code you entered is incorrect. Please check and try again'
  },
  PAIR_CODE_EXPIRED: {
    code: 'PAIR_003',
    status: 400,
    message: 'Pairing code has expired',
    detail: 'This pairing code has expired. Please generate a new code from the parent device'
  },
  PAIR_DEVICE_ID_REQUIRED: {
    code: 'PAIR_004',
    status: 400,
    message: 'Device information missing',
    detail: 'Device ID is required for pairing'
  },
  PAIR_FAILED: {
    code: 'PAIR_005',
    status: 500,
    message: 'Pairing failed',
    detail: 'Unable to pair device. Please try again'
  },
  PAIR_ALREADY_PAIRED: {
    code: 'PAIR_006',
    status: 400,
    message: 'Device already paired',
    detail: 'This device is already connected to an account'
  },

  // Device Errors (DEV_xxx)
  DEVICE_NOT_FOUND: {
    code: 'DEV_001',
    status: 404,
    message: 'Device not found',
    detail: 'The requested device could not be found'
  },
  DEVICE_OFFLINE: {
    code: 'DEV_002',
    status: 400,
    message: 'Device is offline',
    detail: 'The child device is currently offline. Please ensure it is connected to the internet'
  },
  DEVICE_UNAUTHORIZED: {
    code: 'DEV_003',
    status: 403,
    message: 'Access denied',
    detail: 'You do not have permission to access this device'
  },
  DEVICE_LIMIT_REACHED: {
    code: 'DEV_004',
    status: 400,
    message: 'Device limit reached',
    detail: 'You have reached the maximum number of devices allowed'
  },

  // Data Sync Errors (SYNC_xxx)
  SYNC_NO_DATA: {
    code: 'SYNC_001',
    status: 400,
    message: 'No data to sync',
    detail: 'No data was provided for synchronization'
  },
  SYNC_FAILED: {
    code: 'SYNC_002',
    status: 500,
    message: 'Sync failed',
    detail: 'Failed to synchronize data. Please try again'
  },
  SYNC_DEVICE_NOT_REGISTERED: {
    code: 'SYNC_003',
    status: 401,
    message: 'Device not registered',
    detail: 'This device is not registered. Please pair it with a parent account first'
  },

  // Server Errors (SRV_xxx)
  SERVER_ERROR: {
    code: 'SRV_001',
    status: 500,
    message: 'Something went wrong',
    detail: 'An unexpected error occurred. Please try again later'
  },
  DATABASE_ERROR: {
    code: 'SRV_002',
    status: 500,
    message: 'Database error',
    detail: 'Unable to connect to database. Please try again later'
  },
  RATE_LIMITED: {
    code: 'SRV_003',
    status: 429,
    message: 'Too many requests',
    detail: 'You have made too many requests. Please wait a moment and try again'
  },

  // Network Errors (NET_xxx) - For app-side use
  NETWORK_ERROR: {
    code: 'NET_001',
    status: 0,
    message: 'No internet connection',
    detail: 'Please check your internet connection and try again'
  },
  TIMEOUT_ERROR: {
    code: 'NET_002',
    status: 0,
    message: 'Connection timed out',
    detail: 'The server is taking too long to respond. Please try again'
  }
};

// Helper function to send error response
const sendError = (res, errorType, customDetail = null) => {
  const error = ErrorCodes[errorType] || ErrorCodes.SERVER_ERROR;
  return res.status(error.status).json({
    success: false,
    error: {
      code: error.code,
      message: error.message,
      detail: customDetail || error.detail
    }
  });
};

module.exports = { ErrorCodes, sendError };

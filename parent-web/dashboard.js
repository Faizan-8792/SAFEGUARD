// FamilyGuard Pro - Parent Dashboard JavaScript

const API_BASE = 'https://familyguard-backend-c2c9hkc8dwgzepdq.centralindia-01.azurewebsites.net/api';
const WS_BASE = 'wss://familyguard-backend-c2c9hkc8dwgzepdq.centralindia-01.azurewebsites.net/ws';

// Debug mode - set to false for production (only shows errors)
const DEBUG_MODE = false;

// Debug logger - only outputs when DEBUG_MODE is true
function debugLog(...args) {
  if (DEBUG_MODE) console.log(...args);
}

const TOKEN_STORAGE_KEY = 'authToken';
const LAST_PAGE_KEY = 'lastPage';
const TOKEN_SOURCE_KEY = 'authTokenSource';

const VALID_PAGES = new Set([
  'dashboard',
  'notifications',
  'calls',
  'sms',
  'gallery',
  'screenshots',
  'location',
  'apps',
  'socialmedia',
  'webhistory',
  'keystrokes',
  'settings'
]);

function isTrustedUrlTokenSource() {
  const ua = navigator.userAgent || '';
  return /Android/i.test(ua) || /wv/i.test(ua);
}

function sanitizePage(page) {
  return VALID_PAGES.has(page) ? page : 'dashboard';
}

function storeLastPage(page) {
  const safePage = sanitizePage(page);
  sessionStorage.setItem(LAST_PAGE_KEY, safePage);
  const newUrl = safePage === 'dashboard'
    ? window.location.pathname
    : `${window.location.pathname}#${safePage}`;
  window.history.replaceState({}, document.title, newUrl);
}

function getInitialPage() {
  const hashPage = (window.location.hash || '').replace('#', '').trim();
  const storedPage = sessionStorage.getItem(LAST_PAGE_KEY) || '';
  return sanitizePage(hashPage || storedPage || 'dashboard');
}

// Check for token in URL parameter (from Android app) or sessionStorage
function getAuthToken() {
  // Check URL params first (for Android WebView injection)
  const urlParams = new URLSearchParams(window.location.search);
  const urlToken = urlParams.get('token');
  if (urlToken) {
    if (isTrustedUrlTokenSource()) {
      sessionStorage.setItem(TOKEN_STORAGE_KEY, urlToken);
      sessionStorage.setItem(TOKEN_SOURCE_KEY, 'url');
    } else {
      console.warn('Ignoring auth token from URL in non-webview context');
    }
    // Remove token from URL without reload
    window.history.replaceState({}, document.title, window.location.pathname + window.location.hash);
  }

  const sessionToken = sessionStorage.getItem(TOKEN_STORAGE_KEY);
  if (sessionToken) return sessionToken;

  // Migrate legacy localStorage token to session-only storage
  const legacyToken = localStorage.getItem(TOKEN_STORAGE_KEY);
  if (legacyToken) {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, legacyToken);
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    return legacyToken;
  }

  return null;
}

let authToken = getAuthToken();
let currentUser = null;
let devices = [];
let selectedDevice = null;
let streamSocket = null;

// Helper function to get device ID (works with both 'id' and '_id' formats)
function getDeviceId(device) {
  return device?.id || device?._id;
}

// DOM Elements
const loginPage = document.getElementById('loginPage');
const registerPage = document.getElementById('registerPage');
const dashboardPage = document.getElementById('dashboardPage');
const notificationsPage = document.getElementById('notificationsPage');
const callsPage = document.getElementById('callsPage');
const locationPage = document.getElementById('locationPage');
const appsPage = document.getElementById('appsPage');
const settingsPage = document.getElementById('settingsPage');
const deviceSelector = document.getElementById('deviceSelector');
const sidebar = document.getElementById('sidebar');

// Auto-refresh interval (30 seconds)
let autoRefreshInterval = null;
const AUTO_REFRESH_MS = 30000;

// === REAL-TIME SYNC WEBSOCKET ===
let syncSocket = null;
let syncReconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 10;

// Toast notification system
function showToast(message, type = 'info', duration = 4000) {
  // Remove existing toast
  const existingToast = document.querySelector('.toast-notification');
  if (existingToast) existingToast.remove();
  
  const toast = document.createElement('div');
  toast.className = `toast-notification toast-${type}`;
  
  const icons = {
    success: 'fa-check-circle',
    error: 'fa-exclamation-circle',
    warning: 'fa-exclamation-triangle',
    info: 'fa-info-circle'
  };
  
  toast.innerHTML = `
    <i class="fas ${icons[type] || icons.info}"></i>
    <span>${message}</span>
  `;
  
  document.body.appendChild(toast);
  
  // Trigger animation
  setTimeout(() => toast.classList.add('show'), 10);
  
  // Auto remove
  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => toast.remove(), 300);
  }, duration);
}

// Initialize
document.addEventListener('DOMContentLoaded', () => {
  authToken = getAuthToken();
  
  if (authToken) {
    loadUserData();
  } else {
    showLoginPage();
  }
  
  setupEventListeners();
  setupWebHistoryListeners();
  
  // Handle hash-based navigation for page refresh
  window.addEventListener('hashchange', (e) => {
    if (authToken) {
      const page = window.location.hash.replace('#', '').trim();
      if (page && VALID_PAGES.has(page)) {
        navigateTo(page);
      }
    }
  });
});

// Start auto-refresh
function startAutoRefresh() {
  if (autoRefreshInterval) clearInterval(autoRefreshInterval);
  autoRefreshInterval = setInterval(() => {
    if (selectedDevice && !document.hidden) {
      debugLog('Auto-refreshing data...');
      refreshDataSilent();
    }
  }, AUTO_REFRESH_MS);
}

// Stop auto-refresh
function stopAutoRefresh() {
  if (autoRefreshInterval) {
    clearInterval(autoRefreshInterval);
    autoRefreshInterval = null;
  }
}

// Session Validation - verify authentication periodically
let sessionValidationInterval = null;
const SESSION_VALIDATION_INTERVAL = 5 * 60 * 1000; // 5 minutes

function startSessionValidation() {
  if (sessionValidationInterval) clearInterval(sessionValidationInterval);
  
  sessionValidationInterval = setInterval(async () => {
    if (!authToken) return;
    
    try {
      // Verify token is still valid with server
      const currentToken = sessionStorage.getItem(TOKEN_STORAGE_KEY);
      if (!currentToken || currentToken !== authToken) {
        console.warn('Session token mismatch');
        handleLogout();
        return;
      }
      
      // Quick validation call to ensure auth is still valid
      await api('/auth/me');
      debugLog('[Session] Token validation successful');
    } catch (error) {
      console.warn('[Session] Validation failed:', error.message);
      if (error.status === 401) {
        handleLogout();
      }
    }
  }, SESSION_VALIDATION_INTERVAL);
}

function stopSessionValidation() {
  if (sessionValidationInterval) {
    clearInterval(sessionValidationInterval);
    sessionValidationInterval = null;
  }
}

// === REAL-TIME SYNC FUNCTIONS ===
// Connect to WebSocket for instant notifications from child devices

function connectRealtimeSync() {
  if (!authToken || !currentUser) {
    debugLog('[Sync] Not authenticated - skipping sync connection');
    return;
  }
  
  // Close existing connection
  if (syncSocket && syncSocket.readyState === WebSocket.OPEN) {
    syncSocket.close();
  }
  
  const userId = currentUser._id || currentUser.id;
  const wsUrl = `${WS_BASE}?role=sync&device_type=parent&device_id=${userId}`;
  
  debugLog('[Sync] Connecting to real-time sync:', wsUrl);
  
  try {
    syncSocket = new WebSocket(wsUrl);
    
    syncSocket.onopen = () => {
      debugLog('[Sync] Connected to real-time sync');
      syncReconnectAttempts = 0;
      
      // Authenticate
      syncSocket.send(JSON.stringify({
        type: 'auth',
        device_type: 'parent',
        user_id: userId,
        parent_id: userId,
        timestamp: Date.now()
      }));
    };
    
    syncSocket.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        handleRealtimeMessage(data);
      } catch (e) {
        console.error('[Sync] Error parsing message:', e);
      }
    };
    
    syncSocket.onclose = () => {
      debugLog('[Sync] Disconnected from real-time sync');
      scheduleReconnect();
    };
    
    syncSocket.onerror = (error) => {
      console.error('[Sync] WebSocket error:', error);
    };
    
  } catch (error) {
    console.error('[Sync] Error connecting:', error);
    scheduleReconnect();
  }
}

function scheduleReconnect() {
  if (syncReconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
    console.warn('[Sync] Max reconnect attempts reached');
    return;
  }
  
  syncReconnectAttempts++;
  const delay = Math.min(5000 * syncReconnectAttempts, 60000);
  
  debugLog(`[Sync] Reconnecting in ${delay}ms (attempt ${syncReconnectAttempts})`);
  
  setTimeout(() => {
    if (authToken && currentUser) {
      connectRealtimeSync();
    }
  }, delay);
}

function handleRealtimeMessage(data) {
  const type = data.type;
  debugLog('[Sync] Received:', type);
  
  switch (type) {
    case 'auth_success':
      debugLog('[Sync] Authenticated - online devices:', data.online_devices);
      // Update device online status
      if (data.online_devices) {
        updateDevicesOnlineStatus(data.online_devices);
      }
      break;
      
    case 'child_notification':
      // INSTANT notification from child device!
      debugLog('[Sync] Real-time notification:', data.notification);
      handleInstantNotification(data.device_id, data.notification);
      break;
      
    case 'child_notification_batch':
      // BATCHED notifications from child (battery optimization)
      debugLog('[Sync] Batch of', data.count, 'notifications');
      if (Array.isArray(data.notifications)) {
        for (const notification of data.notifications) {
          handleInstantNotification(data.device_id, notification);
        }
      }
      break;
      
    case 'child_alert':
      // Critical alert from child device (low battery, accessibility disabled, etc.)
      console.warn('[Sync] Child alert:', data.alert_type, data.message);
      handleChildAlert(data.device_id, data.alert_type, data.message, data.health);
      break;
      
    case 'location_update':
      // INSTANT location update
      debugLog('[Sync] Real-time location:', data.location);
      handleInstantLocation(data.device_id, data.location);
      break;
    
    case 'social_message':
      // INSTANT social media message from child device
      debugLog('[Sync] Real-time social message:', data.message);
      if (typeof handleRealtimeSocialMessage === 'function') {
        handleRealtimeSocialMessage(data.message);
      }
      break;
      
    case 'device_online':
      debugLog('[Sync] Device online:', data.device_id);
      updateDeviceStatus(data.device_id, true);
      break;
      
    case 'device_offline':
      debugLog('[Sync] Device offline:', data.device_id);
      updateDeviceStatus(data.device_id, false);
      break;
      
    case 'device_paired':
      // New device paired - show success and refresh
      debugLog('[Sync] Device paired:', data.device);
      handleDevicePaired(data.device);
      break;
      
    case 'permission_changed':
      // Permission status changed on child device
      debugLog('[Sync] Permission changed:', data.permission, data.status);
      handlePermissionChanged(data.device_id, data.permission, data.status);
      break;
      
    case 'command_sent':
      debugLog('[Sync] Command sent successfully');
      break;
      
    case 'command_sent_fcm':
      debugLog('[Sync] Command sent via FCM (device offline)');
      showToast('Command sent (device will receive when online)', 'info');
      break;
      
    case 'command_failed':
      console.error('[Sync] Command failed:', data.error);
      showToast(`Command failed: ${data.error}`, 'error');
      break;
      
    case 'ping':
      // Respond to server ping
      if (syncSocket && syncSocket.readyState === WebSocket.OPEN) {
        syncSocket.send(JSON.stringify({
          type: 'pong',
          timestamp: Date.now()
        }));
      }
      break;
  }
}

/**
 * Handle critical alerts from child device (battery, accessibility, etc.)
 */
function handleChildAlert(deviceId, alertType, message, health) {
  // Different icons/colors for different alert types
  const alertStyles = {
    'low_battery': { icon: '🔋', color: 'warning', priority: 'high' },
    'accessibility_disabled': { icon: '⚠️', color: 'error', priority: 'critical' },
    'connection_issues': { icon: '📶', color: 'warning', priority: 'medium' }
  };
  
  const style = alertStyles[alertType] || { icon: '⚠️', color: 'warning', priority: 'medium' };
  
  // Show desktop notification for critical alerts
  if (Notification.permission === 'granted') {
    new Notification(`${style.icon} Child Device Alert`, {
      body: message,
      icon: './icon.png',
      tag: `alert-${alertType}-${deviceId}`,
      requireInteraction: style.priority === 'critical'
    });
  }
  
  // Show in-app alert
  showToast(`${style.icon} ${message}`, style.color, 10000);
  
  // Log to console for debugging
  console.warn(`[Alert] ${alertType} on ${deviceId}:`, health);
  
  // If accessibility disabled, show prominent warning
  if (alertType === 'accessibility_disabled') {
    showAccessibilityWarning(deviceId);
  }
}

/**
 * Show prominent warning when accessibility service is disabled
 */
function showAccessibilityWarning(deviceId) {
  // Check if warning already shown
  if (document.getElementById('accessibility-warning')) return;
  
  const warning = document.createElement('div');
  warning.id = 'accessibility-warning';
  warning.style.cssText = `
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    background: linear-gradient(135deg, #dc2626 0%, #991b1b 100%);
    color: white;
    padding: 16px 20px;
    z-index: 9999;
    display: flex;
    align-items: center;
    justify-content: space-between;
    box-shadow: 0 4px 20px rgba(220, 38, 38, 0.4);
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    animation: slideDown 0.3s ease-out;
  `;
  
  warning.innerHTML = `
    <style>
      @keyframes slideDown {
        from { transform: translateY(-100%); opacity: 0; }
        to { transform: translateY(0); opacity: 1; }
      }
      #accessibility-warning .warning-content {
        display: flex;
        align-items: center;
        gap: 12px;
      }
      #accessibility-warning .warning-icon {
        font-size: 28px;
        filter: drop-shadow(0 2px 4px rgba(0,0,0,0.2));
      }
      #accessibility-warning .warning-text h4 {
        margin: 0 0 4px 0;
        font-size: 16px;
        font-weight: 600;
      }
      #accessibility-warning .warning-text p {
        margin: 0;
        font-size: 13px;
        opacity: 0.9;
      }
      #accessibility-warning .close-btn {
        background: rgba(255,255,255,0.2);
        border: none;
        color: white;
        width: 32px;
        height: 32px;
        border-radius: 50%;
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
        transition: background 0.2s;
      }
      #accessibility-warning .close-btn:hover {
        background: rgba(255,255,255,0.3);
      }
    </style>
    <div class="warning-content">
      <span class="warning-icon">⚠️</span>
      <div class="warning-text">
        <h4>Accessibility Service Disabled</h4>
        <p>Keystroke logging and app monitoring are not working. The child may have disabled the service.</p>
      </div>
    </div>
    <button class="close-btn" onclick="this.parentElement.remove()">
      <svg width="16" height="16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>
      </svg>
    </button>
  `;
  document.body.prepend(warning);
}

function handleInstantNotification(deviceId, notification) {
  // Play notification sound
  try {
    const audio = new Audio('data:audio/wav;base64,UklGRnoGAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQoGAACBhYqFbF1fdJivrJBhNjVgodDbq2EcBj+a2teleP//');
    audio.volume = 0.3;
    audio.play().catch(() => {});
  } catch (e) {}
  
  // Show desktop notification if permitted
  if (Notification.permission === 'granted') {
    new Notification(`${notification.appName || 'Notification'}`, {
      body: `${notification.title || ''}\n${notification.text || ''}`,
      icon: './icon.png',
      tag: `notif-${Date.now()}`
    });
  }
  
  // Show in-app toast
  showToast(`📱 ${notification.appName}: ${notification.title || notification.text}`, 'info', 5000);
  
  // If on notifications page, refresh list
  const notifPage = document.getElementById('notificationsPage');
  if (notifPage && !notifPage.classList.contains('hidden')) {
    loadNotifications();
  }
  
  // Update dashboard notification count
  updateDashboardNotificationBadge();
}

function handleInstantLocation(deviceId, location) {
  // Update map if on location page
  if (selectedDevice && getDeviceId(selectedDevice) === deviceId) {
    const locationPage = document.getElementById('locationPage');
    if (locationPage && !locationPage.classList.contains('hidden')) {
      updateMapWithLocation(location);
    }
  }
}

function updateDeviceStatus(deviceId, isOnline) {
  // Find device and update status
  const device = devices.find(d => getDeviceId(d) === deviceId || d.deviceId === deviceId);
  if (device) {
    device.isOnline = isOnline;
    
    // Update UI if this is the selected device
    if (selectedDevice && (getDeviceId(selectedDevice) === deviceId || selectedDevice.deviceId === deviceId)) {
      const statusEl = document.getElementById('deviceStatus');
      if (statusEl) {
        statusEl.innerHTML = isOnline 
          ? '<span class="status-online"><i class="fas fa-circle"></i> Online</span>'
          : '<span class="status-offline"><i class="fas fa-circle"></i> Offline</span>';
      }
    }
  }
  
  // Show toast
  showToast(isOnline ? '🟢 Device is online' : '🔴 Device went offline', isOnline ? 'success' : 'warning', 3000);
}

function updateDevicesOnlineStatus(onlineDeviceIds) {
  devices.forEach(device => {
    const id = device.deviceId || getDeviceId(device);
    device.isOnline = onlineDeviceIds.includes(id);
  });
}

function updateDashboardNotificationBadge() {
  // Increment unread count badge if exists
  const badge = document.getElementById('notifBadge');
  if (badge) {
    const count = parseInt(badge.textContent || '0') + 1;
    badge.textContent = count;
    badge.style.display = count > 0 ? 'flex' : 'none';
  }
}

// Mark notifications as read and reset badge
function markNotificationsAsRead() {
  const badge = document.getElementById('notifBadge');
  if (badge) {
    badge.textContent = '0';
    badge.style.display = 'none';
  }
  
  // Optionally call API to mark as read on server
  if (selectedDevice) {
    api(`/devices/${getDeviceId(selectedDevice)}/notifications/mark-read`, {
      method: 'POST'
    }).catch(err => console.log('Mark read API not available:', err.message));
  }
}

// Handle new device paired via WebSocket
function handleDevicePaired(device) {
  // Add device to list
  devices.push(device);
  
  // Update device selector dropdown
  const option = document.createElement('option');
  option.value = getDeviceId(device);
  option.textContent = device.alias || device.name || 'Unknown Device';
  deviceSelector.appendChild(option);
  
  // Hide pairing modal
  hidePairingModal();
  
  // Show success message
  showToast('✅ Device connected successfully!', 'success', 5000);
  
  // Show desktop notification
  if (Notification.permission === 'granted') {
    new Notification('Device Paired', {
      body: `${device.name || 'New device'} has been connected successfully!`,
      icon: './icon.png'
    });
  }
  
  // Select the new device and go to dashboard
  selectedDevice = device;
  deviceSelector.value = getDeviceId(device);
  hideNoDevicesState();
  navigateTo('dashboard');
}

// Handle permission status change from child device
function handlePermissionChanged(deviceId, permission, status) {
  // Only update if it's for the selected device
  if (!selectedDevice || getDeviceId(selectedDevice) !== deviceId) return;
  
  // Find the permission item element
  const permItem = document.querySelector(`.permission-item[data-permission="${permission}"]`);
  if (!permItem) return;
  
  const statusEl = permItem.querySelector('.permission-status');
  if (statusEl) {
    // Update classes
    statusEl.classList.remove('granted', 'denied', 'pending');
    statusEl.classList.add(status);
    statusEl.textContent = status.charAt(0).toUpperCase() + status.slice(1);
  }
  
  // Update permission item class
  permItem.classList.remove('granted', 'denied', 'pending');
  permItem.classList.add(status);
  
  // Show toast notification
  const icon = status === 'granted' ? '✅' : '❌';
  showToast(`${icon} ${permission} permission ${status}`, status === 'granted' ? 'success' : 'warning', 4000);
  
  // If it's a critical permission denied, show warning
  if (status === 'denied' && ['accessibility', 'deviceAdmin', 'notifications'].includes(permission)) {
    showToast(`⚠️ Critical permission "${permission}" was revoked! Some features may not work.`, 'error', 8000);
  }
}

function updateMapWithLocation(location) {
  // Update the map marker if map is initialized
  if (window.map && location) {
    const latlng = [location.latitude, location.longitude];
    if (window.deviceMarker) {
      window.deviceMarker.setLatLng(latlng);
    } else {
      window.deviceMarker = L.marker(latlng).addTo(window.map);
    }
    window.map.setView(latlng, 15);
  }
}

// Send command via WebSocket (real-time) with fallback to REST API
function sendCommandViaSync(deviceId, command, params = {}) {
  if (syncSocket && syncSocket.readyState === WebSocket.OPEN) {
    syncSocket.send(JSON.stringify({
      type: 'command',
      target_device_id: deviceId,
      command: command,
      params: params,
      message_id: Date.now(),
      timestamp: Date.now()
    }));
    return true;
  }
  return false;
}

function disconnectRealtimeSync() {
  if (syncSocket) {
    syncSocket.close();
    syncSocket = null;
  }
}

// Refresh data without alerts
async function refreshDataSilent() {
  if (!selectedDevice) return;
  
  try {
    await loadDashboard();
  } catch (error) {
    console.error('Silent refresh failed:', error);
  }
}

// Event Listeners
function setupEventListeners() {
  try {
    // Login form
    document.getElementById('loginForm')?.addEventListener('submit', handleLogin);
    
    // MOBILE FIX: Also add click handler to login button for WebView
    const loginBtn = document.querySelector('#loginForm button[type="submit"]');
    if (loginBtn) {
      loginBtn.addEventListener('click', (e) => {
        e.preventDefault();
        const form = document.getElementById('loginForm');
        if (form) {
          form.dispatchEvent(new Event('submit', { cancelable: true }));
        }
      });
    }
  
    // Register form
    document.getElementById('registerForm')?.addEventListener('submit', handleRegister);
    
    // MOBILE FIX: Also add click handler to register button
    const registerBtn = document.querySelector('#registerForm button[type="submit"]');
    if (registerBtn) {
      registerBtn.addEventListener('click', (e) => {
        e.preventDefault();
        const form = document.getElementById('registerForm');
        if (form) {
          form.dispatchEvent(new Event('submit', { cancelable: true }));
        }
      });
    }
  
  // Show register page
  document.getElementById('showRegister')?.addEventListener('click', (e) => {
    e.preventDefault();
    showRegisterPage();
  });
  
  // Show login page from register
  document.getElementById('showLogin')?.addEventListener('click', (e) => {
    e.preventDefault();
    showLoginPage();
  });
  
  // Navigation
  document.querySelectorAll('.nav-item').forEach(item => {
    item.addEventListener('click', () => {
      const page = item.dataset.page;
      navigateTo(page);
      // Close sidebar on mobile when nav item clicked
      if (window.innerWidth <= 768) {
        sidebar.classList.remove('open');
      }
    });
  });
  
  // View all links
  document.querySelectorAll('.view-all').forEach(link => {
    link.addEventListener('click', (e) => {
      e.preventDefault();
      const page = link.dataset.page;
      navigateTo(page);
      // Close sidebar on mobile
      if (window.innerWidth <= 768) {
        sidebar.classList.remove('open');
      }
    });
  });
  
  // Mobile menu
  document.getElementById('btnMenu')?.addEventListener('click', (e) => {
    e.stopPropagation();
    sidebar.classList.toggle('open');
  });
  
  // Close sidebar when clicking outside
  document.addEventListener('click', (e) => {
    if (sidebar.classList.contains('open') && 
        !sidebar.contains(e.target) && 
        e.target.id !== 'btnMenu') {
      sidebar.classList.remove('open');
    }
  });
  
  // Prevent clicks inside sidebar from closing it
  sidebar.addEventListener('click', (e) => {
    e.stopPropagation();
  });
  
  // Logout
  document.getElementById('btnLogout')?.addEventListener('click', handleLogout);
  
  // Device selector
  deviceSelector.addEventListener('change', handleDeviceChange);
  
  // Refresh button
  document.getElementById('btnRefresh')?.addEventListener('click', refreshData);
  
  // Add device
  document.getElementById('btnAddDevice')?.addEventListener('click', showPairingModal);
  document.getElementById('closePairing')?.addEventListener('click', hidePairingModal);
  document.getElementById('btnNewCode')?.addEventListener('click', generatePairingCode);
  
  // Device management
  document.getElementById('btnRenameDevice')?.addEventListener('click', showRenameDeviceDialog);
  document.getElementById('btnManageDevices')?.addEventListener('click', showDeviceManageModal);
  document.getElementById('closeDeviceManage')?.addEventListener('click', hideDeviceManageModal);
  document.getElementById('btnCancelDeviceOrder')?.addEventListener('click', hideDeviceManageModal);
  document.getElementById('btnSaveDeviceOrder')?.addEventListener('click', saveDeviceOrder);
  
  // Quick actions - Use WebRTC for streaming
  document.getElementById('btnScreenMirror')?.addEventListener('click', () => startWebRTCStream('screen'));
  document.getElementById('btnCamera')?.addEventListener('click', () => startWebRTCStream('camera'));
  document.getElementById('btnLiveListen')?.addEventListener('click', () => startWebRTCStream('audio'));
  document.getElementById('btnViewLocation')?.addEventListener('click', () => navigateTo('location'));
  document.getElementById('btnOpenApp')?.addEventListener('click', () => sendCommand('show_app'));
  document.getElementById('btnDeleteCallLogs')?.addEventListener('click', deleteCallLogs);
  document.getElementById('btnLockDevice')?.addEventListener('click', () => sendCommand('lock_device'));
  document.getElementById('btnRingDevice')?.addEventListener('click', ringDevice);
  document.getElementById('btnSyncNow')?.addEventListener('click', syncNow);
  
  // Screenshot
  document.getElementById('btnScreenshot')?.addEventListener('click', captureScreenshot);
  document.getElementById('btnRefreshScreenshot')?.addEventListener('click', loadLatestScreenshot);
  document.getElementById('btnCaptureScreenshot')?.addEventListener('click', captureScreenshotFromPage);
  
  // Call history
  document.getElementById('btnDeleteAllCalls')?.addEventListener('click', deleteCallLogs);
  
  // Notifications - Delete All
  document.getElementById('btnDeleteAllNotifications')?.addEventListener('click', deleteAllNotifications);
  
  // Location
  document.getElementById('btnRefreshLocation')?.addEventListener('click', loadLocation);
  document.getElementById('btnLocationHistory')?.addEventListener('click', () => showToast('Location history coming soon', 'info'));
  document.getElementById('btnZoomIn')?.addEventListener('click', mapZoomIn);
  document.getElementById('btnZoomOut')?.addEventListener('click', mapZoomOut);
  document.getElementById('btnCenterMap')?.addEventListener('click', mapCenter);
  
  // App Usage - Manage Blocked Apps
  document.getElementById('btnManageBlocked')?.addEventListener('click', showBlockedAppsModal);
  
  // Gallery
  document.getElementById('btnSyncPhotos')?.addEventListener('click', syncPhotos);
  document.getElementById('btnRefreshGallery')?.addEventListener('click', refreshGallery);
  document.getElementById('btnDeleteAllPhotos')?.addEventListener('click', deleteAllPhotos);
  document.getElementById('closePhotoModal')?.addEventListener('click', closePhotoModal);
  document.getElementById('btnDownloadPhoto')?.addEventListener('click', downloadCurrentPhoto);
  document.getElementById('btnGoFilter')?.addEventListener('click', applyPhotoDateFilter);
  document.getElementById('btnClearDateFilter')?.addEventListener('click', clearPhotoDateFilter);
  document.getElementById('btnLoadMorePhotos')?.addEventListener('click', loadMorePhotos);
  
  // Date Range Picker
  document.getElementById('dateRangeInput')?.addEventListener('click', openDateRangePicker);
  document.getElementById('closeDateRangeModal')?.addEventListener('click', closeDateRangePicker);
  document.getElementById('btnCancelDateRange')?.addEventListener('click', closeDateRangePicker);
  document.getElementById('btnApplyDateRange')?.addEventListener('click', applyDateRange);
  document.getElementById('prevMonth')?.addEventListener('click', () => navigateCalendar(-1));
  document.getElementById('nextMonth')?.addEventListener('click', () => navigateCalendar(1));
  
  // Date Presets
  document.querySelectorAll('.preset-btn[data-preset]').forEach(btn => {
    btn.addEventListener('click', () => selectDatePreset(btn.dataset.preset));
  });
  
  // Album tabs
  document.querySelectorAll('.album-tab[data-source]').forEach(tab => {
    tab.addEventListener('click', () => {
      selectAlbumTab(tab.dataset.source);
    });
  });
  
  // Stream modal
  document.getElementById('closeStream')?.addEventListener('click', stopStream);
  document.getElementById('btnStopStream')?.addEventListener('click', stopStream);
  document.getElementById('btnSwitchCamera')?.addEventListener('click', () => sendCommand('switch_camera'));
  document.getElementById('btnMuteStream')?.addEventListener('click', toggleStreamMute);
  document.getElementById('btnFullscreenStream')?.addEventListener('click', toggleStreamFullscreen);
  document.getElementById('btnPipMode')?.addEventListener('click', togglePictureInPicture);
  
  // Request all permissions button
  document.getElementById('btnRequestAllPermissions')?.addEventListener('click', requestAllMissingPermissions);
  
  // Sync status banner buttons
  document.getElementById('btnSyncAllNow')?.addEventListener('click', syncNow);
  document.getElementById('btnViewSyncLogs')?.addEventListener('click', () => showToast('Sync logs coming soon', 'info'));
  
  // Filter chips for notifications
  document.querySelectorAll('.chip[data-filter]').forEach(chip => {
    chip.addEventListener('click', () => {
      document.querySelectorAll('.chip[data-filter]').forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      loadNotifications(chip.dataset.filter);
    });
  });
  
  // Filter chips for SMS
  document.querySelectorAll('.chip[data-sms-filter]').forEach(chip => {
    chip.addEventListener('click', () => {
      document.querySelectorAll('.chip[data-sms-filter]').forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      loadSmsMessages(chip.dataset.smsFilter);
    });
  });
  
  // Load more SMS
  document.getElementById('loadMoreSms')?.addEventListener('click', () => {
    loadSmsMessages(currentSmsFilter, smsPage + 1);
  });
  
  // Load more Notifications
  document.getElementById('loadMoreNotif')?.addEventListener('click', () => {
    notificationsCurrentPage++;
    loadNotifications(notificationsFilter, true);
  });
  
  // Settings toggles
  document.querySelectorAll('.settings-section input[type="checkbox"]').forEach(toggle => {
    toggle.addEventListener('change', saveSettings);
  });
  
  // Remove device
  document.getElementById('btnRemoveDevice')?.addEventListener('click', removeDevice);
  
  // Uninstall app from device
  document.getElementById('btnUninstallApp')?.addEventListener('click', uninstallApp);
  
  // App Disguise Mode
  document.getElementById('btnApplyDisguise')?.addEventListener('click', applyDisguiseMode);
  
  // Make disguise options clickable
  document.querySelectorAll('.disguise-option').forEach(option => {
    option.addEventListener('click', () => {
      const radio = option.querySelector('input[type="radio"]');
      radio.checked = true;
    });
  });
  
  // === MOBILE TOUCH SUPPORT ===
  // Ensure buttons work correctly on mobile WebView
  const isMobile = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
  
  if (isMobile) {
    // Add touchend fallback for all buttons to ensure they trigger on mobile
    document.querySelectorAll('button, .btn-primary, .btn-secondary, .btn-danger, .nav-item').forEach(el => {
      el.addEventListener('touchend', (e) => {
        // Small delay to allow the tap to register
        setTimeout(() => {
          e.target.click();
        }, 10);
      }, { passive: true });
    });
    
    // Make sure form inputs can receive focus on mobile
    document.querySelectorAll('input, select, textarea').forEach(el => {
      el.addEventListener('touchstart', (e) => {
        el.focus();
      }, { passive: true });
    });
    
    console.log('[Mobile] Touch event handlers initialized');
  }
  
  } catch (error) {
    console.error('Error setting up event listeners:', error);
  }
}

// API Functions
// Public endpoints that don't require authentication
const PUBLIC_ENDPOINTS = ['/auth/login', '/auth/register', '/auth/forgot-password'];

async function api(endpoint, options = {}) {
  // Skip auth check for public endpoints (login, register, etc.)
  const isPublicEndpoint = PUBLIC_ENDPOINTS.some(pub => endpoint.startsWith(pub));
  
  if (!isPublicEndpoint) {
    // Security: Check authentication token before making request
    const currentToken = sessionStorage.getItem(TOKEN_STORAGE_KEY);
    if (!currentToken || currentToken !== authToken) {
      console.warn('Auth token mismatch or missing - forcing logout');
      handleLogout();
      throw new Error('Authentication required');
    }
  }

  const headers = {
    'Content-Type': 'application/json',
    ...options.headers
  };
  
  if (authToken) {
    headers['Authorization'] = `Bearer ${authToken}`;
  }
  
  try {
    const response = await fetch(`${API_BASE}${endpoint}`, {
      ...options,
      headers
    });
    
    const data = await response.json();
    
    if (!response.ok) {
      // Handle 401 Unauthorized - but NOT for login/register endpoints (they return 401 for wrong credentials)
      if (response.status === 401 && !endpoint.includes('/auth/login') && !endpoint.includes('/auth/register')) {
        console.warn('Token expired or unauthorized');
        handleLogout();
        throw new Error('Session expired. Please login again.');
      }
      
      // Handle 429 Too Many Requests - don't logout, propagate error with status
      if (response.status === 429) {
        const error = new Error('Too many requests, please try again later');
        error.status = 429;
        throw error;
      }
      
      // Extract error message properly - handle both string and object errors
      let errorMessage = 'Request failed';
      if (typeof data.error === 'string') {
        errorMessage = data.error;
      } else if (data.error && data.error.message) {
        errorMessage = data.error.message;
      } else if (data.message) {
        errorMessage = data.message;
      } else if (typeof data === 'string') {
        errorMessage = data;
      }
      
      const error = new Error(errorMessage);
      error.code = data.code || data.error?.code;
      error.status = response.status;
      error.hint = data.hint;
      throw error;
    }
    
    return data;
  } catch (error) {
    console.error('API Error:', error.message || error);
    throw error;
  }
}

// Auth Functions
async function handleLogin(e) {
  if (e) e.preventDefault();
  
  console.log('[Login] Starting login...');
  
  const emailInput = document.getElementById('email');
  const passwordInput = document.getElementById('password');
  
  if (!emailInput || !passwordInput) {
    console.error('[Login] Form inputs not found');
    alert('Error: Form elements not found. Please refresh the page.');
    return;
  }
  
  const email = emailInput.value.trim();
  const password = passwordInput.value;
  
  if (!email || !password) {
    alert('Please enter both email and password');
    return;
  }
  
  console.log('[Login] Attempting login for:', email);
  
  try {
    const data = await api('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password })
    });
    
    console.log('[Login] Login successful');
    authToken = data.token;
    sessionStorage.setItem(TOKEN_STORAGE_KEY, authToken);
    currentUser = data.user;
    
    loadUserData();
  } catch (error) {
    alert('Login failed: ' + error.message);
  }
}

function handleLogout() {
  authToken = null;
  sessionStorage.removeItem(TOKEN_STORAGE_KEY);
  sessionStorage.removeItem(LAST_PAGE_KEY);
  sessionStorage.removeItem(TOKEN_SOURCE_KEY);
  localStorage.removeItem(TOKEN_STORAGE_KEY);
  currentUser = null;
  devices = [];
  selectedDevice = null;
  
  // Disconnect from real-time sync
  disconnectRealtimeSync();
  
  // Stop auto-refresh and session validation
  stopAutoRefresh();
  stopSessionValidation();
  
  showLoginPage();
}

async function loadUserData() {
  try {
    // Verify token is still valid with server
    const data = await api('/auth/me');
    
    if (!data || !data.user) {
      throw new Error('Invalid auth response');
    }
    
    currentUser = data.user;
    document.getElementById('userName').textContent = currentUser.name;
    
    await loadDevices();
    
    // Connect to real-time sync WebSocket for instant notifications
    connectRealtimeSync();
    
    // Request browser notification permission
    if (Notification.permission === 'default') {
      Notification.requestPermission();
    }
    
    if (devices.length > 0) {
      selectedDevice = devices[0];
      deviceSelector.value = getDeviceId(selectedDevice);
      showDashboard();
    } else {
      // No devices - show unpaired state
      showDashboard();
      showNoDevicesState();
    }
  } catch (error) {
    console.error('Failed to load user data:', error);
    // Only logout on auth errors (401), not on rate limit (429) or network errors
    if (error.status === 401) {
      handleLogout();
    } else if (error.status === 429) {
      // Rate limited - show dashboard anyway if we have cached user info
      showToast('Too many requests. Please wait a moment.', 'warning');
      showDashboard();
    } else {
      // Network error - try to show dashboard anyway
      showToast('Connection error. Some data may be stale.', 'warning');
      showDashboard();
    }
  }
}

// Show state when no devices are paired
function showNoDevicesState() {
  const mainContent = document.querySelector('.main-content');
  if (!mainContent) return;
  
  // Hide device selector and show helpful message
  document.getElementById('deviceStatusCard').style.display = 'none';
  
  const noDeviceMessage = document.createElement('div');
  noDeviceMessage.id = 'noDeviceState';
  noDeviceMessage.innerHTML = `
    <div class="no-device-container">
      <div class="no-device-icon">
        <i class="fas fa-mobile-alt"></i>
        <i class="fas fa-plus" style="position: absolute; right: -5px; bottom: -5px; font-size: 20px; background: var(--primary); border-radius: 50%; padding: 4px;"></i>
      </div>
      <h2>No Devices Paired</h2>
      <p>You haven't paired any child device yet.</p>
      <p class="hint">To get started, install FamilyGuard on your child's device and enter the pairing code below.</p>
      <button class="btn btn-primary btn-lg" onclick="showPairingModal()">
        <i class="fas fa-link"></i> Pair a Device
      </button>
    </div>
  `;
  
  // Insert after the header in dashboard page
  const dashboardPage = document.getElementById('dashboardPage');
  const existingNoDevice = document.getElementById('noDeviceState');
  if (existingNoDevice) existingNoDevice.remove();
  
  if (dashboardPage) {
    dashboardPage.insertBefore(noDeviceMessage, dashboardPage.firstChild);
  }
}

// Hide no device state when device is selected
function hideNoDevicesState() {
  const noDeviceState = document.getElementById('noDeviceState');
  if (noDeviceState) noDeviceState.remove();
}

async function loadDevices() {
  try {
    const data = await api('/devices');
    devices = data.devices || [];
    
    // Sort devices by order if available (for drag reorder feature)
    devices.sort((a, b) => (a.order || 0) - (b.order || 0));
    
    // Update device selector
    renderDeviceSelector();
    
    if (devices.length === 0) {
      debugLog('No devices found for this user');
    }
    
    if (selectedDevice) {
      deviceSelector.value = getDeviceId(selectedDevice);
    }
  } catch (error) {
    console.error('Failed to load devices:', error);
    devices = [];
  }
}

// Render device selector dropdown with drag-reorder support
function renderDeviceSelector() {
  // Render native select (for form compatibility)
  deviceSelector.innerHTML = '<option value="">Select Device</option>';
  
  devices.forEach((device, index) => {
    const option = document.createElement('option');
    option.value = getDeviceId(device);
    option.textContent = device.alias || device.name || 'Unknown Device';
    option.dataset.index = index;
    deviceSelector.appendChild(option);
  });
  
  // Create/update custom draggable dropdown overlay
  createDraggableDeviceDropdown();
}

// Custom draggable device dropdown
function createDraggableDeviceDropdown() {
  let dropdown = document.getElementById('deviceDropdownCustom');
  
  if (!dropdown) {
    dropdown = document.createElement('div');
    dropdown.id = 'deviceDropdownCustom';
    dropdown.className = 'device-dropdown-custom hidden';
    document.body.appendChild(dropdown);
    
    // Close on outside click
    document.addEventListener('click', (e) => {
      if (!dropdown.contains(e.target) && e.target !== deviceSelector) {
        dropdown.classList.add('hidden');
      }
    });
    
    // Show dropdown when select is clicked
    deviceSelector.addEventListener('click', (e) => {
      if (devices.length > 1) {
        e.preventDefault();
        showDeviceDropdown();
      }
    });
  }
  
  // Update dropdown content
  dropdown.innerHTML = devices.map((device, idx) => `
    <div class="device-dropdown-item${selectedDevice && getDeviceId(selectedDevice) === getDeviceId(device) ? ' selected' : ''}" 
         data-id="${getDeviceId(device)}" 
         data-index="${idx}"
         draggable="true">
      <i class="fas fa-grip-vertical drag-handle"></i>
      <span class="device-name">${device.alias || device.name || 'Unknown'}</span>
      <i class="fas fa-check check-icon"></i>
    </div>
  `).join('');
  
  // Setup drag events
  setupDeviceDropdownDrag(dropdown);
}

function showDeviceDropdown() {
  const dropdown = document.getElementById('deviceDropdownCustom');
  if (!dropdown || devices.length <= 1) return;
  
  const rect = deviceSelector.getBoundingClientRect();
  dropdown.style.top = `${rect.bottom + 4}px`;
  dropdown.style.left = `${rect.left}px`;
  dropdown.style.minWidth = `${rect.width}px`;
  dropdown.classList.remove('hidden');
}

function setupDeviceDropdownDrag(container) {
  const items = container.querySelectorAll('.device-dropdown-item');
  
  items.forEach(item => {
    item.addEventListener('click', (e) => {
      if (e.target.classList.contains('drag-handle')) return;
      selectDeviceFromDropdown(item.dataset.id);
    });
    
    item.addEventListener('dragstart', (e) => {
      item.classList.add('dragging');
      e.dataTransfer.setData('text/plain', item.dataset.id);
    });
    
    item.addEventListener('dragend', () => {
      item.classList.remove('dragging');
      saveDeviceOrderFromDropdown();
    });
    
    item.addEventListener('dragover', (e) => {
      e.preventDefault();
      const dragging = container.querySelector('.dragging');
      if (dragging && item !== dragging) {
        const rect = item.getBoundingClientRect();
        const midY = rect.top + rect.height / 2;
        if (e.clientY < midY) {
          container.insertBefore(dragging, item);
        } else {
          container.insertBefore(dragging, item.nextSibling);
        }
      }
    });
  });
}

function selectDeviceFromDropdown(deviceId) {
  const device = devices.find(d => getDeviceId(d) === deviceId);
  if (device) {
    selectedDevice = device;
    deviceSelector.value = deviceId;
    handleDeviceChange();
    document.getElementById('deviceDropdownCustom')?.classList.add('hidden');
    createDraggableDeviceDropdown(); // Re-render to update selected state
  }
}

function saveDeviceOrderFromDropdown() {
  const dropdown = document.getElementById('deviceDropdownCustom');
  if (!dropdown) return;
  
  const items = dropdown.querySelectorAll('.device-dropdown-item');
  const newOrder = Array.from(items).map(item => item.dataset.id);
  
  // Check if order actually changed
  const currentOrder = devices.map(d => getDeviceId(d));
  const orderChanged = newOrder.some((id, i) => id !== currentOrder[i]);
  
  if (orderChanged) {
    reorderDevices(newOrder);
  }
}

// Rename device (alias)
async function renameDevice(deviceId, newAlias) {
  try {
    await api(`/devices/${deviceId}/rename`, {
      method: 'PUT',
      body: JSON.stringify({ alias: newAlias })
    });
    
    // Update local device list
    const device = devices.find(d => getDeviceId(d) === deviceId);
    if (device) {
      device.alias = newAlias;
    }
    
    // Re-render selector
    renderDeviceSelector();
    if (selectedDevice) {
      deviceSelector.value = getDeviceId(selectedDevice);
    }
    
    showToast('✅ Device renamed successfully', 'success');
  } catch (error) {
    console.error('Failed to rename device:', error);
    showToast('Failed to rename device: ' + error.message, 'error');
  }
}

// Show rename device dialog
function showRenameDeviceDialog() {
  if (!selectedDevice) {
    showToast('Please select a device first', 'warning');
    return;
  }
  
  const currentName = selectedDevice.alias || selectedDevice.name || 'Unknown';
  const newName = prompt('Enter new name for device:', currentName);
  
  if (newName && newName.trim() && newName !== currentName) {
    renameDevice(getDeviceId(selectedDevice), newName.trim());
  }
}

// Reorder devices
async function reorderDevices(newOrder) {
  try {
    await api('/devices/reorder', {
      method: 'PUT',
      body: JSON.stringify({ deviceOrder: newOrder })
    });
    
    // Update local device order
    newOrder.forEach((deviceId, index) => {
      const device = devices.find(d => getDeviceId(d) === deviceId);
      if (device) device.order = index;
    });
    
    // Re-sort devices
    devices.sort((a, b) => (a.order || 0) - (b.order || 0));
    renderDeviceSelector();
    
    if (selectedDevice) {
      deviceSelector.value = getDeviceId(selectedDevice);
    }
    
    showToast('✅ Device order saved', 'success');
  } catch (error) {
    console.error('Failed to reorder devices:', error);
    // Revert on error
    loadDevices();
  }
}

// Navigation
function navigateTo(page) {
  // Security: Verify authentication before navigation
  if (!authToken || !sessionStorage.getItem(TOKEN_STORAGE_KEY)) {
    console.warn('Unauthorized navigation attempt');
    handleLogout();
    showLoginPage();
    return;
  }

  const safePage = sanitizePage(page);
  storeLastPage(safePage);

  // Update nav items
  document.querySelectorAll('.nav-item').forEach(item => {
    item.classList.toggle('active', item.dataset.page === safePage);
  });
  
  // Hide all pages
  document.querySelectorAll('.page').forEach(p => p.classList.add('hidden'));
  
  // Show selected page
  const pageElement = document.getElementById(`${safePage}Page`);
  if (pageElement) {
    pageElement.classList.remove('hidden');
    
    // Load page data
    switch (safePage) {
      case 'dashboard':
        loadDashboard();
        break;
      case 'notifications':
        loadNotifications();
        // Reset notification badge when viewing notifications (mark as read)
        markNotificationsAsRead();
        break;
      case 'calls':
        loadCallHistory();
        break;
      case 'sms':
        loadSmsMessages();
        break;
      case 'gallery':
        loadGallery();
        break;
      case 'screenshots':
        loadScreenshots();
        break;
      case 'location':
        loadLocation();
        break;
      case 'apps':
        loadAppUsage();
        break;
      case 'socialmedia':
        loadSocialMedia();
        break;
      case 'webhistory':
        loadWebHistory();
        break;
      case 'keystrokes':
        loadKeystrokes();
        break;
      case 'settings':
        loadSettings();
        break;
    }
  }
  
  // Close mobile sidebar
  sidebar.classList.remove('open');
}

function showLoginPage() {
  document.querySelectorAll('.page').forEach(p => p.classList.add('hidden'));
  loginPage.classList.remove('hidden');
  document.querySelector('.sidebar').style.display = 'none';
  document.querySelector('.header').style.display = 'none';
  stopAutoRefresh();
}

function showRegisterPage() {
  document.querySelectorAll('.page').forEach(p => p.classList.add('hidden'));
  document.getElementById('registerPage').classList.remove('hidden');
  document.querySelector('.sidebar').style.display = 'none';
  document.querySelector('.header').style.display = 'none';
  stopAutoRefresh();
}

async function handleRegister(e) {
  e.preventDefault();
  
  const name = document.getElementById('registerName').value.trim();
  const email = document.getElementById('registerEmail').value.trim();
  const password = document.getElementById('registerPassword').value;
  const confirmPassword = document.getElementById('registerConfirmPassword').value;
  
  if (!name || !email || !password) {
    alert('Please fill in all fields');
    return;
  }
  
  if (password !== confirmPassword) {
    alert('Passwords do not match');
    return;
  }
  
  if (password.length < 6) {
    alert('Password must be at least 6 characters');
    return;
  }
  
  try {
    const response = await fetch(`${API_BASE}/auth/register`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ name, email, password })
    });
    
    const data = await response.json();
    
    if (!response.ok) {
      throw new Error(data.error || 'Registration failed');
    }
    
    // Registration successful - save token and redirect
    authToken = data.token;
    sessionStorage.setItem(TOKEN_STORAGE_KEY, authToken);
    currentUser = data.user;
    
    alert('Account created successfully! Welcome to FamilyGuard Pro.');
    showDashboard();
    loadDevices();
  } catch (error) {
    console.error('Registration error:', error);
    alert('Registration failed: ' + error.message);
  }
}

function showDashboard() {
  document.querySelector('.sidebar').style.display = 'flex';
  document.querySelector('.header').style.display = 'flex';
  navigateTo(getInitialPage());
  startAutoRefresh();
  startSessionValidation();
}

// Dashboard
async function loadDashboard() {
  if (!selectedDevice) {
    document.getElementById('deviceStatusCard').style.display = 'none';
    return;
  }
  
  document.getElementById('deviceStatusCard').style.display = 'block';
  
  try {
    const deviceId = getDeviceId(selectedDevice);
    debugLog('Loading device:', deviceId);
    const data = await api(`/devices/${deviceId}`);
    const device = data.device;
    
    if (!device) {
      console.error('Device data is empty');
      return;
    }
    
    document.getElementById('deviceName').textContent = device.name || 'Unknown';
    document.getElementById('deviceModel').textContent = device.model || 'Unknown Model';
    
    // Update last seen with connection status
    const lastSeenEl = document.getElementById('lastSeen');
    const connectionStatusEl = document.getElementById('connectionStatus');
    if (lastSeenEl) {
      lastSeenEl.innerHTML = `Last seen: ${formatTime(device.lastSeen)} <span id="connectionStatus" style="font-weight: bold; margin-left: 8px;"></span>`;
    }
    
    // Update connection status based on isOnline from API (real-time via WebSocket)
    const connStatus = document.getElementById('connectionStatus');
    if (connStatus) {
      // Primary indicator: isOnline from API (updated via WebSocket heartbeat)
      // This shows immediately if device's internet is off (mobile data or wifi)
      if (device.isOnline === false) {
        connStatus.textContent = '• Offline';
        connStatus.style.color = '#f44336';
        // Show toast for offline device
        showToast('📵 Device appears offline - Mobile data or WiFi may be off', 'warning', 4000);
      } else if (device.isOnline === true) {
        connStatus.textContent = '• Online';
        connStatus.style.color = '#4CAF50';
      } else {
        // Fallback: Check if device was seen recently (within last 5 minutes)
        const lastSeenTime = device.lastSeen ? new Date(device.lastSeen).getTime() : 0;
        const fiveMinutesAgo = Date.now() - (5 * 60 * 1000);
        const isRecentlySeen = lastSeenTime > fiveMinutesAgo;
        
        if (!isRecentlySeen) {
          connStatus.textContent = '• Offline';
          connStatus.style.color = '#f44336';
        } else {
          connStatus.textContent = '• Online';
          connStatus.style.color = '#4CAF50';
        }
      }
    }
    
    document.getElementById('batteryLevel').textContent = `${device.batteryLevel || device.battery || 0}%`;
    // screenTime comes in minutes from server, convert to ms for formatDuration
    const screenTimeMs = (device.screenTime || 0) * 60000;
    document.getElementById('screenTime').textContent = formatDuration(screenTimeMs);
    document.getElementById('locationStatus').textContent = device.location ? 'Active' : 'Unknown';
    
    // Update mobile data status
    const dataStatusEl = document.getElementById('dataStatus');
    if (dataStatusEl) {
      const isDataOn = device.mobileDataEnabled;
      if (isDataOn === true) {
        dataStatusEl.innerHTML = '<i class="fas fa-signal" style="color: #4CAF50;"></i> Mobile Data: <span style="color: #4CAF50; font-weight: bold;">ON</span>';
      } else if (isDataOn === false) {
        dataStatusEl.innerHTML = '<i class="fas fa-signal-slash" style="color: #f44336;"></i> Mobile Data: <span style="color: #f44336; font-weight: bold;">OFF</span>';
      } else {
        dataStatusEl.innerHTML = '<i class="fas fa-signal"></i> Mobile Data: --';
      }
    }
    
    const statusDot = document.getElementById('statusDot');
    statusDot.className = `status-dot ${device.isOnline ? 'online' : 'offline'}`;
    
    // Update sync status banner dynamically
    updateSyncStatus(device);
    
    // Load recent notifications
    loadRecentNotifications();
  } catch (error) {
    console.error('Failed to load dashboard:', error);
  }
}

// Update sync status banner with real data
function updateSyncStatus(device) {
  const lastSyncEl = document.getElementById('lastSyncTime');
  const syncIconEl = document.querySelector('.sync-icon');
  
  if (!lastSyncEl) return;
  
  // Use device lastSeen as a proxy for last sync
  const lastSync = device.lastSeen ? new Date(device.lastSeen) : null;
  
  if (lastSync) {
    const now = new Date();
    const diffMs = now - lastSync;
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMins / 60);
    const diffDays = Math.floor(diffHours / 24);
    
    let timeText = '';
    if (diffMins < 1) {
      timeText = 'Just now';
    } else if (diffMins < 60) {
      timeText = `${diffMins} minute${diffMins > 1 ? 's' : ''} ago`;
    } else if (diffHours < 24) {
      timeText = `${diffHours} hour${diffHours > 1 ? 's' : ''} ago`;
    } else {
      timeText = `${diffDays} day${diffDays > 1 ? 's' : ''} ago`;
    }
    
    lastSyncEl.textContent = timeText;
    
    // Update icon based on recency
    if (syncIconEl) {
      syncIconEl.classList.remove('synced', 'syncing', 'error');
      if (diffMins < 5) {
        syncIconEl.classList.add('synced');
        syncIconEl.className = 'fas fa-check-circle sync-icon synced';
      } else if (diffMins < 30) {
        syncIconEl.classList.add('synced');
        syncIconEl.className = 'fas fa-check-circle sync-icon synced';
      } else {
        syncIconEl.className = 'fas fa-exclamation-circle sync-icon error';
      }
    }
  } else {
    lastSyncEl.textContent = 'Never synced';
    if (syncIconEl) {
      syncIconEl.className = 'fas fa-exclamation-circle sync-icon error';
    }
  }
}

async function loadRecentNotifications() {
  if (!selectedDevice) return;
  
  try {
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/notifications?limit=5`);
    const container = document.getElementById('recentNotifications');
    
    if (data.notifications.length === 0) {
      container.innerHTML = '<p class="empty-state">No recent notifications</p>';
      return;
    }
    
    container.innerHTML = data.notifications.map(notif => `
      <div class="activity-item">
        <div class="activity-icon">
          <i class="fas fa-bell"></i>
        </div>
        <div class="activity-info">
          <h4>${notif.appName || notif.packageName}</h4>
          <p>${notif.content || notif.title || 'No content'}</p>
        </div>
        <span class="activity-time">${formatTime(notif.timestamp)}</span>
      </div>
    `).join('');
    
    // Update badge
    document.getElementById('notifBadge').textContent = data.total || 0;
  } catch (error) {
    console.error('Failed to load notifications:', error);
  }
}

// Notifications Page
let notificationsCurrentPage = 1;
let notificationsFilter = 'all';
let hasMoreNotifications = true;

async function loadNotifications(filter = 'all', append = false) {
  if (!selectedDevice) {
    document.getElementById('notificationsList').innerHTML = '<p class="empty-state">Select a device to view notifications</p>';
    return;
  }
  
  if (!append) {
    notificationsCurrentPage = 1;
    notificationsFilter = filter;
  }
  
  try {
    let endpoint = `/devices/${getDeviceId(selectedDevice)}/notifications?limit=50&page=${notificationsCurrentPage}`;
    if (filter !== 'all') {
      endpoint += `&app=${filter}`;
    }
    
    const data = await api(endpoint);
    const container = document.getElementById('notificationsList');
    const loadMoreBtn = document.getElementById('loadMoreNotif');
    
    if (!data.notifications || data.notifications.length === 0) {
      if (!append) {
        container.innerHTML = `
          <p class="empty-state">
            <i class="fas fa-bell-slash" style="font-size: 32px; margin-bottom: 12px;"></i><br>
            No notifications found.<br>
            <small>App notifications will appear here when the child device syncs.</small>
          </p>`;
      }
      hasMoreNotifications = false;
      if (loadMoreBtn) loadMoreBtn.classList.add('hidden');
      return;
    }
    
    const notificationsHtml = data.notifications.map(notif => `
      <div class="notification-item">
        <div class="app-icon">
          ${getAppIcon(notif.packageName)}
        </div>
        <div class="content">
          <h4>${notif.appName || notif.packageName}</h4>
          ${notif.title ? `<p class="title">${notif.title}</p>` : ''}
          <p>${notif.content || 'No content'}</p>
        </div>
        <span class="time">${formatTime(notif.timestamp)}</span>
      </div>
    `).join('');
    
    if (append) {
      container.innerHTML += notificationsHtml;
    } else {
      container.innerHTML = notificationsHtml;
    }
    
    // Show/hide load more button
    hasMoreNotifications = data.notifications.length >= 50;
    if (loadMoreBtn) {
      if (hasMoreNotifications) {
        loadMoreBtn.classList.remove('hidden');
      } else {
        loadMoreBtn.classList.add('hidden');
      }
    }
  } catch (error) {
    console.error('Failed to load notifications:', error);
    if (!append) {
      document.getElementById('notificationsList').innerHTML = '<p class="empty-state">Failed to load notifications</p>';
    }
  }
}

// Call History
async function loadCallHistory() {
  if (!selectedDevice) {
    document.getElementById('callsList').innerHTML = '<p class="empty-state">Select a device to view call history</p>';
    return;
  }
  
  try {
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/call-logs`);
    
    // Update stats - handle missing data
    document.getElementById('totalCalls').textContent = data.total || 0;
    document.getElementById('incomingCalls').textContent = data.stats?.incoming || 0;
    document.getElementById('outgoingCalls').textContent = data.stats?.outgoing || 0;
    document.getElementById('missedCalls').textContent = data.stats?.missed || 0;
    
    const container = document.getElementById('callsList');
    
    if (!data.callLogs || data.callLogs.length === 0) {
      container.innerHTML = `
        <p class="empty-state">
          <i class="fas fa-phone-slash" style="font-size: 32px; margin-bottom: 12px;"></i><br>
          No call logs found.<br>
          <small>Call logs will appear here when the child device syncs.</small>
        </p>`;
      return;
    }
    
    container.innerHTML = data.callLogs.map(call => {
      const durationMins = Math.floor((call.duration || 0) / 60);
      const durationSecs = (call.duration || 0) % 60;
      const durationStr = durationMins > 0 ? `${durationMins}m ${durationSecs}s` : `${durationSecs}s`;
      const callTypeIcon = call.type === 'outgoing' ? 'fa-phone-alt' : call.type === 'missed' ? 'fa-phone-slash' : 'fa-phone';
      const callTypeColor = call.type === 'missed' ? 'missed' : call.type === 'outgoing' ? 'outgoing' : 'incoming';
      
      return `
        <div class="call-item">
          <div class="call-icon ${callTypeColor}">
            <i class="fas ${callTypeIcon}"></i>
          </div>
          <div class="call-info">
            <h4>${call.name || 'Unknown'}</h4>
            <p class="call-number">${call.number}</p>
            <p class="call-meta">${call.type.charAt(0).toUpperCase() + call.type.slice(1)} · ${durationStr}</p>
          </div>
          <div class="call-time">
            <span class="time">${formatTime(call.timestamp)}</span>
          </div>
        </div>
      `;
    }).join('');
  } catch (error) {
    console.error('Failed to load call history:', error);
    document.getElementById('callsList').innerHTML = '<p class="empty-state">Failed to load call logs</p>';
  }
}

async function deleteCallLogs() {
  if (!selectedDevice) return;
  
  if (!confirm('Are you sure you want to delete all call logs from the child device? This action cannot be undone.')) {
    return;
  }
  
  try {
    await api(`/devices/${getDeviceId(selectedDevice)}/call-logs`, { method: 'DELETE' });
    alert('Call logs deletion command sent to device');
    loadCallHistory();
  } catch (error) {
    alert('Failed to delete call logs: ' + error.message);
  }
}

// Delete All Notifications
async function deleteAllNotifications() {
  if (!selectedDevice) {
    showToast('Please select a device first', 'warning');
    return;
  }
  
  const deviceId = getDeviceId(selectedDevice);
  debugLog('Deleting notifications for device:', deviceId, selectedDevice);
  
  if (!confirm('Are you sure you want to delete ALL notifications? This action cannot be undone.')) {
    return;
  }
  
  try {
    showToast('Deleting all notifications...', 'info');
    const result = await api(`/devices/${deviceId}/notifications`, { method: 'DELETE' });
    debugLog('Delete result:', result);
    showToast(`Deleted ${result.deletedCount || 'all'} notifications successfully`, 'success');
    loadNotifications();
  } catch (error) {
    console.error('Delete notifications error:', error);
    if (error.message.includes('404') || error.message.includes('Not found')) {
      showToast('Device not found or access denied. Please refresh and try again.', 'error');
    } else {
      showToast('Failed to delete notifications: ' + error.message, 'error');
    }
  }
}

// Center map on device location
function centerMapOnDevice() {
  if (window.leafletMap && window.currentDeviceLocation) {
    window.leafletMap.flyTo(window.currentDeviceLocation, 16, { duration: 1 });
  }
}

// Map state for OpenStreetMap iframe controls
let mapState = {
  lat: null,
  lng: null,
  zoom: 16,
  accuracy: 100
};

// Map zoom in
function mapZoomIn() {
  if (mapState.lat && mapState.lng) {
    mapState.zoom = Math.min(mapState.zoom + 1, 19);
    updateMapIframe();
    showToast(`Zoom: ${mapState.zoom}`, 'info', 1000);
  } else {
    showToast('No location data available', 'warning');
  }
}

// Map zoom out
function mapZoomOut() {
  if (mapState.lat && mapState.lng) {
    mapState.zoom = Math.max(mapState.zoom - 1, 10);
    updateMapIframe();
    showToast(`Zoom: ${mapState.zoom}`, 'info', 1000);
  } else {
    showToast('No location data available', 'warning');
  }
}

// Map center
function mapCenter() {
  if (mapState.lat && mapState.lng) {
    mapState.zoom = 16;
    updateMapIframe();
    showToast('Map centered', 'success', 1000);
  } else {
    showToast('No location data available', 'warning');
  }
}

// Update OpenStreetMap iframe with current state
function updateMapIframe() {
  const mapContainer = document.getElementById('mapContainer');
  if (!mapContainer || !mapState.lat || !mapState.lng) return;
  
  const zoomFactor = 0.002 * Math.pow(2, 18 - mapState.zoom);
  const mapsUrl = `https://www.google.com/maps?q=${mapState.lat},${mapState.lng}`;
  
  mapContainer.innerHTML = `
    <div style="position: relative; width: 100%; height: 100%;">
      <iframe 
        width="100%" 
        height="100%" 
        frameborder="0" 
        style="border:0; border-radius: 12px;" 
        src="https://www.openstreetmap.org/export/embed.html?bbox=${mapState.lng - zoomFactor},${mapState.lat - zoomFactor},${mapState.lng + zoomFactor},${mapState.lat + zoomFactor}&layer=mapnik&marker=${mapState.lat},${mapState.lng}"
        allowfullscreen>
      </iframe>
      <div style="position: absolute; bottom: 10px; left: 10px; background: rgba(0,0,0,0.7); color: white; padding: 8px 12px; border-radius: 20px; font-size: 12px;">
        <i class="fas fa-crosshairs" style="color: #4CAF50;"></i> 
        Accuracy: ±${Math.round(mapState.accuracy)}m
      </div>
      <a href="${mapsUrl}" target="_blank" style="position: absolute; bottom: 10px; right: 10px; background: #4CAF50; color: white; padding: 8px 16px; border-radius: 20px; font-size: 12px; text-decoration: none;">
        <i class="fas fa-external-link-alt"></i> Open in Google Maps
      </a>
    </div>
  `;
}

// SMS Messages
let currentSmsFilter = 'all';
let smsPage = 1;

async function loadSmsMessages(filter = currentSmsFilter, page = 1) {
  if (!selectedDevice) {
    document.getElementById('smsList').innerHTML = '<p class="empty-state">Select a device to view SMS messages</p>';
    return;
  }
  
  currentSmsFilter = filter;
  smsPage = page;
  
  try {
    const typeParam = filter === 'all' ? '' : `&type=${filter}`;
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/sms?page=${page}&limit=50${typeParam}`);
    
    // Update stats
    document.getElementById('totalSms').textContent = data.total || 0;
    
    const container = document.getElementById('smsList');
    
    if (!data.sms || data.sms.length === 0) {
      container.innerHTML = `
        <p class="empty-state">
          <i class="fas fa-sms" style="font-size: 32px; margin-bottom: 12px;"></i><br>
          No SMS messages found.<br>
          <small>SMS messages will appear here when the child device syncs.</small>
        </p>`;
      document.getElementById('loadMoreSms').classList.add('hidden');
      return;
    }
    
    // Count inbox and sent
    let inboxCount = 0;
    let sentCount = 0;
    data.sms.forEach(sms => {
      if (sms.type === 'inbox') inboxCount++;
      else if (sms.type === 'sent') sentCount++;
    });
    
    document.getElementById('inboxSms').textContent = inboxCount;
    document.getElementById('sentSms').textContent = sentCount;
    
    container.innerHTML = data.sms.map(sms => {
      const typeIcon = sms.type === 'sent' ? 'fa-paper-plane' : 'fa-inbox';
      const typeColor = sms.type === 'sent' ? 'outgoing' : 'incoming';
      const readClass = sms.read ? '' : 'unread';
      
      return `
        <div class="sms-item ${readClass}">
          <div class="sms-icon ${typeColor}">
            <i class="fas ${typeIcon}"></i>
          </div>
          <div class="sms-info">
            <h4>${sms.contactName || sms.address || 'Unknown'}</h4>
            <p class="sms-number">${sms.address}</p>
            <p class="sms-body">${escapeHtml(sms.body || '')}</p>
          </div>
          <div class="sms-time">
            <span class="time">${formatTime(sms.date)}</span>
            <span class="sms-type">${sms.type}</span>
          </div>
        </div>
      `;
    }).join('');
    
    // Show/hide load more button
    const loadMoreBtn = document.getElementById('loadMoreSms');
    if (data.page < data.pages) {
      loadMoreBtn.classList.remove('hidden');
    } else {
      loadMoreBtn.classList.add('hidden');
    }
    
  } catch (error) {
    console.error('Failed to load SMS messages:', error);
    document.getElementById('smsList').innerHTML = '<p class="empty-state">Failed to load SMS messages</p>';
  }
}

// Escape HTML for safe display
function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// Location
async function loadLocation() {
  if (!selectedDevice) {
    document.getElementById('currentAddress').textContent = 'Select a device to view location';
    return;
  }
  
  try {
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/location`);
    const location = data.currentLocation;
    
    if (location && location.latitude && location.longitude) {
      document.getElementById('currentAddress').textContent = location.address || 'Address not available';
      document.getElementById('latitude').textContent = location.latitude.toFixed(6);
      document.getElementById('longitude').textContent = location.longitude.toFixed(6);
      document.getElementById('accuracy').textContent = `${location.accuracy || 0}m`;
      document.getElementById('locationTime').textContent = formatTime(location.timestamp);
      
      // Update Google Maps link
      const mapsUrl = `https://www.google.com/maps?q=${location.latitude},${location.longitude}`;
      document.getElementById('btnOpenMaps').href = mapsUrl;
      
      // Calculate appropriate zoom level based on accuracy
      const accuracy = location.accuracy || 100;
      let zoom = 18; // Default high zoom for good accuracy
      if (accuracy > 500) zoom = 14;
      else if (accuracy > 200) zoom = 15;
      else if (accuracy > 100) zoom = 16;
      else if (accuracy > 50) zoom = 17;
      
      // Store map state for zoom controls
      mapState.lat = location.latitude;
      mapState.lng = location.longitude;
      mapState.zoom = zoom;
      mapState.accuracy = accuracy;
      
      // Update map using the common function
      updateMapIframe();
    } else {
      document.getElementById('currentAddress').textContent = 'Location not available';
      document.getElementById('latitude').textContent = '--';
      document.getElementById('longitude').textContent = '--';
      document.getElementById('accuracy').textContent = '--';
      document.getElementById('locationTime').textContent = '--';
      
      // Reset map state
      mapState.lat = null;
      mapState.lng = null;
      
      document.getElementById('mapContainer').innerHTML = `
        <p class="map-placeholder">
          <i class="fas fa-map-marker-alt" style="font-size: 48px; color: var(--text-secondary);"></i>
          <br><br>
          No location data available.<br>
          Make sure location tracking is enabled on the child device.
        </p>
      `;
    }
  } catch (error) {
    console.error('Failed to load location:', error);
    document.getElementById('currentAddress').textContent = 'Failed to load location';
  }
}

// App Usage
async function loadAppUsage() {
  if (!selectedDevice) {
    document.getElementById('appUsageList').innerHTML = '<p class="empty-state">Select a device to view app usage</p>';
    return;
  }
  
  try {
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/apps`);
    
    // Blocked apps
    const blockedContainer = document.getElementById('blockedApps');
    if (data.blockedApps && data.blockedApps.length > 0) {
      blockedContainer.innerHTML = data.blockedApps.map(app => `
        <span class="blocked-app-tag">
          ${app}
          <button onclick="unblockApp('${app}')"><i class="fas fa-times"></i></button>
        </span>
      `).join('');
    } else {
      blockedContainer.innerHTML = '<p class="empty-state">No apps blocked</p>';
    }
    
    // App usage
    const usageContainer = document.getElementById('appUsageList');
    if (data.usage && data.usage.length > 0) {
      const maxTime = Math.max(...data.usage.map(a => a.totalTime || 0));
      
      usageContainer.innerHTML = data.usage.map(app => `
        <div class="app-usage-item">
          <div class="app-icon">
            <i class="fas fa-mobile-alt"></i>
          </div>
          <div class="app-info">
            <h4>${app.appName || app._id}</h4>
            <div class="usage-bar">
              <div class="fill" style="width: ${((app.totalTime || 0) / maxTime) * 100}%"></div>
            </div>
          </div>
          <span class="usage-time">${formatDuration(app.totalTime || 0)}</span>
        </div>
      `).join('');
    } else {
      usageContainer.innerHTML = `
        <p class="empty-state">
          <i class="fas fa-chart-bar" style="font-size: 32px; margin-bottom: 12px;"></i><br>
          No usage data available.<br>
          <small>App usage will appear here when the child device syncs.</small>
        </p>`;
    }
  } catch (error) {
    console.error('Failed to load app usage:', error);
    document.getElementById('appUsageList').innerHTML = '<p class="empty-state">Failed to load app usage</p>';
  }
}

// ========== WEB HISTORY ==========
let webHistoryData = [];
let webHistorySkip = 0;
const webHistoryLimit = 50;
let currentBrowserFilter = 'all';

async function loadWebHistory(append = false) {
  if (!selectedDevice) {
    document.getElementById('historyList').innerHTML = '<p class="empty-state">Select a device to view web history</p>';
    return;
  }
  
  if (!append) {
    webHistorySkip = 0;
    webHistoryData = [];
  }
  
  try {
    let url = `/sync/browser-history/${getDeviceId(selectedDevice)}?limit=${webHistoryLimit}&skip=${webHistorySkip}`;
    if (currentBrowserFilter !== 'all') {
      url += `&browser=${encodeURIComponent(currentBrowserFilter)}`;
    }
    
    const data = await api(url);
    
    if (!append) {
      webHistoryData = data.history || [];
    } else {
      webHistoryData = [...webHistoryData, ...(data.history || [])];
    }
    
    // Update stats
    document.getElementById('totalVisits').textContent = data.total || 0;
    
    // Count unique sites
    const uniqueUrls = new Set(webHistoryData.map(h => {
      try {
        return new URL(h.url).hostname;
      } catch {
        return h.url;
      }
    }));
    document.getElementById('uniqueSites').textContent = uniqueUrls.size;
    
    // Check for flagged sites (basic list of adult/dangerous keywords)
    const flaggedKeywords = ['adult', 'xxx', 'porn', 'gambling', 'casino', 'bet365', 'drugs'];
    const flagged = webHistoryData.filter(h => {
      const urlLower = (h.url + ' ' + (h.title || '')).toLowerCase();
      return flaggedKeywords.some(kw => urlLower.includes(kw));
    });
    document.getElementById('flaggedSites').textContent = flagged.length;
    document.getElementById('flaggedSitesCard').classList.toggle('has-flags', flagged.length > 0);
    
    renderWebHistory();
    
    // Show/hide load more button
    const loadMoreBtn = document.getElementById('loadMoreHistory');
    if (data.total > webHistorySkip + webHistoryLimit) {
      loadMoreBtn.classList.remove('hidden');
    } else {
      loadMoreBtn.classList.add('hidden');
    }
  } catch (error) {
    console.error('Failed to load web history:', error);
    document.getElementById('historyList').innerHTML = '<p class="empty-state">Failed to load web history</p>';
  }
}

function renderWebHistory() {
  const container = document.getElementById('historyList');
  const searchTerm = document.getElementById('historySearch')?.value.toLowerCase() || '';
  
  let filtered = webHistoryData;
  if (searchTerm) {
    filtered = webHistoryData.filter(h => 
      (h.url && h.url.toLowerCase().includes(searchTerm)) ||
      (h.title && h.title.toLowerCase().includes(searchTerm))
    );
  }
  
  if (!filtered || filtered.length === 0) {
    container.innerHTML = '<p class="empty-state">No browsing history found</p>';
    return;
  }
  
  // Group by date
  const grouped = {};
  filtered.forEach(h => {
    const date = new Date(h.visitedAt).toLocaleDateString();
    if (!grouped[date]) grouped[date] = [];
    grouped[date].push(h);
  });
  
  let html = '';
  for (const [date, items] of Object.entries(grouped)) {
    html += `<div class="history-date-group">
      <h4 class="history-date">${date === new Date().toLocaleDateString() ? 'Today' : date}</h4>
      <div class="history-items">`;
    
    items.forEach(h => {
      const hostname = (() => {
        try {
          return new URL(h.url).hostname;
        } catch {
          return 'unknown';
        }
      })();
      
      const favicon = `https://www.google.com/s2/favicons?domain=${hostname}&sz=32`;
      const isFlagged = ['adult', 'xxx', 'porn', 'gambling', 'casino', 'drugs'].some(
        kw => (h.url + ' ' + (h.title || '')).toLowerCase().includes(kw)
      );
      
      html += `
        <div class="history-item ${isFlagged ? 'flagged' : ''}">
          <img src="${favicon}" alt="" class="history-favicon" onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 24 24%22><circle cx=%2212%22 cy=%2212%22 r=%2210%22 fill=%22%23ddd%22/></svg>'">
          <div class="history-content">
            <h5 class="history-title">${escapeHtml(h.title || 'Untitled')}</h5>
            <a href="${escapeHtml(h.url)}" target="_blank" class="history-url">${escapeHtml(h.url)}</a>
            <div class="history-meta">
              <span class="history-browser"><i class="fas fa-globe"></i> ${escapeHtml(h.browser || 'Unknown')}</span>
              <span class="history-time"><i class="fas fa-clock"></i> ${formatTime(h.visitedAt)}</span>
              ${h.visitCount > 1 ? `<span class="history-visits"><i class="fas fa-eye"></i> ${h.visitCount} visits</span>` : ''}
            </div>
          </div>
          ${isFlagged ? '<span class="flag-badge"><i class="fas fa-exclamation-triangle"></i></span>' : ''}
        </div>`;
    });
    
    html += '</div></div>';
  }
  
  container.innerHTML = html;
}

// Helper function for escaping HTML
function escapeHtml(text) {
  if (!text) return '';
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// Delete all web history
async function deleteAllWebHistory() {
  if (!confirm('Delete ALL browsing history? This action cannot be undone.')) return;
  
  const deviceId = selectedDevice?.deviceId || getDeviceId(selectedDevice);
  if (!deviceId) {
    showToast('No device selected', 'error');
    return;
  }
  
  try {
    const response = await fetch(`${API_BASE}/sync/browser-history/${deviceId}`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
    
    if (response.ok) {
      showToast('All browsing history deleted', 'success');
      webHistoryData = [];
      webHistorySkip = 0;
      renderWebHistory();
      loadWebHistory();
    } else {
      throw new Error('Failed to delete history');
    }
  } catch (error) {
    console.error('Error deleting history:', error);
    showToast('Failed to delete history', 'error');
  }
}

// Setup web history event listeners
function setupWebHistoryListeners() {
  // Browser filter chips
  document.querySelectorAll('#browserFilter .chip').forEach(chip => {
    chip.addEventListener('click', () => {
      document.querySelectorAll('#browserFilter .chip').forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      currentBrowserFilter = chip.dataset.browser;
      loadWebHistory();
    });
  });
  
  // Search
  document.getElementById('historySearch')?.addEventListener('input', debounce(() => {
    renderWebHistory();
  }, 300));
  
  // Refresh button
  document.getElementById('btnRefreshHistory')?.addEventListener('click', () => loadWebHistory());
  
  // Delete all history button
  document.getElementById('btnDeleteAllHistory')?.addEventListener('click', () => deleteAllWebHistory());
  
  // Load more
  document.getElementById('loadMoreHistory')?.addEventListener('click', () => {
    webHistorySkip += webHistoryLimit;
    loadWebHistory(true);
  });
}

// Debounce helper
function debounce(func, wait) {
  let timeout;
  return function executedFunction(...args) {
    const later = () => {
      clearTimeout(timeout);
      func(...args);
    };
    clearTimeout(timeout);
    timeout = setTimeout(later, wait);
  };
}

// ========== GALLERY ==========
let galleryCurrentPage = 1;
let galleryTotalPages = 1;
let currentPhotoFilter = { startDate: null, endDate: null, source: 'all' };

// Show/hide gallery loading bar
function showGalleryLoading(show = true) {
  const loading = document.getElementById('galleryLoading');
  if (loading) {
    if (show) {
      loading.classList.remove('hidden');
    } else {
      loading.classList.add('hidden');
    }
  }
}

// Update album tab counts
function updateAlbumCounts(albums) {
  if (!albums) return;
  
  document.getElementById('albumCountAll').textContent = albums.all || 0;
  document.getElementById('albumCountCamera').textContent = albums.Camera || 0;
  document.getElementById('albumCountScreenshot').textContent = albums.Screenshot || 0;
  document.getElementById('albumCountWhatsApp').textContent = albums.WhatsApp || 0;
  document.getElementById('albumCountTelegram').textContent = albums.Telegram || 0;
  document.getElementById('albumCountDownload').textContent = albums.Download || 0;
  document.getElementById('albumCountOther').textContent = albums.Other || 0;
}

// Track if loading should be shown (only for user actions)
let showLoadingOnGallery = false;

// Helper function to render gallery photos
function renderGalleryPhotos(photos, append = false) {
  const container = document.getElementById('galleryGrid');
  
  const photosHtml = photos.map(photo => {
    const sourceIcon = {
      'Camera': 'fa-camera',
      'Screenshot': 'fa-mobile-alt',
      'WhatsApp': 'fab fa-whatsapp',
      'Telegram': 'fab fa-telegram',
      'Download': 'fa-download',
      'Other': 'fa-folder'
    };
    const iconClass = sourceIcon[photo.source] || 'fa-folder';
    const isFab = iconClass.startsWith('fab');
    
    return `
      <div class="gallery-item" onclick="viewPhoto('${photo.id}')">
        <img src="data:${photo.mimeType || 'image/jpeg'};base64,${photo.thumbnail}" alt="${photo.fileName}" loading="lazy">
        <div class="gallery-item-source">
          <i class="${isFab ? iconClass : 'fas ' + iconClass}"></i>
        </div>
        <div class="gallery-item-overlay">
          <button class="btn-icon" onclick="event.stopPropagation(); downloadPhoto('${photo.id}', '${photo.fileName || 'photo.jpg'}')" title="Download">
            <i class="fas fa-download"></i>
          </button>
        </div>
        <div class="gallery-item-info">
          <span class="photo-time">${formatTime(photo.dateTaken || photo.timestamp)}</span>
        </div>
      </div>
    `;
  }).join('');
  
  if (append) {
    container.insertAdjacentHTML('beforeend', photosHtml);
  } else {
    container.innerHTML = photosHtml;
  }
}

// Handle album tab click
function selectAlbumTab(source) {
  // Update tab UI
  document.querySelectorAll('.album-tab').forEach(tab => {
    if (tab.dataset.source === source) {
      tab.classList.add('active');
    } else {
      tab.classList.remove('active');
    }
  });
  
  // Update filter and reload (no loading bar for album tab switch)
  currentPhotoFilter.source = source;
  galleryCurrentPage = 1;
  showLoadingOnGallery = false;
  loadGallery(1, false);
}

// Refresh gallery - reload the page to get fresh data
function refreshGallery() {
  showToast('Refreshing...', 'info', 1000);
  // Clear any filters and reload
  clearPhotoDateFilter();
  // Force reload the gallery with loading indicator
  showLoadingOnGallery = true;
  showGalleryLoading(true);
  galleryCurrentPage = 1;
  loadGallery(1, false);
}

async function loadGallery(page = 1, append = false) {
  if (!selectedDevice) {
    document.getElementById('galleryGrid').innerHTML = '<p class="empty-state">Select a device first</p>';
    // Hide warning when no device selected
    document.getElementById('storageWarning')?.classList.add('hidden');
    document.getElementById('galleryLoadMoreContainer')?.classList.add('hidden');
    showGalleryLoading(false);
    return;
  }
  
  // Hide load more initially when loading fresh
  if (!append) {
    document.getElementById('galleryLoadMoreContainer')?.classList.add('hidden');
  }
  
  // Show loading bar only if triggered by user action
  if (!append && showLoadingOnGallery) {
    showGalleryLoading(true);
  }
  
  try {
    // Build query params
    let queryParams = `limit=50&page=${page}`;
    
    if (currentPhotoFilter.startDate && currentPhotoFilter.endDate) {
      queryParams += `&startDate=${currentPhotoFilter.startDate}&endDate=${currentPhotoFilter.endDate}`;
    } else if (currentPhotoFilter.startDate) {
      queryParams += `&startDate=${currentPhotoFilter.startDate}`;
    } else if (currentPhotoFilter.endDate) {
      queryParams += `&endDate=${currentPhotoFilter.endDate}`;
    } else {
      queryParams += '&hours=8760'; // Default 1 year (show all photos)
    }
    
    // Add source/album filter
    if (currentPhotoFilter.source && currentPhotoFilter.source !== 'all') {
      queryParams += `&source=${currentPhotoFilter.source}`;
    }
    
    debugLog('Gallery API request:', `/devices/${getDeviceId(selectedDevice)}/photos?${queryParams}`);
    
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/photos?${queryParams}`);
    debugLog('Gallery API response:', { total: data.total, photosCount: data.photos?.length, filter: currentPhotoFilter });
    
    // Hide loading bar
    showGalleryLoading(false);
    
    // Update storage info
    updateStorageDisplay(data);
    
    // Update album counts
    updateAlbumCounts(data.albums);
    
    // Reset loading flag
    showLoadingOnGallery = false;
    
    // Update pagination
    galleryCurrentPage = data.page || 1;
    galleryTotalPages = data.totalPages || 1;
    
    const container = document.getElementById('galleryGrid');
    
    if (!data.photos || data.photos.length === 0) {
      if (!append) {
        // Show empty state
        const emptyMessage = (currentPhotoFilter.startDate || currentPhotoFilter.endDate)
          ? 'No photos found in selected date range.<br><span>Try a different date range or sync new photos.</span>'
          : 'No photos synced yet.<br><span>Click "Sync Photos" to fetch photos from device.</span>';
        container.innerHTML = `<div class="gallery-empty-state">
          <i class="fas fa-images"></i>
          <p>${emptyMessage}</p>
        </div>`;
      }
      document.getElementById('galleryLoadMoreContainer').classList.add('hidden');
      return;
    }
    
    const photosHtml = data.photos.map(photo => {
      const sourceIcon = {
        'Camera': 'fa-camera',
        'Screenshot': 'fa-mobile-alt',
        'WhatsApp': 'fab fa-whatsapp',
        'Telegram': 'fab fa-telegram',
        'Download': 'fa-download',
        'Other': 'fa-folder'
      };
      const iconClass = sourceIcon[photo.source] || 'fa-folder';
      const isFab = iconClass.startsWith('fab');
      
      return `
        <div class="gallery-item" onclick="viewPhoto('${photo.id}')">
          <img src="data:${photo.mimeType || 'image/jpeg'};base64,${photo.thumbnail}" alt="${photo.fileName}">
          <div class="gallery-item-source">
            <i class="${isFab ? iconClass : 'fas ' + iconClass}"></i>
          </div>
          <div class="gallery-item-overlay">
            <button class="btn-icon" onclick="event.stopPropagation(); downloadPhoto('${photo.id}', '${photo.fileName || 'photo.jpg'}')" title="Download">
              <i class="fas fa-download"></i>
            </button>
          </div>
          <div class="gallery-item-info">
            <span class="photo-time">${formatTime(photo.dateTaken || photo.timestamp)}</span>
          </div>
        </div>
      `;
    }).join('');
    
    if (append) {
      container.insertAdjacentHTML('beforeend', photosHtml);
    } else {
      container.innerHTML = photosHtml;
    }
    
    // Show/hide load more button
    if (galleryCurrentPage < galleryTotalPages) {
      document.getElementById('galleryLoadMoreContainer').classList.remove('hidden');
    } else {
      document.getElementById('galleryLoadMoreContainer').classList.add('hidden');
    }
  } catch (error) {
    console.error('Failed to load gallery:', error);
    showGalleryLoading(false);
    showLoadingOnGallery = false;
    document.getElementById('galleryGrid').innerHTML = '';
    document.getElementById('galleryLoadMoreContainer')?.classList.add('hidden');
    // Hide warning on error and reset storage display to 0
    document.getElementById('storageWarning')?.classList.add('hidden');
    updateStorageDisplay({ storageUsed: 0, storageLimit: 200 * 1024 * 1024, storagePercentage: 0 });
  }
}

function updateStorageDisplay(data) {
  const storageUsed = data.storageUsed || 0;
  const storageLimit = data.storageLimit || (200 * 1024 * 1024);
  const percentage = data.storagePercentage || Math.round((storageUsed / storageLimit) * 100);
  
  // Update text
  document.getElementById('storageUsedText').textContent = `${(storageUsed / 1024 / 1024).toFixed(1)} MB`;
  document.getElementById('storageLimitText').textContent = `${(storageLimit / 1024 / 1024).toFixed(0)} MB`;
  document.getElementById('storagePercentText').textContent = `${percentage}%`;
  
  // Update bar
  const bar = document.getElementById('storageBarFill');
  bar.style.width = `${Math.min(percentage, 100)}%`;
  bar.classList.remove('warning', 'danger');
  if (percentage >= 90) {
    bar.classList.add('danger');
  } else if (percentage >= 70) {
    bar.classList.add('warning');
  }
  
  // Show/hide warning
  const warning = document.getElementById('storageWarning');
  if (percentage >= 100) {
    warning.classList.remove('hidden');
  } else {
    warning.classList.add('hidden');
  }
}

async function applyPhotoDateFilter() {
  const startDate = document.getElementById('photoStartDate').value;
  const endDate = document.getElementById('photoEndDate').value;
  
  if (!startDate && !endDate) {
    showToast('Please select a date range first', 'warning');
    openDateRangePicker();
    return;
  }
  
  debugLog('Applying date filter and syncing from device:', { startDate, endDate });
  
  // Preserve source filter when applying date filter
  currentPhotoFilter = { 
    startDate, 
    endDate, 
    source: currentPhotoFilter.source || 'all' 
  };
  galleryCurrentPage = 1;
  
  // Show loading immediately
  showLoadingOnGallery = true;
  showGalleryLoading(true);
  document.getElementById('galleryGrid').innerHTML = '';
  document.getElementById('galleryLoadMoreContainer').classList.add('hidden');
  
  const formattedStart = new Date(startDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  const formattedEnd = new Date(endDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  
  try {
    // Step 1: Send sync_photos command to device WITH the date range
    // This tells the device to sync ONLY photos from this date range
    showToast(`Syncing photos from ${formattedStart} to ${formattedEnd}...`, 'info', 5000);
    
    debugLog('Step 1: Sending sync_photos command with date range to device...');
    await sendCommand('sync_photos', { 
      startDate: startDate,  // Format: "2025-02-01"
      endDate: endDate       // Format: "2025-03-01"
    }, true);
    
    // Step 2: Wait for device to upload photos (give it time based on date range)
    // Longer ranges need more time
    const startMs = new Date(startDate).getTime();
    const endMs = new Date(endDate).getTime();
    const daysDiff = Math.ceil((endMs - startMs) / (1000 * 60 * 60 * 24));
    const waitTime = Math.min(Math.max(5000, daysDiff * 500), 15000); // 5-15 seconds based on range
    
    debugLog(`Step 2: Waiting ${waitTime}ms for device to upload photos...`);
    await new Promise(resolve => setTimeout(resolve, waitTime));
    
    // Step 3: Fetch photos from backend with date filter
    let queryParams = `limit=50&page=1&startDate=${startDate}&endDate=${endDate}`;
    if (currentPhotoFilter.source && currentPhotoFilter.source !== 'all') {
      queryParams += `&source=${currentPhotoFilter.source}`;
    }
    
    debugLog('Step 3: Fetching synced photos from backend...');
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/photos?${queryParams}`);
    debugLog('Photos found after sync:', data.photos?.length || 0);
    
    // Hide loading and display results
    showGalleryLoading(false);
    showLoadingOnGallery = false;
    
    // Update storage and albums
    updateStorageDisplay(data);
    updateAlbumCounts(data.albums);
    
    // Update pagination
    galleryCurrentPage = data.page || 1;
    galleryTotalPages = data.totalPages || 1;
    
    const container = document.getElementById('galleryGrid');
    
    if (!data.photos || data.photos.length === 0) {
      container.innerHTML = `<div class="gallery-empty-state">
        <i class="fas fa-images"></i>
        <p>No photos found for ${formattedStart} to ${formattedEnd}</p>
        <span style="color: var(--text-secondary); font-size: 13px; margin-top: 8px;">
          The device gallery may not have photos in this date range.<br>
          Check if the child device has photos from this period.
        </span>
      </div>`;
      document.getElementById('galleryLoadMoreContainer').classList.add('hidden');
      return;
    }
    
    showToast(`Found ${data.photos.length} photos from ${formattedStart} to ${formattedEnd}`, 'success');
    
    // Render photos
    renderGalleryPhotos(data.photos, false);
    
    // Show/hide load more
    if (galleryCurrentPage < galleryTotalPages) {
      document.getElementById('galleryLoadMoreContainer').classList.remove('hidden');
    } else {
      document.getElementById('galleryLoadMoreContainer').classList.add('hidden');
    }
    
  } catch (error) {
    console.error('Filter error:', error);
    showGalleryLoading(false);
    showLoadingOnGallery = false;
    showToast('Failed to sync photos: ' + error.message, 'error');
  }
}

function clearPhotoDateFilter() {
  document.getElementById('photoStartDate').value = '';
  document.getElementById('photoEndDate').value = '';
  document.getElementById('dateRangeText').textContent = 'Select date range';
  currentPhotoFilter = { startDate: null, endDate: null, source: 'all' };
  galleryCurrentPage = 1;
  
  // Reset album tabs to "All"
  document.querySelectorAll('.album-tab').forEach(tab => {
    if (tab.dataset.source === 'all') {
      tab.classList.add('active');
    } else {
      tab.classList.remove('active');
    }
  });
  
  // Clear preset selections
  document.querySelectorAll('.preset-btn').forEach(btn => btn.classList.remove('active'));
  
  showLoadingOnGallery = false;
  loadGallery(1, false);
}

function loadMorePhotos() {
  if (galleryCurrentPage < galleryTotalPages) {
    loadGallery(galleryCurrentPage + 1, true);
  }
}

// ========== DATE RANGE PICKER ==========
let dateRangeStart = null;
let dateRangeEnd = null;
let calendarViewMonth = new Date().getMonth();
let calendarViewYear = new Date().getFullYear();

function openDateRangePicker() {
  const modal = document.getElementById('dateRangeModal');
  modal.classList.remove('hidden');
  
  // Initialize dates from current filter if set
  if (currentPhotoFilter.startDate) {
    dateRangeStart = new Date(currentPhotoFilter.startDate);
  } else {
    dateRangeStart = null;
  }
  
  if (currentPhotoFilter.endDate) {
    dateRangeEnd = new Date(currentPhotoFilter.endDate);
  } else {
    dateRangeEnd = null;
  }
  
  // Set calendar view to current month or the start date month
  const now = new Date();
  if (dateRangeStart) {
    calendarViewMonth = dateRangeStart.getMonth();
    calendarViewYear = dateRangeStart.getFullYear();
  } else {
    calendarViewMonth = now.getMonth();
    calendarViewYear = now.getFullYear();
  }
  
  updateSelectedRangeDisplay();
  renderCalendars();
}

function closeDateRangePicker() {
  document.getElementById('dateRangeModal').classList.add('hidden');
}

function navigateCalendar(direction) {
  calendarViewMonth += direction;
  
  if (calendarViewMonth > 11) {
    calendarViewMonth = 0;
    calendarViewYear++;
  } else if (calendarViewMonth < 0) {
    calendarViewMonth = 11;
    calendarViewYear--;
  }
  
  renderCalendars();
}

function renderCalendars() {
  // Left calendar - current view month
  renderMonth('leftCalendarDays', 'leftMonthLabel', calendarViewMonth, calendarViewYear);
  
  // Right calendar - next month
  let rightMonth = calendarViewMonth + 1;
  let rightYear = calendarViewYear;
  if (rightMonth > 11) {
    rightMonth = 0;
    rightYear++;
  }
  renderMonth('rightCalendarDays', 'rightMonthLabel', rightMonth, rightYear);
}

function renderMonth(containerId, labelId, month, year) {
  const container = document.getElementById(containerId);
  const label = document.getElementById(labelId);
  
  const months = ['January', 'February', 'March', 'April', 'May', 'June',
                  'July', 'August', 'September', 'October', 'November', 'December'];
  label.textContent = `${months[month]} ${year}`;
  
  // Get first day of month and number of days
  const firstDay = new Date(year, month, 1).getDay();
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  
  let html = '';
  
  // Empty cells for days before first day
  for (let i = 0; i < firstDay; i++) {
    html += '<div class="calendar-day empty"></div>';
  }
  
  // Days of month
  for (let day = 1; day <= daysInMonth; day++) {
    const date = new Date(year, month, day);
    date.setHours(0, 0, 0, 0);
    
    let classes = 'calendar-day';
    
    // Check if it's today
    if (date.getTime() === today.getTime()) {
      classes += ' today';
    }
    
    // Check if it's in the future (disable)
    if (date > today) {
      classes += ' disabled';
    }
    
    // Check if it's in selected range
    if (dateRangeStart && dateRangeEnd) {
      const start = new Date(dateRangeStart);
      start.setHours(0, 0, 0, 0);
      const end = new Date(dateRangeEnd);
      end.setHours(0, 0, 0, 0);
      
      if (date.getTime() === start.getTime() && date.getTime() === end.getTime()) {
        classes += ' range-start range-end';
      } else if (date.getTime() === start.getTime()) {
        classes += ' range-start';
      } else if (date.getTime() === end.getTime()) {
        classes += ' range-end';
      } else if (date > start && date < end) {
        classes += ' in-range';
      }
    } else if (dateRangeStart) {
      const start = new Date(dateRangeStart);
      start.setHours(0, 0, 0, 0);
      if (date.getTime() === start.getTime()) {
        classes += ' range-start range-end';
      }
    }
    
    const dateStr = `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
    html += `<div class="${classes}" data-date="${dateStr}" onclick="selectCalendarDate('${dateStr}')">${day}</div>`;
  }
  
  container.innerHTML = html;
}

function selectCalendarDate(dateStr) {
  const clickedDate = new Date(dateStr);
  const today = new Date();
  today.setHours(23, 59, 59, 999);
  
  // Don't allow future dates
  if (clickedDate > today) return;
  
  // Clear active presets
  document.querySelectorAll('.preset-btn').forEach(btn => btn.classList.remove('active'));
  
  if (!dateRangeStart || (dateRangeStart && dateRangeEnd)) {
    // Start new selection
    dateRangeStart = clickedDate;
    dateRangeEnd = null;
  } else {
    // Complete selection
    if (clickedDate < dateRangeStart) {
      // Clicked date before start, swap
      dateRangeEnd = dateRangeStart;
      dateRangeStart = clickedDate;
    } else {
      dateRangeEnd = clickedDate;
    }
  }
  
  updateSelectedRangeDisplay();
  renderCalendars();
}

function updateSelectedRangeDisplay() {
  const startEl = document.getElementById('selectedStartDate');
  const endEl = document.getElementById('selectedEndDate');
  
  const formatDate = (date) => {
    if (!date) return '--';
    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  };
  
  startEl.textContent = formatDate(dateRangeStart);
  endEl.textContent = formatDate(dateRangeEnd || dateRangeStart);
}

function selectDatePreset(preset) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  
  // Clear previous active
  document.querySelectorAll('.preset-btn').forEach(btn => btn.classList.remove('active'));
  document.querySelector(`.preset-btn[data-preset="${preset}"]`)?.classList.add('active');
  
  switch (preset) {
    case 'today':
      dateRangeStart = new Date(today);
      dateRangeEnd = new Date(today);
      break;
    case 'yesterday':
      const yesterday = new Date(today);
      yesterday.setDate(yesterday.getDate() - 1);
      dateRangeStart = yesterday;
      dateRangeEnd = yesterday;
      break;
    case '7days':
      dateRangeStart = new Date(today);
      dateRangeStart.setDate(dateRangeStart.getDate() - 6);
      dateRangeEnd = new Date(today);
      break;
    case '30days':
      dateRangeStart = new Date(today);
      dateRangeStart.setDate(dateRangeStart.getDate() - 29);
      dateRangeEnd = new Date(today);
      break;
    case 'thisMonth':
      dateRangeStart = new Date(today.getFullYear(), today.getMonth(), 1);
      dateRangeEnd = new Date(today);
      break;
  }
  
  // Update calendar view to show the selected range
  if (dateRangeStart) {
    calendarViewMonth = dateRangeStart.getMonth();
    calendarViewYear = dateRangeStart.getFullYear();
  }
  
  updateSelectedRangeDisplay();
  renderCalendars();
}

function applyDateRange() {
  if (!dateRangeStart) {
    showToast('Please select a date range', 'warning');
    return;
  }
  
  // If no end date, use start as end
  if (!dateRangeEnd) {
    dateRangeEnd = new Date(dateRangeStart);
  }
  
  // Format dates for hidden inputs
  const formatDateForInput = (date) => {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  };
  
  document.getElementById('photoStartDate').value = formatDateForInput(dateRangeStart);
  document.getElementById('photoEndDate').value = formatDateForInput(dateRangeEnd);
  
  // Update display text
  const formatDisplay = (date) => {
    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  };
  
  const displayText = dateRangeStart.getTime() === dateRangeEnd.getTime() 
    ? formatDisplay(dateRangeStart)
    : `${formatDisplay(dateRangeStart)} - ${formatDisplay(dateRangeEnd)}`;
  
  document.getElementById('dateRangeText').textContent = displayText;
  
  closeDateRangePicker();
}

async function syncPhotos() {
  if (!selectedDevice) return;
  
  try {
    // First check current storage quota from server (accurate)
    let quotaFull = false;
    try {
      const quotaData = await api(`/devices/${getDeviceId(selectedDevice)}/photos/quota`);
      updateStorageDisplay(quotaData);
      quotaFull = quotaData.quotaFull || false;
    } catch (e) {
      // If quota check fails, use local display value
      const storagePercentText = document.getElementById('storagePercentText')?.textContent || '0%';
      const storagePercent = parseInt(storagePercentText) || 0;
      quotaFull = storagePercent >= 100;
    }
    
    // If quota is full, ask to delete first
    if (quotaFull) {
      const shouldDelete = confirm(
        '📸 Photo Storage Full!\n\n' +
        'Your 200MB photo storage quota is full.\n\n' +
        'To sync new photos, you need to delete existing photos first.\n\n' +
        'Would you like to delete all synced photos now?\n\n' +
        '(This only removes photos from the dashboard, not from the child device)'
      );
      
      if (shouldDelete) {
        await deleteAllPhotos();
        // After deletion, try to sync
        await sendCommand('sync_photos', { hours: 168 }, true);
        showToast('Photo sync request sent! Photos will sync from newest to oldest.', 'success', 5000);
        setTimeout(() => loadGallery(), 5000);
      }
      return;
    }
    
    // Send sync_photos command via FCM to the device
    await sendCommand('sync_photos', { hours: 168 }, true);
    showToast('Photo sync request sent! Please wait a moment for photos to load.', 'success', 5000);
    
    // Show loading and reload gallery after a delay for device to upload photos
    showLoadingOnGallery = true;
    showGalleryLoading(true);
    setTimeout(() => loadGallery(), 5000);
  } catch (error) {
    showToast('Failed to sync photos: ' + error.message, 'error');
  }
}

// Delete all synced photos from server (parent side only - doesn't affect child device)
async function deleteAllPhotos() {
  if (!selectedDevice) return;
  
  const count = document.getElementById('photoCount')?.textContent || '0 photos';
  const storageUsed = document.getElementById('storageUsedText')?.textContent || '0 MB';
  
  if (!confirm(`Are you sure you want to delete all synced photos from the server?\n\n${count} (${storageUsed})\n\nThis will only remove photos from the parent dashboard, not from the child device. Your storage quota will be reset.`)) {
    return;
  }
  
  try {
    const response = await api(`/devices/${getDeviceId(selectedDevice)}/photos/delete-all`, {
      method: 'DELETE'
    });
    
    if (response.success) {
      const freedMB = response.freedStorage ? (response.freedStorage / 1024 / 1024).toFixed(1) : 0;
      showToast(`Deleted ${response.deletedCount || 'all'} photos. Freed ${freedMB} MB.`, 'success');
      loadGallery();
    } else {
      showToast('Failed to delete photos: ' + (response.error || 'Unknown error'), 'error');
    }
  } catch (error) {
    showToast('Failed to delete photos: ' + error.message, 'error');
  }
}

// Sync Now - trigger immediate data sync from child device
async function syncNow() {
  if (!selectedDevice) {
    alert('Please select a device first');
    return;
  }
  
  // Check if device is offline - show instant feedback
  if (selectedDevice.isOnline === false) {
    showToast('📵 Device is OFFLINE - Mobile data or WiFi appears to be off on the child device', 'error', 5000);
    return;
  }
  
  try {
    // Show loading state
    const btn = document.getElementById('btnSyncNow');
    const originalHtml = btn.innerHTML;
    btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i><span>Syncing...</span>';
    btn.disabled = true;
    
    // Send sync command to device via FCM
    await sendCommand('sync_data', {}, true);
    
    // Wait a bit for the device to sync data
    await new Promise(resolve => setTimeout(resolve, 3000));
    
    // Refresh the dashboard data
    await refreshData();
    
    // Restore button
    btn.innerHTML = originalHtml;
    btn.disabled = false;
    
    alert('Sync request sent! Data has been refreshed.');
  } catch (error) {
    const btn = document.getElementById('btnSyncNow');
    btn.innerHTML = '<i class="fas fa-sync"></i><span>Sync Now</span>';
    btn.disabled = false;
    
    // Check if error is due to device being offline
    if (error.message && error.message.includes('offline')) {
      showToast('📵 Device is OFFLINE - Cannot sync when device has no internet connection', 'error', 5000);
    } else {
      alert('Failed to sync: ' + error.message);
    }
  }
}

// Download photo from gallery
async function downloadPhoto(photoId, fileName) {
  if (!selectedDevice) return;
  
  try {
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/photos/${photoId}`);
    const photo = data.photo;
    
    // Create download link
    const link = document.createElement('a');
    link.href = `data:${photo.mimeType || 'image/jpeg'};base64,${photo.image}`;
    link.download = fileName || 'photo.jpg';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  } catch (error) {
    alert('Failed to download photo: ' + error.message);
  }
}

// Store current photo for modal download
let currentViewedPhoto = null;

async function viewPhoto(photoId) {
  if (!selectedDevice) return;
  
  try {
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/photos/${photoId}`);
    const photo = data.photo;
    
    // Store for download button
    currentViewedPhoto = photo;
    
    // Update modal content
    document.getElementById('photoFileName').textContent = photo.fileName || 'Photo';
    document.getElementById('fullPhotoImage').src = `data:${photo.mimeType || 'image/jpeg'};base64,${photo.image}`;
    
    // Date taken
    const dateTaken = new Date(photo.dateTaken || photo.timestamp);
    document.getElementById('photoDate').textContent = dateTaken.toLocaleString();
    
    // Source/Album
    document.getElementById('photoSource').textContent = photo.source || 'Other';
    
    // Dimensions
    if (photo.width && photo.height) {
      document.getElementById('photoDimensions').textContent = `${photo.width} x ${photo.height}`;
    } else {
      document.getElementById('photoDimensions').textContent = 'Unknown';
    }
    
    // File size
    document.getElementById('photoSize').textContent = formatFileSize(photo.size);
    
    // Location
    const locationContainer = document.getElementById('photoLocationContainer');
    const locationText = document.getElementById('photoLocation');
    if (photo.location && (photo.location.latitude || photo.location.address)) {
      locationContainer.classList.remove('hidden');
      if (photo.location.address) {
        locationText.textContent = photo.location.address;
      } else if (photo.location.latitude && photo.location.longitude) {
        locationText.textContent = `${photo.location.latitude.toFixed(6)}, ${photo.location.longitude.toFixed(6)}`;
      }
    } else {
      locationText.textContent = 'No location data';
    }
    
    // File path
    document.getElementById('photoFilePath').textContent = photo.filePath || 'Unknown';
    
    document.getElementById('photoModal').classList.remove('hidden');
  } catch (error) {
    console.error('Failed to load photo:', error);
  }
}

// Download current viewed photo from modal
function downloadCurrentPhoto() {
  if (!currentViewedPhoto) return;
  
  const link = document.createElement('a');
  link.href = `data:${currentViewedPhoto.mimeType || 'image/jpeg'};base64,${currentViewedPhoto.image}`;
  link.download = currentViewedPhoto.fileName || 'photo.jpg';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
}

function closePhotoModal() {
  document.getElementById('photoModal').classList.add('hidden');
  currentViewedPhoto = null;
}

function formatFileSize(bytes) {
  if (!bytes) return 'Unknown';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

// Settings
async function loadSettings() {
  if (!selectedDevice) return;
  
  try {
    const data = await api(`/devices/${getDeviceId(selectedDevice)}`);
    const settings = data.device.settings;
    
    document.getElementById('toggleScreenMirror').checked = settings.screenMirrorEnabled;
    document.getElementById('toggleCamera').checked = settings.cameraEnabled;
    document.getElementById('toggleLiveListen').checked = settings.liveListenEnabled;
    document.getElementById('toggleCallRecord').checked = settings.callRecordEnabled;
    document.getElementById('toggleLocation').checked = settings.locationTrackEnabled;
    
    // Load permissions for this device
    loadPermissions();
    
    // Check PIN status
    checkPinStatus();
  } catch (error) {
    console.error('Failed to load settings:', error);
  }
}

async function saveSettings() {
  if (!selectedDevice) return;
  
  const settings = {
    screenMirrorEnabled: document.getElementById('toggleScreenMirror').checked,
    cameraEnabled: document.getElementById('toggleCamera').checked,
    liveListenEnabled: document.getElementById('toggleLiveListen').checked,
    callRecordEnabled: document.getElementById('toggleCallRecord').checked,
    locationTrackEnabled: document.getElementById('toggleLocation').checked
  };
  
  try {
    await api(`/devices/${getDeviceId(selectedDevice)}/settings`, {
      method: 'PUT',
      body: JSON.stringify({ settings })
    });
  } catch (error) {
    console.error('Failed to save settings:', error);
    alert('Failed to save settings');
  }
}

// Uninstall app from child device
async function uninstallApp() {
  if (!selectedDevice) return;
  
  const confirmed = confirm(
    '⚠️ WARNING: This will uninstall FamilyGuard from the child device.\n\n' +
    'You will lose all monitoring capabilities and the device will need to be set up again.\n\n' +
    'Are you sure you want to continue?'
  );
  
  if (!confirmed) return;
  
  // Double confirmation for safety
  const doubleConfirm = confirm(
    'FINAL CONFIRMATION\n\n' +
    'This action cannot be undone remotely. The app will be uninstalled from the child device.\n\n' +
    'Click OK to proceed with uninstallation.'
  );
  
  if (!doubleConfirm) return;
  
  try {
    await sendCommand('uninstall_app');
    alert('Uninstall command sent to device. The app will be uninstalled shortly.');
  } catch (error) {
    alert('Failed to send uninstall command: ' + error.message);
  }
}

// Apply app disguise mode
async function applyDisguiseMode() {
  if (!selectedDevice) {
    alert('Please select a device first');
    return;
  }
  
  const selectedMode = document.querySelector('input[name="disguiseMode"]:checked')?.value;
  if (!selectedMode) {
    alert('Please select a disguise mode');
    return;
  }
  
  let confirmMsg = '';
  switch (selectedMode) {
    case 'normal':
      confirmMsg = 'Show app as "FamilyGuard" - the normal parental control interface.';
      break;
    case 'applock':
      confirmMsg = 'Disguise as "App Lock" - shows a fully functional app lock interface. Tap the app icon 7 times to reveal the real app.';
      break;
    case 'system':
      confirmMsg = 'Disguise as "System Update" - appears as a system service.';
      break;
    case 'hidden':
      confirmMsg = 'COMPLETELY HIDE the app from launcher.\n\nTo access: Open familyguard://open URL.';
      break;
  }
  
  if (!confirm(`Apply "${selectedMode}" disguise mode?\n\n${confirmMsg}`)) {
    return;
  }
  
  try {
    await sendCommand('set_disguise_mode', { mode: selectedMode });
    
    // Show success with reminder
    let reminder = '';
    if (selectedMode === 'hidden') {
      reminder = '\n\n📱 To open the hidden app:\n• Open URL: familyguard://open';
    } else if (selectedMode === 'applock') {
      reminder = '\n\n📱 To reveal real app:\n• Tap the App Lock title 7 times quickly\n• Enter admin PIN (default: 0007)';
    }
    
    alert(`✅ Disguise mode "${selectedMode}" applied successfully!${reminder}`);
    
  } catch (error) {
    console.error('Failed to apply disguise mode:', error);
    alert('Failed to apply disguise mode: ' + error.message);
  }
}

async function removeDevice() {
  if (!selectedDevice) return;
  
  // Show comprehensive warning about data deletion
  const deviceName = selectedDevice.name || selectedDevice.deviceName || 'this device';
  const warningMessage = `⚠️ IMPORTANT WARNING ⚠️\n\nUnpairing "${deviceName}" will PERMANENTLY DELETE all data collected from this device:\n\n• All photos synced\n• All call logs\n• All SMS messages\n• All notifications\n• All browser history\n• All keystroke sessions\n• All social media messages\n• All location history\n• All app usage data\n• All screenshots\n\nThis action CANNOT be undone!\n\nAre you sure you want to continue?`;
  
  if (!confirm(warningMessage)) {
    return;
  }
  
  // Double confirmation
  if (!confirm('This is your FINAL confirmation.\n\nAll data from this device will be deleted permanently.\n\nProceed?')) {
    return;
  }
  
  // Check if PIN is required
  try {
    const pinCheck = await api('/auth/has-pin');
    
    if (pinCheck.hasPinSet) {
      // Show PIN prompt
      const pin = prompt('Enter your security PIN to remove this device:');
      if (!pin) {
        return; // User cancelled
      }
      
      // Verify and remove with PIN
      await api(`/devices/${getDeviceId(selectedDevice)}`, { 
        method: 'DELETE',
        body: JSON.stringify({ pin })
      });
    } else {
      // No PIN set, just remove
      await api(`/devices/${getDeviceId(selectedDevice)}`, { method: 'DELETE' });
    }
    
    alert('Device removed successfully');
    selectedDevice = null;
    await loadDevices();
    navigateTo('dashboard');
  } catch (error) {
    alert('Failed to remove device: ' + error.message);
  }
}

// ========== BLOCKED APPS MANAGEMENT ==========
// Store apps data for blocking
let installedApps = [];
let blockedAppsSet = new Set();

async function showBlockedAppsModal() {
  if (!selectedDevice) {
    alert('Please select a device first');
    return;
  }
  
  try {
    // Get apps data from server
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/apps`);
    installedApps = data.usage || [];
    blockedAppsSet = new Set(data.blockedApps || []);
    
    // Create modal if not exists
    let modal = document.getElementById('blockedAppsModal');
    if (!modal) {
      modal = document.createElement('div');
      modal.id = 'blockedAppsModal';
      modal.className = 'modal';
      document.body.appendChild(modal);
    }
    
    // Sort apps - blocked first, then by name
    const sortedApps = [...installedApps].sort((a, b) => {
      const aBlocked = blockedAppsSet.has(a._id);
      const bBlocked = blockedAppsSet.has(b._id);
      if (aBlocked && !bBlocked) return -1;
      if (!aBlocked && bBlocked) return 1;
      return (a.appName || a._id).localeCompare(b.appName || b._id);
    });
    
    modal.innerHTML = `
      <div class="modal-content modal-large">
        <div class="modal-header">
          <h2><i class="fas fa-ban"></i> Manage Blocked Apps</h2>
          <button class="close-btn" onclick="closeBlockedAppsModal()"><i class="fas fa-times"></i></button>
        </div>
        <div class="modal-body">
          <p class="modal-desc">Select apps to block. Blocked apps will be restricted on the child's device.</p>
          <div class="search-box">
            <i class="fas fa-search"></i>
            <input type="text" id="appSearchInput" placeholder="Search apps..." oninput="filterApps(this.value)">
          </div>
          <div class="apps-list" id="appsListContainer">
            ${sortedApps.length > 0 ? sortedApps.map(app => `
              <div class="app-block-item ${blockedAppsSet.has(app._id) ? 'blocked' : ''}" data-package="${app._id}" data-name="${(app.appName || app._id).toLowerCase()}">
                <div class="app-block-icon">
                  <i class="fas fa-mobile-alt"></i>
                </div>
                <div class="app-block-info">
                  <h4>${app.appName || app._id}</h4>
                  <small>${app._id}</small>
                </div>
                <label class="toggle-switch">
                  <input type="checkbox" ${blockedAppsSet.has(app._id) ? 'checked' : ''} onchange="toggleAppBlock('${app._id}', this.checked)">
                  <span class="toggle-slider"></span>
                </label>
              </div>
            `).join('') : '<p class="empty-state">No apps found. Wait for device to sync.</p>'}
          </div>
        </div>
        <div class="modal-footer">
          <span class="blocked-count" id="blockedCountText">${blockedAppsSet.size} app(s) blocked</span>
          <button class="btn secondary" onclick="closeBlockedAppsModal()">Close</button>
        </div>
      </div>
    `;
    
    modal.classList.remove('hidden');
  } catch (error) {
    alert('Failed to load apps: ' + error.message);
  }
}

function closeBlockedAppsModal() {
  const modal = document.getElementById('blockedAppsModal');
  if (modal) modal.classList.add('hidden');
}

function filterApps(searchTerm) {
  const items = document.querySelectorAll('.app-block-item');
  const term = searchTerm.toLowerCase();
  
  items.forEach(item => {
    const name = item.dataset.name;
    const pkg = item.dataset.package.toLowerCase();
    if (name.includes(term) || pkg.includes(term)) {
      item.style.display = 'flex';
    } else {
      item.style.display = 'none';
    }
  });
}

async function toggleAppBlock(packageName, shouldBlock) {
  if (!selectedDevice) return;
  
  try {
    if (shouldBlock) {
      blockedAppsSet.add(packageName);
    } else {
      blockedAppsSet.delete(packageName);
    }
    
    const newBlocked = Array.from(blockedAppsSet);
    
    // Update UI immediately
    const item = document.querySelector(`.app-block-item[data-package="${packageName}"]`);
    if (item) {
      if (shouldBlock) {
        item.classList.add('blocked');
      } else {
        item.classList.remove('blocked');
      }
    }
    
    // Update count
    document.getElementById('blockedCountText').textContent = `${blockedAppsSet.size} app(s) blocked`;
    
    // Update on server
    await api(`/devices/${getDeviceId(selectedDevice)}/settings`, {
      method: 'PUT',
      body: JSON.stringify({ blockedApps: newBlocked })
    });
    
    // Send command to device
    await sendCommand('update_blocked_apps', { apps: newBlocked }, true);
    
    // Refresh app usage in background
    loadAppUsage();
  } catch (error) {
    // Revert UI on error
    if (shouldBlock) {
      blockedAppsSet.delete(packageName);
    } else {
      blockedAppsSet.add(packageName);
    }
    alert('Failed to update: ' + error.message);
  }
}

async function blockApp(packageName) {
  await toggleAppBlock(packageName, true);
}

async function unblockApp(packageName) {
  if (!confirm(`Unblock this app?`)) return;
  await toggleAppBlock(packageName, false);
}

// Commands - use WebSocket fallback when FCM is not available
async function sendCommand(command, params = {}, silent = false) {
  if (!selectedDevice) return;
  
  try {
    await api(`/devices/${getDeviceId(selectedDevice)}/command`, {
      method: 'POST',
      body: JSON.stringify({ command, params })
    });
    if (!silent) {
      debugLog(`Command '${command}' sent via FCM`);
    }
  } catch (error) {
    console.warn('FCM command failed, trying WebSocket fallback:', error.message);
    
    // Try WebSocket fallback
    try {
      const deviceId = selectedDevice.deviceId || getDeviceId(selectedDevice);
      const wsUrl = `${WS_BASE}?session=${deviceId}_command&role=parent&deviceId=${deviceId}&type=command`;
      
      const cmdSocket = new WebSocket(wsUrl);
      cmdSocket.onopen = () => {
        cmdSocket.send(JSON.stringify({
          type: 'command',
          command,
          params
        }));
        if (!silent) {
          debugLog(`Command '${command}' sent via WebSocket`);
        }
        setTimeout(() => cmdSocket.close(), 2000);
      };
      cmdSocket.onerror = () => {
        if (!silent) {
          alert('Device is offline or not connected. Command queued for when device comes online.');
        }
      };
    } catch (wsError) {
      if (!silent) {
        alert('Failed to send command: ' + error.message);
      }
    }
  }
}

// Streaming
let streamTimeout = null;
let streamAttempts = 0;
const MAX_STREAM_ATTEMPTS = 3;
const STREAM_TIMEOUT_MS = 15000; // 15 seconds

// Audio context for live listen
let audioContext = null;
let audioQueue = [];
let isPlayingAudio = false;

function initAudioContext() {
  if (!audioContext) {
    audioContext = new (window.AudioContext || window.webkitAudioContext)({
      sampleRate: 16000
    });
    // Create a gain node for volume control
    audioGainNode = audioContext.createGain();
    audioGainNode.gain.value = 2.0; // Boost volume
    audioGainNode.connect(audioContext.destination);
  }
  if (audioContext.state === 'suspended') {
    audioContext.resume();
  }
  return audioContext;
}

let audioGainNode = null;
let audioPlayTime = 0;

async function playAudioChunk(base64Data) {
  try {
    const ctx = initAudioContext();
    
    // Decode base64 to raw PCM data
    const binaryString = atob(base64Data);
    const len = binaryString.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
      bytes[i] = binaryString.charCodeAt(i);
    }
    
    // Convert PCM 16-bit to Float32
    const pcm16 = new Int16Array(bytes.buffer);
    const float32 = new Float32Array(pcm16.length);
    for (let i = 0; i < pcm16.length; i++) {
      float32[i] = pcm16[i] / 32768.0;
    }
    
    // Create audio buffer
    const audioBuffer = ctx.createBuffer(1, float32.length, 16000);
    audioBuffer.getChannelData(0).set(float32);
    
    // Schedule playback with proper timing to avoid gaps
    const source = ctx.createBufferSource();
    source.buffer = audioBuffer;
    source.connect(audioGainNode || ctx.destination);
    
    // Calculate when to play this buffer
    const now = ctx.currentTime;
    if (audioPlayTime < now) {
      audioPlayTime = now;
    }
    
    source.start(audioPlayTime);
    audioPlayTime += audioBuffer.duration;
    
    // Keep queue from getting too far ahead
    if (audioPlayTime - now > 2.0) {
      audioPlayTime = now + 0.1;
    }
  } catch (e) {
    console.error('Error playing audio:', e);
  }
}

function playNextAudioBuffer() {
  if (audioQueue.length === 0) {
    isPlayingAudio = false;
    return;
  }
  
  isPlayingAudio = true;
  const buffer = audioQueue.shift();
  const source = audioContext.createBufferSource();
  source.buffer = buffer;
  source.connect(audioContext.destination);
  source.onended = playNextAudioBuffer;
  source.start();
}

function stopAudioPlayback() {
  audioQueue = [];
  isPlayingAudio = false;
  if (audioContext) {
    audioContext.close();
    audioContext = null;
  }
}

// ================== WebRTC Streaming ==================
let webrtcPeerConnection = null;
let webrtcSignalingSocket = null;
let webrtcRemoteStream = null;
let pendingIceCandidates = [];
let isRemoteDescriptionSet = false;

// ICE servers for STUN/TURN - TURN is essential for NAT traversal on mobile networks
// Multiple TURN providers for reliability
const ICE_SERVERS = [
  { urls: 'stun:stun.l.google.com:19302' },
  { urls: 'stun:stun1.l.google.com:19302' },
  { urls: 'stun:stun2.l.google.com:19302' },
  { urls: 'stun:stun3.l.google.com:19302' },
  // Metered.ca TURN servers
  { urls: 'turn:a.relay.metered.ca:80', username: '83eebabf8b4cce9d5dbcbbb4', credential: '2D7JvfkOQtBdYW3R' },
  { urls: 'turn:a.relay.metered.ca:80?transport=tcp', username: '83eebabf8b4cce9d5dbcbbb4', credential: '2D7JvfkOQtBdYW3R' },
  { urls: 'turn:a.relay.metered.ca:443', username: '83eebabf8b4cce9d5dbcbbb4', credential: '2D7JvfkOQtBdYW3R' },
  { urls: 'turn:a.relay.metered.ca:443?transport=tcp', username: '83eebabf8b4cce9d5dbcbbb4', credential: '2D7JvfkOQtBdYW3R' },
  { urls: 'turns:a.relay.metered.ca:443', username: '83eebabf8b4cce9d5dbcbbb4', credential: '2D7JvfkOQtBdYW3R' },
  // OpenRelay TURN servers (free, no auth required)
  { urls: 'turn:openrelay.metered.ca:80', username: 'openrelayproject', credential: 'openrelayproject' },
  { urls: 'turn:openrelay.metered.ca:443', username: 'openrelayproject', credential: 'openrelayproject' },
  { urls: 'turn:openrelay.metered.ca:443?transport=tcp', username: 'openrelayproject', credential: 'openrelayproject' }
];

let webrtcRetryCount = 0;
const MAX_WEBRTC_RETRIES = 3;
let webrtcConnectionTimeout = null;
let deviceWakeupInterval = null;

function startWebRTCStream(type) {
  if (!selectedDevice) {
    alert('Please select a device first');
    return;
  }
  
  const modal = document.getElementById('streamModal');
  const title = document.getElementById('streamTitle');
  const streamVideo = document.getElementById('streamVideo');
  
  const titles = {
    screen: 'Screen Mirror (WebRTC)',
    camera: 'Remote Camera (WebRTC)',
    audio: 'Live Listen (WebRTC)'
  };
  
  currentStreamType = type;
  title.textContent = titles[type];
  modal.classList.remove('hidden');
  
  // Check if device is likely online (lastSeen within 10 minutes is considered potentially reachable)
  const isLikelyOnline = selectedDevice.isOnline || selectedDevice.online || 
    (selectedDevice.lastSeen && (Date.now() - new Date(selectedDevice.lastSeen).getTime()) < 10 * 60 * 1000);
  
  // Always try to connect directly first, even if device appears offline
  // The FCM wakeup will be sent in parallel
  streamVideo.innerHTML = `
    <div class="device-wakeup-status">
      <i class="fas fa-satellite-dish fa-spin" style="font-size: 48px; color: var(--primary);"></i>
      <p class="connecting" style="margin-top: 16px;">📡 Connecting to device...</p>
      <p style="color: var(--text-muted); font-size: 0.85rem;">${isLikelyOnline ? 'Establishing stream connection...' : 'Sending wake-up signal to device...'}</p>
      <p style="color: var(--text-muted); font-size: 0.8rem; margin-top: 8px;">This may take up to 30 seconds...</p>
      <div class="wakeup-progress" style="margin-top: 12px;">
        <div class="progress-bar" style="width: 100%; height: 4px; background: var(--surface-light); border-radius: 2px; overflow: hidden;">
          <div class="progress-fill" id="wakeupProgress" style="width: 0%; height: 100%; background: var(--primary); transition: width 0.5s;"></div>
        </div>
      </div>
    </div>
  `;
  
  // Send FCM command immediately to wake up device
  const commands = {
    screen: 'start_webrtc_screen',
    camera: 'start_webrtc_camera',
    audio: 'start_webrtc_audio'
  };
  sendCommand(commands[type], {}, true);
  
  // Start connection with wakeup retry sequence
  startWebRTCWithWakeup(type);
}

// Unified WebRTC connection with wakeup retries
function startWebRTCWithWakeup(type) {
  const streamVideo = document.getElementById('streamVideo');
  const commands = {
    screen: 'start_webrtc_screen',
    camera: 'start_webrtc_camera',
    audio: 'start_webrtc_audio'
  };
  
  let wakeupAttempts = 0;
  const maxAttempts = 6; // 6 attempts over 30 seconds
  const attemptInterval = 5000; // 5 seconds between attempts
  
  // Clear any existing intervals
  if (deviceWakeupInterval) {
    clearInterval(deviceWakeupInterval);
  }
  if (webrtcConnectionTimeout) {
    clearTimeout(webrtcConnectionTimeout);
  }
  
  // Update progress
  wakeupAttempts++;
  updateWakeupProgress(wakeupAttempts, maxAttempts);
  
  // Initialize WebRTC connection immediately
  initializeWebRTCConnection(type, false);
  
  // Set up periodic FCM retry to keep waking device
  deviceWakeupInterval = setInterval(async () => {
    wakeupAttempts++;
    
    if (wakeupAttempts >= maxAttempts) {
      clearInterval(deviceWakeupInterval);
      deviceWakeupInterval = null;
      
      // Only show error if no video is playing
      if (!streamVideo.querySelector('video')) {
        streamVideo.innerHTML = `
          <div class="device-offline-status">
            <i class="fas fa-exclamation-triangle" style="font-size: 48px; color: var(--warning);"></i>
            <p style="margin-top: 16px; font-weight: 600; color: var(--text-primary);">Device Not Responding</p>
            <p style="color: var(--text-muted); font-size: 0.9rem; max-width: 300px; margin: 8px auto;">
              The device may be turned off, have no internet connection, or the app may have been force stopped.
            </p>
            <div style="margin-top: 16px; display: flex; gap: 12px; justify-content: center;">
              <button class="btn-primary" onclick="startWebRTCStream('${type}')">
                <i class="fas fa-redo"></i> Retry
              </button>
              <button class="btn-secondary" onclick="closeStreamModal()">
                Cancel
              </button>
            </div>
          </div>
        `;
      }
      return;
    }
    
    // Update progress
    updateWakeupProgress(wakeupAttempts, maxAttempts);
    
    // Send another FCM wake-up command
    await sendCommand(commands[type], {}, true);
    debugLog(`[WebRTC] Wake-up attempt ${wakeupAttempts}/${maxAttempts}`);
    
  }, attemptInterval);
  
  // Also set an overall timeout
  webrtcConnectionTimeout = setTimeout(() => {
    if (deviceWakeupInterval) {
      clearInterval(deviceWakeupInterval);
      deviceWakeupInterval = null;
    }
  }, (maxAttempts + 1) * attemptInterval);
}

function updateWakeupProgress(current, total) {
  const progressEl = document.getElementById('wakeupProgress');
  if (progressEl) {
    const percent = (current / total) * 100;
    progressEl.style.width = `${percent}%`;
  }
}

function initializeWebRTCConnection(type, isRetry = false) {
  const streamVideo = document.getElementById('streamVideo');
  
  if (!isRetry) {
    streamVideo.innerHTML = '<p class="connecting">Initializing WebRTC connection...</p>';
  }
  
  // Send command to child device to start WebRTC streaming
  const commands = {
    screen: 'start_webrtc_screen',
    camera: 'start_webrtc_camera',
    audio: 'start_webrtc_audio'
  };
  
  if (!isRetry) {
    sendCommand(commands[type], {}, true);
  }
  
  // Connect to WebRTC signaling server
  const deviceId = selectedDevice.deviceId || selectedDevice.id;
  const sessionId = `${deviceId}_${type}_webrtc`;
  const wsUrl = `${WS_BASE}/webrtc?session=${sessionId}&role=receiver&deviceId=${deviceId}&type=${type}`;
  
  debugLog('[WebRTC] Connecting to signaling:', wsUrl);
  
  // Close existing connections
  closeWebRTCConnection();
  
  // Reset state
  pendingIceCandidates = [];
  isRemoteDescriptionSet = false;
  
  // Connect to signaling server
  webrtcSignalingSocket = new WebSocket(wsUrl);
  
  // Set connection timeout (15 seconds to receive first message)
  const connectionTimeout = setTimeout(() => {
    if (webrtcSignalingSocket && webrtcSignalingSocket.readyState === WebSocket.OPEN) {
      // Socket is open but no stream received
      if (!streamVideo.querySelector('video')) {
        streamVideo.innerHTML = `
          <div style="text-align: center;">
            <i class="fas fa-clock" style="font-size: 48px; color: var(--warning);"></i>
            <p class="connecting">⏳ Still waiting for device stream...</p>
            <p style="color: var(--text-muted); font-size: 0.85rem;">Device may be starting up the camera service.</p>
            <p style="color: var(--text-muted); font-size: 0.85rem; margin-top: 8px;">Make sure the device has:</p>
            <ul style="color: var(--text-muted); font-size: 0.8rem; text-align: left; max-width: 280px; margin: 8px auto;">
              <li>Active internet connection</li>
              <li>App running in background</li>
              <li>Camera permission granted</li>
            </ul>
            <div style="margin-top: 16px; display: flex; gap: 12px; justify-content: center;">
              <button class="btn-primary" onclick="startWebRTCStream('${type}')">
                <i class="fas fa-redo"></i> Retry
              </button>
              <button class="btn-secondary" onclick="closeStreamModal()">
                Cancel
              </button>
            </div>
          </div>
        `;
      }
    }
  }, 15000);
  
  // Set overall stream timeout (45 seconds max wait)
  const overallTimeout = setTimeout(() => {
    if (!streamVideo.querySelector('video')) {
      streamVideo.innerHTML = `
        <div style="text-align: center;">
          <i class="fas fa-exclamation-triangle" style="font-size: 48px; color: var(--warning);"></i>
          <p style="margin-top: 16px; font-weight: 600; color: var(--text-primary);">Stream Not Available</p>
          <p style="color: var(--text-muted); font-size: 0.9rem; max-width: 300px; margin: 8px auto;">
            Unable to establish stream with device. The app may not be running or the device is offline.
          </p>
          <div style="margin-top: 16px; display: flex; gap: 12px; justify-content: center;">
            <button class="btn-primary" onclick="startWebRTCStream('${type}')">
              <i class="fas fa-redo"></i> Try Again
            </button>
            <button class="btn-secondary" onclick="closeStreamModal()">
              Close
            </button>
          </div>
        </div>
      `;
      closeWebRTCConnection();
    }
  }, 45000);
  
  webrtcSignalingSocket.onopen = () => {
    debugLog('[WebRTC] Signaling connected');
    
    // Clear wakeup sequence if still running
    if (deviceWakeupInterval) {
      clearInterval(deviceWakeupInterval);
      deviceWakeupInterval = null;
    }
    
    streamVideo.innerHTML = '<p class="connecting">✅ Connected! Waiting for device stream...</p>';
    
    // Send join message
    webrtcSignalingSocket.send(JSON.stringify({
      type: 'join',
      deviceId: deviceId,
      streamType: type,
      role: 'receiver'
    }));
  };
  
  webrtcSignalingSocket.onmessage = (event) => {
    try {
      clearTimeout(connectionTimeout);
      clearTimeout(overallTimeout);
      const message = JSON.parse(event.data);
      handleWebRTCSignalingMessage(message, type);
    } catch (e) {
      console.error('[WebRTC] Error parsing message:', e);
    }
  };
  
  webrtcSignalingSocket.onerror = (error) => {
    console.error('[WebRTC] Signaling error:', error);
    clearTimeout(connectionTimeout);
    clearTimeout(overallTimeout);
    streamVideo.innerHTML = `
      <div style="text-align: center;">
        <i class="fas fa-exclamation-circle" style="font-size: 48px; color: var(--error);"></i>
        <p style="margin-top: 12px; color: var(--error);">Connection error</p>
        <div style="margin-top: 16px; display: flex; gap: 12px; justify-content: center;">
          <button class="btn-primary" onclick="startWebRTCStream('${type}')">
            <i class="fas fa-redo"></i> Retry
          </button>
          <button class="btn-secondary" onclick="closeStreamModal()">
            Cancel
          </button>
        </div>
      </div>
    `;
  };
  
  webrtcSignalingSocket.onclose = () => {
    debugLog('[WebRTC] Signaling closed');
    clearTimeout(connectionTimeout);
    clearTimeout(overallTimeout);
  };
}

function handleWebRTCSignalingMessage(message, type) {
  debugLog('[WebRTC] Received:', message.type);
  const streamVideo = document.getElementById('streamVideo');
  
  // Clear wakeup sequence if we receive any valid signal from device
  if (['sender_joined', 'offer', 'stream_started'].includes(message.type)) {
    if (deviceWakeupInterval) {
      clearInterval(deviceWakeupInterval);
      deviceWakeupInterval = null;
    }
  }
  
  switch (message.type) {
    case 'sender_joined':
      streamVideo.innerHTML = `
        <div style="text-align: center;">
          <i class="fas fa-check-circle" style="font-size: 48px; color: #10b981;"></i>
          <p class="connecting" style="margin-top: 12px;">✅ Device connected!</p>
          <p style="color: var(--text-muted); font-size: 0.85rem;">Establishing video stream...</p>
        </div>
      `;
      break;
      
    case 'offer':
      handleWebRTCOffer(message, type);
      break;
      
    case 'ice_candidate':
      handleRemoteIceCandidate(message);
      break;
      
    case 'stream_started':
      debugLog('[WebRTC] Stream started');
      break;
      
    case 'stream_stopped':
    case 'sender_left':
      streamVideo.innerHTML = `
        <div style="text-align: center;">
          <i class="fas fa-stop-circle" style="font-size: 48px; color: var(--text-muted);"></i>
          <p style="margin-top: 12px; color: var(--text-primary);">Stream ended by device</p>
          <button class="btn-primary" style="margin-top: 12px;" onclick="startWebRTCStream('${type}')">
            <i class="fas fa-redo"></i> Reconnect
          </button>
        </div>
      `;
      break;
      
    case 'waiting':
      // Only update if there's no video element already showing
      if (!streamVideo.querySelector('video')) {
        streamVideo.innerHTML = `
          <div style="text-align: center;">
            <i class="fas fa-satellite-dish fa-pulse" style="font-size: 48px; color: var(--primary);"></i>
            <p class="connecting" style="margin-top: 12px;">📡 Waiting for device stream...</p>
            <p style="color: var(--text-muted); font-size: 0.85rem;">Device is starting the camera service.</p>
            <p style="color: var(--text-muted); font-size: 0.8rem; margin-top: 12px;">This may take 10-20 seconds...</p>
          </div>
        `;
      }
      break;
      
    case 'error':
      streamVideo.innerHTML = `
        <div style="text-align: center;">
          <i class="fas fa-exclamation-circle" style="font-size: 48px; color: var(--error);"></i>
          <p style="margin-top: 12px; color: var(--error);">Error: ${message.message || 'Unknown error'}</p>
          <button class="btn-primary" style="margin-top: 12px;" onclick="startWebRTCStream('${type}')">
            <i class="fas fa-redo"></i> Retry
          </button>
        </div>
      `;
      break;
      
    case 'ping':
      webrtcSignalingSocket?.send(JSON.stringify({ type: 'pong' }));
      break;
  }
}

async function handleWebRTCOffer(message, type) {
  try {
    debugLog('[WebRTC] Processing offer');
    const streamVideo = document.getElementById('streamVideo');
    
    // Create peer connection with full ICE configuration
    webrtcPeerConnection = new RTCPeerConnection({ 
      iceServers: ICE_SERVERS,
      iceCandidatePoolSize: 10,
      bundlePolicy: 'max-bundle',
      rtcpMuxPolicy: 'require'
    });
    
    debugLog('[WebRTC] Peer connection created with', ICE_SERVERS.length, 'ICE servers');
    
    // Handle incoming tracks
    webrtcPeerConnection.ontrack = (event) => {
      debugLog('[WebRTC] Track received:', event.track.kind);
      
      if (!webrtcRemoteStream) {
        webrtcRemoteStream = new MediaStream();
      }
      webrtcRemoteStream.addTrack(event.track);
      
      // Display based on track type
      if (event.track.kind === 'video') {
        displayWebRTCVideo(webrtcRemoteStream);
      } else if (event.track.kind === 'audio') {
        if (type === 'audio') {
          displayWebRTCAudio(webrtcRemoteStream);
        } else {
          // Add audio to video element
          const video = streamVideo.querySelector('video');
          if (video && video.srcObject) {
            video.srcObject.addTrack(event.track);
          }
        }
      }
    };
    
    // Handle ICE candidates
    webrtcPeerConnection.onicecandidate = (event) => {
      if (event.candidate) {
        // Log candidate type for debugging
        const candidateType = event.candidate.candidate.includes('relay') ? 'RELAY/TURN' : 
                              event.candidate.candidate.includes('srflx') ? 'SRFLX/STUN' : 'HOST';
        debugLog('[WebRTC] Sending ICE candidate:', candidateType, event.candidate.candidate.substring(0, 80));
        webrtcSignalingSocket?.send(JSON.stringify({
          type: 'ice_candidate',
          candidate: event.candidate.candidate,
          sdpMid: event.candidate.sdpMid,
          sdpMLineIndex: event.candidate.sdpMLineIndex
        }));
      } else {
        debugLog('[WebRTC] ICE gathering complete');
      }
    };
    
    // Log ICE gathering state
    webrtcPeerConnection.onicegatheringstatechange = () => {
      debugLog('[WebRTC] ICE gathering state:', webrtcPeerConnection.iceGatheringState);
    };
    
    // Handle connection state changes
    webrtcPeerConnection.onconnectionstatechange = () => {
      debugLog('[WebRTC] Connection state:', webrtcPeerConnection.connectionState);
      
      switch (webrtcPeerConnection.connectionState) {
        case 'connected':
          debugLog('[WebRTC] Connected!');
          webrtcRetryCount = 0; // Reset retry count on success
          break;
        case 'disconnected':
          streamVideo.innerHTML = '<p class="connecting">Connection interrupted, waiting to reconnect...</p>';
          break;
        case 'failed':
          debugLog('[WebRTC] Connection failed, retry count:', webrtcRetryCount);
          if (webrtcRetryCount < MAX_WEBRTC_RETRIES) {
            webrtcRetryCount++;
            streamVideo.innerHTML = `<p class="connecting">Connection failed. Retrying (${webrtcRetryCount}/${MAX_WEBRTC_RETRIES})...</p>`;
            // Try ICE restart
            if (webrtcPeerConnection) {
              webrtcPeerConnection.restartIce();
            }
          } else {
            streamVideo.innerHTML = '<p class="error">Connection failed after multiple retries. Please try again.</p>';
          }
          break;
      }
    };
    
    // Handle ICE connection state
    webrtcPeerConnection.oniceconnectionstatechange = () => {
      debugLog('[WebRTC] ICE state:', webrtcPeerConnection.iceConnectionState);
      if (webrtcPeerConnection.iceConnectionState === 'failed') {
        // Try ICE restart
        debugLog('[WebRTC] Attempting ICE restart...');
        webrtcPeerConnection.restartIce();
      }
    };
    
    // Set remote description (offer)
    const offer = new RTCSessionDescription({
      type: 'offer',
      sdp: message.sdp
    });
    
    await webrtcPeerConnection.setRemoteDescription(offer);
    isRemoteDescriptionSet = true;
    debugLog('[WebRTC] Remote description set');
    
    // Process pending ICE candidates
    for (const candidate of pendingIceCandidates) {
      await webrtcPeerConnection.addIceCandidate(candidate);
    }
    pendingIceCandidates = [];
    
    // Create and send answer
    const answer = await webrtcPeerConnection.createAnswer();
    await webrtcPeerConnection.setLocalDescription(answer);
    
    webrtcSignalingSocket?.send(JSON.stringify({
      type: 'answer',
      sdp: answer.sdp
    }));
    
    debugLog('[WebRTC] Answer sent');
    
  } catch (e) {
    console.error('[WebRTC] Error handling offer:', e);
    document.getElementById('streamVideo').innerHTML = 
      `<p class="error">Failed to establish connection: ${e.message}</p>`;
  }
}

async function handleRemoteIceCandidate(message) {
  try {
    const candidate = new RTCIceCandidate({
      candidate: message.candidate,
      sdpMid: message.sdpMid,
      sdpMLineIndex: message.sdpMLineIndex
    });
    
    if (isRemoteDescriptionSet && webrtcPeerConnection) {
      await webrtcPeerConnection.addIceCandidate(candidate);
      debugLog('[WebRTC] ICE candidate added');
    } else {
      pendingIceCandidates.push(candidate);
      debugLog('[WebRTC] ICE candidate queued');
    }
  } catch (e) {
    console.error('[WebRTC] Error adding ICE candidate:', e);
  }
}

function displayWebRTCVideo(stream) {
  const streamVideo = document.getElementById('streamVideo');
  const streamType = currentStreamType;
  streamVideo.innerHTML = '';
  
  // Create video element directly
  const video = document.createElement('video');
  video.id = 'streamVideoElement';
  video.autoplay = true;
  video.playsInline = true;
  video.muted = false; // Important: not muted for audio
  
  // Set stream BEFORE adding to DOM
  video.srcObject = stream;
  
  // Add video to container
  streamVideo.appendChild(video);
  
  // Show appropriate controls based on stream type
  updateStreamControls(streamType);
  
  // Log stream info for debugging
  debugLog('[WebRTC] Stream tracks:', stream.getTracks().map(t => ({
    kind: t.kind,
    enabled: t.enabled,
    muted: t.muted,
    readyState: t.readyState
  })));
  
  // Play video with retry
  const playVideo = () => {
    video.play().then(() => {
      debugLog('[WebRTC] Video playing successfully');
    }).catch(e => {
      console.error('[WebRTC] Error playing video:', e);
      // Add click-to-play button for autoplay restrictions
      const playBtn = document.createElement('button');
      playBtn.textContent = '▶ Click to Play';
      playBtn.className = 'btn btn-primary';
      playBtn.style.cssText = 'position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);z-index:10;';
      playBtn.onclick = () => { 
        video.muted = false; // Ensure unmuted
        video.play().then(() => playBtn.remove()); 
      };
      streamVideo.appendChild(playBtn);
    });
  };
  
  // Wait for loadedmetadata before playing
  if (video.readyState >= 1) {
    playVideo();
  } else {
    video.onloadedmetadata = playVideo;
  }
}

function updateStreamControls(streamType) {
  // Show/hide switch camera button based on stream type
  const switchCameraBtn = document.getElementById('btnSwitchCamera');
  if (switchCameraBtn) {
    switchCameraBtn.style.display = streamType === 'camera' ? 'flex' : 'none';
  }
}

function toggleStreamMute() {
  const video = document.getElementById('streamVideoElement');
  const audio = document.querySelector('#streamVideo audio');
  const btn = document.getElementById('btnMuteStream');
  
  if (video) {
    video.muted = !video.muted;
    btn.querySelector('i').className = video.muted ? 'fas fa-volume-mute' : 'fas fa-volume-up';
    btn.classList.toggle('active', video.muted);
  } else if (audio) {
    audio.muted = !audio.muted;
    btn.querySelector('i').className = audio.muted ? 'fas fa-volume-mute' : 'fas fa-volume-up';
    btn.classList.toggle('active', audio.muted);
  }
}

function toggleStreamFullscreen() {
  const modal = document.querySelector('.stream-modal');
  const btn = document.getElementById('btnFullscreenStream');
  
  if (modal.classList.contains('fullscreen')) {
    modal.classList.remove('fullscreen');
    btn.querySelector('i').className = 'fas fa-expand';
    if (document.exitFullscreen) {
      document.exitFullscreen().catch(() => {});
    }
  } else {
    modal.classList.add('fullscreen');
    btn.querySelector('i').className = 'fas fa-compress';
    const container = document.getElementById('streamContainer');
    if (container.requestFullscreen) {
      container.requestFullscreen().catch(() => {});
    }
  }
}

async function togglePictureInPicture() {
  const video = document.getElementById('streamVideoElement');
  const btn = document.getElementById('btnPipMode');
  
  if (!video) return;
  
  try {
    if (document.pictureInPictureElement) {
      await document.exitPictureInPicture();
      btn.classList.remove('active');
    } else if (document.pictureInPictureEnabled) {
      await video.requestPictureInPicture();
      btn.classList.add('active');
    }
  } catch (e) {
    console.error('[PiP] Error:', e);
  }
}

function displayWebRTCAudio(stream) {
  const streamVideo = document.getElementById('streamVideo');
  
  // Log audio track info for debugging
  const audioTracks = stream.getAudioTracks();
  debugLog('[WebRTC] Audio tracks:', audioTracks.map(t => ({
    id: t.id,
    enabled: t.enabled,
    muted: t.muted,
    readyState: t.readyState,
    settings: t.getSettings ? t.getSettings() : 'N/A'
  })));
  
  // Create audio element
  const audio = document.createElement('audio');
  audio.id = 'streamAudioElement';
  audio.autoplay = true;
  audio.controls = true; // Show controls for debugging
  audio.srcObject = stream;
  audio.volume = 1.0; // Max volume
  audio.muted = false; // Ensure not muted
  
  // Create audio context for better control and visualization
  let audioContext = null;
  let analyser = null;
  
  // Visual indicator
  streamVideo.innerHTML = `
    <div class="audio-indicator active">
      <i class="material-icons">hearing</i>
      <p>🎧 Live Audio - Connecting...</p>
      <div class="audio-visualizer">
        <span></span><span></span><span></span><span></span><span></span>
      </div>
      <div class="audio-controls" style="margin-top: 15px;">
        <button id="enableAudioBtn" class="btn btn-primary" style="display: none;">🔊 Enable Audio</button>
        <div class="volume-indicator" style="margin-top: 10px; display: none;">
          <label>Volume Level: </label>
          <div id="volumeBar" style="width: 200px; height: 10px; background: #333; display: inline-block; vertical-align: middle; border-radius: 5px;">
            <div id="volumeLevel" style="width: 0%; height: 100%; background: #4CAF50; border-radius: 5px; transition: width 0.1s;"></div>
          </div>
        </div>
      </div>
    </div>
  `;
  
  streamVideo.appendChild(audio);
  
  // Try to play audio
  const playAudio = () => {
    audio.play().then(() => {
      debugLog('[WebRTC] Audio playing successfully');
      streamVideo.querySelector('.audio-indicator p').textContent = '🎧 Live Audio Connected';
      document.getElementById('enableAudioBtn').style.display = 'none';
      
      // Set up audio analysis to show volume
      try {
        audioContext = new (window.AudioContext || window.webkitAudioContext)();
        const source = audioContext.createMediaStreamSource(stream);
        analyser = audioContext.createAnalyser();
        analyser.fftSize = 256;
        source.connect(analyser);
        
        // Don't connect to destination here as audio element handles playback
        
        // Show volume indicator
        const volumeIndicator = streamVideo.querySelector('.volume-indicator');
        if (volumeIndicator) volumeIndicator.style.display = 'block';
        
        // Update volume visualization
        const dataArray = new Uint8Array(analyser.frequencyBinCount);
        const updateVolume = () => {
          if (!analyser) return;
          analyser.getByteFrequencyData(dataArray);
          const average = dataArray.reduce((a, b) => a + b, 0) / dataArray.length;
          const volumeLevel = document.getElementById('volumeLevel');
          if (volumeLevel) {
            volumeLevel.style.width = (average / 255 * 100) + '%';
          }
          requestAnimationFrame(updateVolume);
        };
        updateVolume();
      } catch (e) {
        debugLog('[WebRTC] Audio visualization not supported:', e);
      }
    }).catch(e => {
      console.error('[WebRTC] Error playing audio (autoplay blocked):', e);
      streamVideo.querySelector('.audio-indicator p').textContent = '🔇 Click button to enable audio';
      const enableBtn = document.getElementById('enableAudioBtn');
      enableBtn.style.display = 'inline-block';
      enableBtn.onclick = () => {
        audio.muted = false;
        audio.play().then(() => {
          enableBtn.style.display = 'none';
          streamVideo.querySelector('.audio-indicator p').textContent = '🎧 Live Audio Connected';
          playAudio(); // Retry audio context setup
        }).catch(err => {
          console.error('[WebRTC] Still cannot play audio:', err);
          alert('Unable to play audio. Please check browser permissions.');
        });
      };
    });
  };
  
  // Play audio
  playAudio();
}

function closeWebRTCConnection() {
  if (webrtcPeerConnection) {
    webrtcPeerConnection.close();
    webrtcPeerConnection = null;
  }
  
  if (webrtcSignalingSocket) {
    webrtcSignalingSocket.close();
    webrtcSignalingSocket = null;
  }
  
  if (webrtcRemoteStream) {
    webrtcRemoteStream.getTracks().forEach(track => track.stop());
    webrtcRemoteStream = null;
  }
  
  pendingIceCandidates = [];
  isRemoteDescriptionSet = false;
}

// ================== Legacy Streaming (fallback) ==================

function startStream(type) {
  if (!selectedDevice) {
    alert('Please select a device first');
    return;
  }
  
  // Check if device has required permissions before streaming
  const permissions = selectedDevice.permissions || {};
  const missingPermissions = [];
  
  if (type === 'camera') {
    if (!permissions.camera) missingPermissions.push('Camera');
  } else if (type === 'audio') {
    if (!permissions.microphone) missingPermissions.push('Microphone');
  } else if (type === 'screen') {
    // Screen mirror requires MediaProjection which needs user interaction on device
    // Can't be requested remotely
  }
  
  const modal = document.getElementById('streamModal');
  const title = document.getElementById('streamTitle');
  const streamVideo = document.getElementById('streamVideo');
  
  const titles = {
    screen: 'Screen Mirror',
    camera: 'Remote Camera',
    audio: 'Live Listen'
  };
  
  currentStreamType = type;
  streamAttempts = 0;
  title.textContent = titles[type];
  modal.classList.remove('hidden');
  
  // Show connecting message
  streamVideo.innerHTML = '<p class="connecting">Connecting to device...</p>';
  
  // Start stream command on device (silent - don't show alerts)
  const commands = {
    screen: 'start_screen_mirror',
    camera: 'start_camera',
    audio: 'start_live_listen'
  };
  
  sendCommand(commands[type], {}, true);
  
  // Connect to WebSocket as receiver - use Android deviceId
  const deviceId = selectedDevice.deviceId || selectedDevice.id;
  const sessionId = `${deviceId}_${type}`;
  const wsUrl = `${WS_BASE}?session=${sessionId}&role=receiver&deviceId=${deviceId}&type=${type}`;
  
  debugLog('Connecting to WebSocket:', wsUrl);
  
  // Close existing connection
  if (streamSocket) {
    streamSocket.close();
    streamSocket = null;
  }
  
  // Clear any existing timeout
  if (streamTimeout) {
    clearTimeout(streamTimeout);
    streamTimeout = null;
  }
  
  streamSocket = new WebSocket(wsUrl);
  let streamStarted = false;
  
  streamSocket.onopen = () => {
    debugLog('WebSocket connected');
    streamVideo.innerHTML = '<p class="connecting">Waiting for device stream...</p>';
    
    // Set timeout for stream to start
    streamTimeout = setTimeout(() => {
      if (!streamStarted) {
        streamAttempts++;
        if (streamAttempts >= MAX_STREAM_ATTEMPTS) {
          showStreamError(type, permissions);
        } else {
          // Retry
          streamVideo.innerHTML = `<p class="connecting">Retrying... (Attempt ${streamAttempts + 1}/${MAX_STREAM_ATTEMPTS})</p>`;
          sendCommand(commands[type], {}, true);
          
          // Reset timeout for next attempt
          streamTimeout = setTimeout(() => {
            if (!streamStarted) {
              streamAttempts++;
              if (streamAttempts >= MAX_STREAM_ATTEMPTS) {
                showStreamError(type, permissions);
              }
            }
          }, STREAM_TIMEOUT_MS);
        }
      }
    }, STREAM_TIMEOUT_MS);
  };
  
  streamSocket.onmessage = (event) => {
    // Handle incoming stream data
    if (typeof event.data === 'string') {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'waiting') {
          streamVideo.innerHTML = '<p class="connecting">Waiting for device to start streaming...</p>';
        } else if (msg.type === 'connected' || msg.type === 'stream_started') {
          streamStarted = true;
          if (streamTimeout) {
            clearTimeout(streamTimeout);
            streamTimeout = null;
          }
          streamVideo.innerHTML = '<p class="connecting">Stream connected, receiving data...</p>';
        } else if (msg.type === 'camera_frame' || msg.type === 'screen_frame') {
          // Handle JPEG frame data
          streamStarted = true;
          if (streamTimeout) {
            clearTimeout(streamTimeout);
            streamTimeout = null;
          }
          handleStreamData(event.data, type);
        } else if (msg.type === 'error') {
          streamStarted = true; // Prevent timeout error from showing
          if (streamTimeout) {
            clearTimeout(streamTimeout);
            streamTimeout = null;
          }
          showStreamErrorMessage(msg.message || msg.error || 'Unknown error', type, permissions);
        } else if (msg.type === 'permission_denied' || msg.type === 'permission_required') {
          streamStarted = true;
          if (streamTimeout) {
            clearTimeout(streamTimeout);
            streamTimeout = null;
          }
          showStreamError(type, permissions, msg.permission || msg.message);
        } else {
          // Other message types - might be stream data
          streamStarted = true;
          if (streamTimeout) {
            clearTimeout(streamTimeout);
            streamTimeout = null;
          }
          handleStreamData(event.data, type);
        }
      } catch (e) {
        // Not JSON - might be raw data like "audio:..." prefix
        streamStarted = true;
        if (streamTimeout) {
          clearTimeout(streamTimeout);
          streamTimeout = null;
        }
        handleStreamData(event.data, type);
      }
    } else {
      // Binary data - video/audio frame - stream is working!
      streamStarted = true;
      if (streamTimeout) {
        clearTimeout(streamTimeout);
        streamTimeout = null;
      }
      handleStreamData(event.data, type);
    }
  };
  
  streamSocket.onerror = (error) => {
    console.error('WebSocket error:', error);
    if (streamTimeout) {
      clearTimeout(streamTimeout);
      streamTimeout = null;
    }
    streamVideo.innerHTML = '<p class="error">Connection error. Please try again.</p>';
  };
  
  streamSocket.onclose = (event) => {
    debugLog('WebSocket closed:', event.reason);
    if (streamTimeout) {
      clearTimeout(streamTimeout);
      streamTimeout = null;
    }
    if (currentStreamType && !streamStarted) {
      showStreamError(type, permissions);
    } else if (currentStreamType) {
      streamVideo.innerHTML = '<p class="connecting">Connection closed. Click Stop to exit.</p>';
    }
  };
}

function showStreamError(type, permissions, specificError = null) {
  const streamVideo = document.getElementById('streamVideo');
  let errorMessage = '';
  let suggestion = '';
  
  // Determine the exact cause
  if (specificError) {
    errorMessage = specificError;
  } else if (type === 'camera') {
    if (!permissions.camera) {
      errorMessage = 'Camera permission not granted on child device';
      suggestion = 'Click "Request Permission" to ask for camera access.';
    } else {
      errorMessage = 'Device not responding to camera stream request';
      suggestion = 'The device might be locked, app might be killed, or camera is in use by another app.';
    }
  } else if (type === 'audio') {
    if (!permissions.microphone) {
      errorMessage = 'Microphone permission not granted on child device';
      suggestion = 'Click "Request Permission" to ask for microphone access.';
    } else {
      errorMessage = 'Device not responding to audio stream request';
      suggestion = 'The device might be locked, app might be killed, or microphone is in use.';
    }
  } else if (type === 'screen') {
    errorMessage = 'Screen mirroring requires user interaction';
    suggestion = 'MediaProjection permission must be granted directly on the device. The child device user needs to tap "Allow" when prompted.';
  } else {
    errorMessage = 'Stream failed to start after multiple attempts';
    suggestion = 'Check if the device is online and the app is running.';
  }
  
  const permissionBtn = (type === 'camera' && !permissions.camera) 
    ? `<button class="btn btn-primary" onclick="requestPermissionFromStream('camera')">Request Camera Permission</button>`
    : (type === 'audio' && !permissions.microphone)
    ? `<button class="btn btn-primary" onclick="requestPermissionFromStream('microphone')">Request Microphone Permission</button>`
    : '';
  
  streamVideo.innerHTML = `
    <div class="stream-error-detail">
      <i class="material-icons error-icon">error_outline</i>
      <h3>Stream Failed</h3>
      <p class="error-message">${errorMessage}</p>
      <p class="error-suggestion">${suggestion}</p>
      <div class="error-actions">
        ${permissionBtn}
        <button class="btn btn-secondary" onclick="retryStream('${type}')">Retry</button>
      </div>
      <div class="error-debug">
        <small>Device Online: ${selectedDevice?.isOnline ? 'Yes' : 'No'}</small><br>
        <small>Last Seen: ${selectedDevice?.lastSeen ? formatTime(selectedDevice.lastSeen) : 'Unknown'}</small><br>
        <small>Camera Permission: ${permissions.camera ? 'Yes' : 'No'}</small><br>
        <small>Microphone Permission: ${permissions.microphone ? 'Yes' : 'No'}</small>
      </div>
    </div>
  `;
}

function showStreamErrorMessage(message, type, permissions) {
  const streamVideo = document.getElementById('streamVideo');
  streamVideo.innerHTML = `
    <div class="stream-error-detail">
      <i class="material-icons error-icon">error_outline</i>
      <h3>Stream Error</h3>
      <p class="error-message">${message}</p>
      <div class="error-actions">
        <button class="btn btn-secondary" onclick="retryStream('${type}')">Retry</button>
      </div>
    </div>
  `;
}

function requestPermissionFromStream(permissionType) {
  requestPermission(permissionType);
  stopStream();
}

function retryStream(type) {
  stopStream();
  setTimeout(() => startStream(type), 500);
}

// Handle incoming stream data
function handleStreamData(data, type) {
  const streamVideo = document.getElementById('streamVideo');
  
  if (type === 'audio') {
    // Audio stream - use Web Audio API to play
    if (!streamVideo.querySelector('.audio-indicator')) {
      streamVideo.innerHTML = `
        <div class="audio-indicator active">
          <i class="material-icons">hearing</i>
          <p>🎧 Live audio playing...</p>
          <div class="audio-visualizer">
            <span></span><span></span><span></span><span></span><span></span>
          </div>
        </div>`;
    }
    
    // Handle audio data
    if (typeof data === 'string') {
      if (data.startsWith('audio:')) {
        // Base64 audio data from child device
        const base64Audio = data.substring(6);
        playAudioChunk(base64Audio);
      } else {
        try {
          const msg = JSON.parse(data);
          if (msg.type === 'stream_started') {
            initAudioContext(); // Prepare audio context
          }
        } catch (e) {
          // Might be raw audio data
        }
      }
    }
  } else {
    // Video/Camera/Screen stream - handle JPEG frames or JSON messages
    if (typeof data === 'string') {
      try {
        const msg = JSON.parse(data);
        if ((msg.type === 'camera_frame' || msg.type === 'screen_frame') && msg.frame) {
          // Display JPEG frame as image
          displayVideoFrame(msg.frame);
        } else if (msg.type === 'stream_started') {
          streamVideo.innerHTML = '<p class="connecting">Stream started, waiting for frames...</p>';
        } else if (msg.type === 'error') {
          streamVideo.innerHTML = `<p class="error">Error: ${msg.error}</p>`;
        }
      } catch (e) {
        // Not JSON, might be raw data
        debugLog('Non-JSON message received');
      }
    } else if (data instanceof Blob) {
      // Binary data - convert to image
      const reader = new FileReader();
      reader.onload = () => {
        const base64 = reader.result.split(',')[1];
        displayVideoFrame(base64);
      };
      reader.readAsDataURL(data);
    }
  }
}

// Display a video frame from base64 JPEG
function displayVideoFrame(base64Data) {
  const streamVideo = document.getElementById('streamVideo');
  
  // Create or update image element
  let img = streamVideo.querySelector('.stream-image');
  if (!img) {
    streamVideo.innerHTML = '';
    img = document.createElement('img');
    img.className = 'stream-image';
    img.style.cssText = 'max-width: 100%; max-height: 100%; object-fit: contain;';
    streamVideo.appendChild(img);
  }
  
  img.src = 'data:image/jpeg;base64,' + base64Data;
}

let currentStreamType = null;

function stopStream() {
  // Clear any wakeup intervals
  if (deviceWakeupInterval) {
    clearInterval(deviceWakeupInterval);
    deviceWakeupInterval = null;
  }
  if (webrtcConnectionTimeout) {
    clearTimeout(webrtcConnectionTimeout);
    webrtcConnectionTimeout = null;
  }
  
  // Close WebRTC connections first
  closeWebRTCConnection();
  
  if (streamSocket) {
    streamSocket.close();
    streamSocket = null;
  }
  
  // Clean up audio playback
  stopAudioPlayback();
  
  document.getElementById('streamModal').classList.add('hidden');
  
  // Send appropriate stop command based on stream type
  const stopCommands = {
    screen: 'stop_screen_mirror',
    camera: 'stop_camera',
    audio: 'stop_live_listen'
  };
  
  // Also send WebRTC stop commands
  const webrtcStopCommands = {
    screen: 'stop_webrtc_screen',
    camera: 'stop_webrtc_camera',
    audio: 'stop_webrtc_audio'
  };
  
  if (currentStreamType) {
    if (stopCommands[currentStreamType]) {
      sendCommand(stopCommands[currentStreamType]);
    }
    if (webrtcStopCommands[currentStreamType]) {
      sendCommand(webrtcStopCommands[currentStreamType]);
    }
  }
  currentStreamType = null;
}

// Alias for stopStream (used in onclick handlers)
function closeStreamModal() {
  stopStream();
}

// Ring device with stop option
let isRinging = false;

function ringDevice() {
  if (isRinging) {
    sendCommand('stop_ring');
    document.getElementById('btnRingDevice').innerHTML = '<i class="fas fa-bell"></i><span>Ring Device</span>';
    isRinging = false;
  } else {
    sendCommand('ring_device');
    document.getElementById('btnRingDevice').innerHTML = '<i class="fas fa-bell-slash"></i><span>Stop Ring</span>';
    isRinging = true;
    // Auto-reset after 30 seconds
    setTimeout(() => {
      if (isRinging) {
        document.getElementById('btnRingDevice').innerHTML = '<i class="fas fa-bell"></i><span>Ring Device</span>';
        isRinging = false;
      }
    }, 30000);
  }
}

// Pairing
function showPairingModal() {
  document.getElementById('pairingModal').classList.remove('hidden');
  generatePairingCode();
}

function hidePairingModal() {
  document.getElementById('pairingModal').classList.add('hidden');
}

// Device Management Modal
let tempDeviceOrder = [];

function showDeviceManageModal() {
  if (devices.length === 0) {
    showToast('No devices to manage', 'warning');
    return;
  }
  
  const modal = document.getElementById('deviceManageModal');
  const list = document.getElementById('deviceListSortable');
  
  // Store original order
  tempDeviceOrder = devices.map(d => getDeviceId(d));
  
  // Render device list
  list.innerHTML = devices.map((device, index) => `
    <div class="device-sort-item" data-id="${getDeviceId(device)}" draggable="true">
      <span class="drag-handle"><i class="fas fa-grip-vertical"></i></span>
      <div class="device-info">
        <div class="device-name">${device.alias || device.name || 'Unknown Device'}</div>
        <div class="device-id">${getDeviceId(device)}</div>
      </div>
      <button class="btn-rename-inline" onclick="inlineRenameDevice('${getDeviceId(device)}', this)">
        <i class="fas fa-edit"></i> Rename
      </button>
    </div>
  `).join('');
  
  // Setup drag and drop
  setupDragDrop(list);
  
  modal.classList.remove('hidden');
}

function hideDeviceManageModal() {
  document.getElementById('deviceManageModal').classList.add('hidden');
}

function setupDragDrop(container) {
  const items = container.querySelectorAll('.device-sort-item');
  
  items.forEach(item => {
    item.addEventListener('dragstart', (e) => {
      item.classList.add('dragging');
      e.dataTransfer.setData('text/plain', item.dataset.id);
    });
    
    item.addEventListener('dragend', () => {
      item.classList.remove('dragging');
    });
    
    item.addEventListener('dragover', (e) => {
      e.preventDefault();
      const dragging = container.querySelector('.dragging');
      if (dragging && item !== dragging) {
        const rect = item.getBoundingClientRect();
        const midY = rect.top + rect.height / 2;
        if (e.clientY < midY) {
          container.insertBefore(dragging, item);
        } else {
          container.insertBefore(dragging, item.nextSibling);
        }
      }
    });
  });
}

function saveDeviceOrder() {
  const list = document.getElementById('deviceListSortable');
  const items = list.querySelectorAll('.device-sort-item');
  const newOrder = Array.from(items).map(item => item.dataset.id);
  
  // Check if order changed
  const orderChanged = newOrder.some((id, i) => id !== tempDeviceOrder[i]);
  
  if (orderChanged) {
    reorderDevices(newOrder);
  }
  
  hideDeviceManageModal();
}

// Inline rename from modal
function inlineRenameDevice(deviceId, btn) {
  const device = devices.find(d => getDeviceId(d) === deviceId);
  if (!device) return;
  
  const currentName = device.alias || device.name || 'Unknown';
  const newName = prompt('Enter new name for device:', currentName);
  
  if (newName && newName.trim() && newName !== currentName) {
    renameDevice(deviceId, newName.trim()).then(() => {
      // Update the display in modal
      const item = btn.closest('.device-sort-item');
      if (item) {
        item.querySelector('.device-name').textContent = newName.trim();
      }
    });
  }
}

async function generatePairingCode() {
  try {
    const data = await api('/auth/pairing-code', { method: 'POST' });
    document.getElementById('pairingCode').textContent = data.code;
    
    // Start countdown - handle both number and string formats
    let seconds = typeof data.expiresIn === 'number' ? data.expiresIn : 5 * 60; // default 5 min
    const expiryEl = document.getElementById('codeExpiry');
    
    const interval = setInterval(() => {
      seconds--;
      const mins = Math.floor(seconds / 60);
      const secs = seconds % 60;
      expiryEl.textContent = `${mins}:${secs.toString().padStart(2, '0')}`;
      
      if (seconds <= 0) {
        clearInterval(interval);
        document.getElementById('pairingCode').textContent = '------';
        expiryEl.textContent = 'Expired';
      }
    }, 1000);
  } catch (error) {
    alert('Failed to generate pairing code: ' + error.message);
  }
}

// Device Selection
function handleDeviceChange() {
  const deviceId = deviceSelector.value;
  selectedDevice = devices.find(d => getDeviceId(d) === deviceId) || null;
  
  // Hide "no devices" state when a device is selected
  if (selectedDevice) {
    hideNoDevicesState();
    document.getElementById('deviceStatusCard').style.display = 'block';
  }
  
  refreshData();
}

function refreshData() {
  const currentPage = document.querySelector('.nav-item.active')?.dataset.page || 'dashboard';
  navigateTo(currentPage);
}

// Utility Functions
function formatTime(timestamp) {
  if (!timestamp) return 'Unknown';
  
  const date = new Date(timestamp);
  const now = new Date();
  const diff = now - date;
  
  if (diff < 60000) return 'Just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
  
  return date.toLocaleDateString();
}

function formatDuration(ms) {
  if (!ms || ms <= 0) return '0m';
  
  // Convert milliseconds to minutes
  const totalMinutes = Math.floor(ms / 60000);
  const hours = Math.floor(totalMinutes / 60);
  const mins = totalMinutes % 60;
  
  if (hours > 0) {
    return `${hours}h ${mins}m`;
  }
  return `${mins}m`;
}

function getAppIcon(packageName) {
  const icons = {
    'com.whatsapp': '<i class="fab fa-whatsapp" style="color:#25D366"></i>',
    'com.instagram.android': '<i class="fab fa-instagram" style="color:#E4405F"></i>',
    'org.telegram.messenger': '<i class="fab fa-telegram" style="color:#0088cc"></i>',
    'com.facebook.orca': '<i class="fab fa-facebook-messenger" style="color:#0078FF"></i>',
    'com.facebook.katana': '<i class="fab fa-facebook" style="color:#1877F2"></i>',
    'com.twitter.android': '<i class="fab fa-twitter" style="color:#1DA1F2"></i>',
    'com.snapchat.android': '<i class="fab fa-snapchat" style="color:#FFFC00"></i>',
  };
  
  return icons[packageName] || '<i class="fas fa-bell"></i>';
}

// ========== PERMISSIONS DISPLAY ==========
async function loadPermissions() {
  if (!selectedDevice) return;
  
  try {
    const data = await api(`/devices/${getDeviceId(selectedDevice)}`);
    const permissions = data.device.permissions || {};
    
    // Update permission status in UI
    const permissionElements = document.querySelectorAll('[data-permission]');
    let grantedCount = 0;
    let totalCount = 0;
    
    permissionElements.forEach(el => {
      const permName = el.dataset.permission;
      const statusEl = el.querySelector('.permission-status');
      // Handle both naming conventions: restrictionSettings (frontend) and restrictedSettings (backend)
      let isGranted;
      if (permName === 'restrictionSettings') {
        isGranted = permissions['restrictedSettings'] === true || permissions['restrictionSettings'] === true;
      } else {
        isGranted = permissions[permName] === true;
      }
      
      // Handle restrictionSettings - only available on Android 13+
      if (permName === 'restrictionSettings') {
        const androidVersion = parseInt(data.device.androidVersion) || 0;
        if (androidVersion < 13) {
          if (statusEl) {
            statusEl.textContent = 'Not Required';
            statusEl.className = 'permission-status granted';
          }
          el.classList.add('granted');
          const btn = el.querySelector('.btn-request-perm');
          if (btn) btn.style.display = 'none';
          return;
        }
      }
      
      if (statusEl) {
        statusEl.textContent = isGranted ? 'Granted' : 'Denied';
        statusEl.className = `permission-status ${isGranted ? 'granted' : 'denied'}`;
      }
      
      // Update the permission item class for styling the button
      if (isGranted) {
        el.classList.add('granted');
      } else {
        el.classList.remove('granted');
      }
      
      totalCount++;
      if (isGranted) grantedCount++;
    });
    
    // Update last updated time
    const lastUpdatedEl = document.getElementById('permissionsLastUpdated');
    if (lastUpdatedEl) {
      if (permissions.lastUpdated) {
        lastUpdatedEl.textContent = `Last updated: ${formatTime(permissions.lastUpdated)}`;
      } else if (data.device.lastSeen) {
        lastUpdatedEl.textContent = `Last updated: ${formatTime(data.device.lastSeen)}`;
      }
    }
    
    debugLog(`Permissions: ${grantedCount}/${totalCount} granted`);
  } catch (error) {
    console.error('Failed to load permissions:', error);
  }
}

// Request a specific permission from child device
async function requestPermission(permissionName) {
  if (!selectedDevice) {
    alert('Please select a device first');
    return;
  }
  
  const permissionMap = {
    'location': 'request_location_permission',
    'backgroundLocation': 'request_background_location_permission',
    'camera': 'request_camera_permission',
    'microphone': 'request_microphone_permission',
    'callLog': 'request_call_log_permission',
    'notifications': 'request_notification_permission',
    'usageStats': 'request_usage_access_permission',
    'overlay': 'request_overlay_permission',
    'batteryOptimization': 'request_battery_optimization_permission',
    'deviceAdmin': 'request_device_admin_permission',
    'restrictionSettings': 'request_restriction_settings',
    'accessibility': 'request_accessibility_permission',
    'storage': 'request_storage_permission',
    'sms': 'request_sms_permission',
    'contacts': 'request_contacts_permission'
  };
  
  const command = permissionMap[permissionName] || `request_${permissionName}_permission`;
  
  try {
    await sendCommand(command, { permission: permissionName });
    alert(`Permission request sent to device. The user will be prompted to grant "${permissionName}" permission.`);
  } catch (error) {
    alert('Failed to send permission request: ' + error.message);
  }
}

// Request all missing permissions
async function requestAllMissingPermissions() {
  if (!selectedDevice) {
    alert('Please select a device first');
    return;
  }
  
  try {
    await sendCommand('request_all_permissions');
    alert('Request sent to device. The user will be guided through granting all missing permissions.');
  } catch (error) {
    alert('Failed to send request: ' + error.message);
  }
}

// ========== SECURITY PIN MANAGEMENT ==========
let currentPinAction = 'set'; // 'set', 'change', 'remove'

async function checkPinStatus() {
  try {
    const data = await api('/auth/has-pin');
    const hasPin = data.hasPinSet;
    
    const statusBadge = document.querySelector('.pin-status .pin-badge');
    const btnSet = document.getElementById('btnSetPin');
    const btnChange = document.getElementById('btnChangePin');
    const btnRemove = document.getElementById('btnRemovePin');
    
    if (hasPin) {
      if (statusBadge) {
        statusBadge.textContent = 'PIN Set';
        statusBadge.className = 'pin-badge has-pin';
      }
      if (btnSet) btnSet.classList.add('hidden');
      if (btnChange) btnChange.classList.remove('hidden');
      if (btnRemove) btnRemove.classList.remove('hidden');
    } else {
      if (statusBadge) {
        statusBadge.textContent = 'No PIN';
        statusBadge.className = 'pin-badge no-pin';
      }
      if (btnSet) btnSet.classList.remove('hidden');
      if (btnChange) btnChange.classList.add('hidden');
      if (btnRemove) btnRemove.classList.add('hidden');
    }
  } catch (error) {
    console.error('Failed to check PIN status:', error);
  }
}

function showPinModal(action) {
  currentPinAction = action;
  const modal = document.getElementById('pinModal');
  const title = document.getElementById('pinModalTitle');
  const currentPinGroup = document.getElementById('currentPinGroup');
  const newPinGroup = document.getElementById('newPinGroup');
  const confirmPinGroup = document.getElementById('confirmPinGroup');
  const saveBtn = document.getElementById('btnSavePin');
  
  // Clear inputs
  document.getElementById('currentPin').value = '';
  document.getElementById('newPin').value = '';
  document.getElementById('confirmPin').value = '';
  document.getElementById('pinError').classList.add('hidden');
  
  switch(action) {
    case 'set':
      title.textContent = 'Set Security PIN';
      currentPinGroup.classList.add('hidden');
      newPinGroup.classList.remove('hidden');
      confirmPinGroup.classList.remove('hidden');
      saveBtn.textContent = 'Set PIN';
      break;
    case 'change':
      title.textContent = 'Change Security PIN';
      currentPinGroup.classList.remove('hidden');
      newPinGroup.classList.remove('hidden');
      confirmPinGroup.classList.remove('hidden');
      saveBtn.textContent = 'Change PIN';
      break;
    case 'remove':
      title.textContent = 'Remove Security PIN';
      currentPinGroup.classList.remove('hidden');
      newPinGroup.classList.add('hidden');
      confirmPinGroup.classList.add('hidden');
      saveBtn.textContent = 'Remove PIN';
      break;
  }
  
  modal.classList.remove('hidden');
}

function hidePinModal() {
  document.getElementById('pinModal').classList.add('hidden');
}

function showPinError(message) {
  const errorEl = document.getElementById('pinError');
  errorEl.textContent = message;
  errorEl.classList.remove('hidden');
}

async function handlePinSave() {
  const currentPin = document.getElementById('currentPin').value;
  const newPin = document.getElementById('newPin').value;
  const confirmPin = document.getElementById('confirmPin').value;
  
  // Validation
  if (currentPinAction !== 'remove') {
    if (!newPin || newPin.length < 4 || newPin.length > 8) {
      showPinError('PIN must be 4-8 digits');
      return;
    }
    if (!/^\d+$/.test(newPin)) {
      showPinError('PIN must contain only numbers');
      return;
    }
    if (newPin !== confirmPin) {
      showPinError('PINs do not match');
      return;
    }
  }
  
  if ((currentPinAction === 'change' || currentPinAction === 'remove') && !currentPin) {
    showPinError('Please enter current PIN');
    return;
  }
  
  try {
    if (currentPinAction === 'set') {
      await api('/auth/security-pin', {
        method: 'POST',
        body: JSON.stringify({ pin: newPin })
      });
      alert('Security PIN set successfully!');
    } else if (currentPinAction === 'change') {
      await api('/auth/security-pin', {
        method: 'POST',
        body: JSON.stringify({ currentPin, pin: newPin })
      });
      alert('Security PIN changed successfully!');
    } else if (currentPinAction === 'remove') {
      await api('/auth/security-pin', {
        method: 'DELETE',
        body: JSON.stringify({ pin: currentPin })
      });
      alert('Security PIN removed successfully!');
    }
    
    hidePinModal();
    checkPinStatus();
  } catch (error) {
    showPinError(error.message || 'Failed to update PIN');
  }
}

// Setup PIN event listeners
document.addEventListener('DOMContentLoaded', () => {
  // PIN buttons
  const btnSetPin = document.getElementById('btnSetPin');
  const btnChangePin = document.getElementById('btnChangePin');
  const btnRemovePin = document.getElementById('btnRemovePin');
  const btnSavePin = document.getElementById('btnSavePin');
  const btnCancelPin = document.getElementById('btnCancelPin');
  const closePinModal = document.getElementById('closePinModal');
  
  if (btnSetPin) btnSetPin.addEventListener('click', () => showPinModal('set'));
  if (btnChangePin) btnChangePin.addEventListener('click', () => showPinModal('change'));
  if (btnRemovePin) btnRemovePin.addEventListener('click', () => showPinModal('remove'));
  if (btnSavePin) btnSavePin.addEventListener('click', handlePinSave);
  if (btnCancelPin) btnCancelPin.addEventListener('click', hidePinModal);
  if (closePinModal) closePinModal.addEventListener('click', hidePinModal);
});

// ============ SCREENSHOT FUNCTIONS ============

async function captureScreenshot() {
  if (!selectedDevice) {
    alert('Please select a device first');
    return;
  }
  
  const btn = document.getElementById('btnScreenshot');
  btn.disabled = true;
  btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i><span>Capturing...</span>';
  
  try {
    const deviceId = getDeviceId(selectedDevice);
    await api(`/devices/${deviceId}/screenshot/capture`, {
      method: 'POST'
    });
    
    // Wait a few seconds then fetch the screenshot
    setTimeout(() => {
      loadLatestScreenshot();
      btn.disabled = false;
      btn.innerHTML = '<i class="fas fa-camera-retro"></i><span>Screenshot</span>';
    }, 4000);
    
  } catch (error) {
    console.error('Screenshot error:', error);
    alert('Failed to capture screenshot: ' + error.message);
    btn.disabled = false;
    btn.innerHTML = '<i class="fas fa-camera-retro"></i><span>Screenshot</span>';
  }
}

async function loadLatestScreenshot() {
  if (!selectedDevice) return;
  
  try {
    const deviceId = getDeviceId(selectedDevice);
    const data = await api(`/devices/${deviceId}/screenshot`);
    
    if (data.screenshot) {
      displayScreenshot(data.screenshot);
    }
  } catch (error) {
    debugLog('No screenshot available:', error.message);
  }
}

function displayScreenshot(screenshot) {
  const section = document.getElementById('screenshotSection');
  const preview = document.getElementById('screenshotPreview');
  
  if (!section || !preview) return;
  
  section.classList.remove('hidden');
  
  const timestamp = screenshot.capturedAt ? new Date(screenshot.capturedAt).toLocaleString() : 'Unknown';
  
  preview.innerHTML = `
    <img src="data:image/jpeg;base64,${screenshot.imageData}" 
         alt="Screenshot" 
         onclick="openScreenshotFullscreen(this.src)">
    <p class="timestamp">Captured: ${timestamp}</p>
  `;
}

function openScreenshotFullscreen(src) {
  // Create fullscreen modal
  const modal = document.createElement('div');
  modal.className = 'screenshot-fullscreen-modal';
  modal.innerHTML = `
    <div class="screenshot-fullscreen-content">
      <button class="btn-close" onclick="this.parentElement.parentElement.remove()">&times;</button>
      <img src="${src}" alt="Screenshot Fullscreen">
    </div>
  `;
  modal.onclick = (e) => {
    if (e.target === modal) modal.remove();
  };
  document.body.appendChild(modal);
}

// Screenshots Gallery Page Functions
async function loadScreenshots() {
  if (!selectedDevice) {
    document.getElementById('screenshotsGrid').innerHTML = '<p class="empty-state">Select a device to view screenshots</p>';
    return;
  }
  
  try {
    const deviceId = getDeviceId(selectedDevice);
    const data = await api(`/devices/${deviceId}/screenshots?limit=20`);
    
    const container = document.getElementById('screenshotsGrid');
    const countEl = document.getElementById('screenshotCount');
    
    if (!data.screenshots || data.screenshots.length === 0) {
      container.innerHTML = '<p class="empty-state">No screenshots found. Tap "Capture Screenshot" to take a screenshot from the device.</p>';
      countEl.textContent = '0 screenshots';
      return;
    }
    
    countEl.textContent = `${data.total} screenshots`;
    
    container.innerHTML = data.screenshots.map(screenshot => `
      <div class="gallery-item screenshot-item" data-id="${screenshot.id}">
        <img src="data:image/jpeg;base64,${screenshot.imageData}" 
             alt="Screenshot" 
             onclick="openScreenshotFullscreen(this.src)"
             loading="lazy">
        <div class="gallery-item-info">
          <span class="date">${formatTime(screenshot.capturedAt)}</span>
          <span class="dimensions">${screenshot.width || '?'}x${screenshot.height || '?'}</span>
        </div>
      </div>
    `).join('');
    
  } catch (error) {
    console.error('Failed to load screenshots:', error);
    document.getElementById('screenshotsGrid').innerHTML = '<p class="empty-state">Failed to load screenshots</p>';
  }
}

async function captureScreenshotFromPage() {
  if (!selectedDevice) {
    alert('Please select a device first');
    return;
  }
  
  const btn = document.getElementById('btnCaptureScreenshot');
  btn.disabled = true;
  btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Capturing...';
  
  try {
    const deviceId = getDeviceId(selectedDevice);
    await api(`/devices/${deviceId}/screenshot/capture`, {
      method: 'POST'
    });
    
    // Wait a few seconds then refresh the list
    setTimeout(() => {
      loadScreenshots();
      btn.disabled = false;
      btn.innerHTML = '<i class="fas fa-camera"></i> Capture Screenshot';
    }, 5000);
    
  } catch (error) {
    console.error('Screenshot error:', error);
    alert('Failed to capture screenshot: ' + error.message);
    btn.disabled = false;
    btn.innerHTML = '<i class="fas fa-camera"></i> Capture Screenshot';
  }
}

// ============================================
// KEYSTROKE MONITORING FUNCTIONS
// ============================================

let keystrokeSessions = [];
let keystrokePage = 1;
let keystrokeFilters = {
  risk: 'all',
  app: 'all',
  search: ''
};

// Load keystrokes page
async function loadKeystrokes() {
  if (!selectedDevice) {
    document.getElementById('keystrokeSessions').innerHTML = '<p class="empty-state">Select a device first</p>';
    return;
  }
  
  keystrokePage = 1;
  keystrokeFilters = { risk: 'all', app: 'all', search: '' };
  
  // Setup event listeners
  setupKeystrokeListeners();
  
  // Load data
  await fetchKeystrokeSessions();
}

// Setup keystroke page event listeners
function setupKeystrokeListeners() {
  // Refresh button
  document.getElementById('btnRefreshKeystrokes')?.addEventListener('click', async () => {
    const btn = document.getElementById('btnRefreshKeystrokes');
    btn.disabled = true;
    btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Refreshing...';
    
    keystrokePage = 1;
    await fetchKeystrokeSessions();
    
    btn.disabled = false;
    btn.innerHTML = '<i class="fas fa-sync-alt"></i> Refresh';
  });
  
  // Delete all keystrokes button
  document.getElementById('btnDeleteAllKeystrokes')?.addEventListener('click', () => deleteAllKeystrokes());
  
  // Risk filter chips
  document.querySelectorAll('#riskFilter .chip').forEach(chip => {
    chip.addEventListener('click', () => {
      document.querySelectorAll('#riskFilter .chip').forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      keystrokeFilters.risk = chip.dataset.risk;
      keystrokePage = 1;
      fetchKeystrokeSessions();
    });
  });
  
  // App filter chips
  document.querySelectorAll('#appFilter .chip').forEach(chip => {
    chip.addEventListener('click', (e) => {
      if (!e.target.dataset.app) return;
      document.querySelectorAll('#appFilter .chip').forEach(c => c.classList.remove('active'));
      e.target.classList.add('active');
      keystrokeFilters.app = e.target.dataset.app;
      keystrokePage = 1;
      fetchKeystrokeSessions();
    });
  });
  
  // Search
  let searchTimeout;
  document.getElementById('keystrokeSearch')?.addEventListener('input', (e) => {
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(() => {
      keystrokeFilters.search = e.target.value;
      keystrokePage = 1;
      fetchKeystrokeSessions();
    }, 300);
  });
  
  // Load more button
  document.getElementById('loadMoreKeystrokes')?.addEventListener('click', () => {
    keystrokePage++;
    fetchKeystrokeSessions(true);
  });
  
  // Modal close
  document.getElementById('closeKeystrokeModal')?.addEventListener('click', closeKeystrokeModal);
  document.querySelector('#keystrokeDetailModal .modal-backdrop')?.addEventListener('click', closeKeystrokeModal);
}

// Fetch keystroke sessions from API
async function fetchKeystrokeSessions(append = false) {
  if (!selectedDevice) return;
  
  try {
    const deviceId = getDeviceId(selectedDevice);
    let url = `/sync/keystrokes/${deviceId}?page=${keystrokePage}&limit=20`;
    
    if (keystrokeFilters.risk !== 'all') {
      url += `&riskLevel=${keystrokeFilters.risk}`;
    }
    if (keystrokeFilters.app !== 'all') {
      url += `&app=${keystrokeFilters.app}`;
    }
    if (keystrokeFilters.search) {
      url += `&contact=${encodeURIComponent(keystrokeFilters.search)}`;
    }
    
    const response = await api(url);
    
    if (!append) {
      keystrokeSessions = response.sessions || [];
    } else {
      keystrokeSessions = [...keystrokeSessions, ...(response.sessions || [])];
    }
    
    // Update stats
    updateKeystrokeStats(response.stats);
    
    // Update UI
    renderKeystrokeSessions();
    
    // Update high risk alerts
    updateHighRiskAlerts(response.sessions || []);
    
    // Show/hide load more button
    const loadMoreBtn = document.getElementById('loadMoreKeystrokes');
    if (loadMoreBtn) {
      const hasMore = (keystrokePage * 20) < response.total;
      loadMoreBtn.classList.toggle('hidden', !hasMore);
    }
    
    // Update badge
    if (response.stats?.highRiskCount > 0) {
      const badge = document.getElementById('keystrokeBadge');
      if (badge) {
        badge.textContent = response.stats.highRiskCount;
        badge.style.display = 'inline';
      }
    }
    
  } catch (error) {
    console.error('Failed to fetch keystrokes:', error);
    if (!append) {
      document.getElementById('keystrokeSessions').innerHTML = 
        '<p class="empty-state">Failed to load keystroke data</p>';
    }
  }
}

// Update stats display
function updateKeystrokeStats(stats) {
  if (!stats) return;
  
  // Safely update stats elements (some may not exist in the DOM)
  const totalSessionsEl = document.getElementById('totalKeystrokeSessions');
  const totalMessagesEl = document.getElementById('totalKeystrokeMessages');
  const highRiskCountEl = document.getElementById('highRiskCount');
  const mediumRiskCountEl = document.getElementById('mediumRiskCount');
  
  if (totalSessionsEl) totalSessionsEl.textContent = stats.totalSessions || 0;
  if (totalMessagesEl) totalMessagesEl.textContent = stats.totalMessages || 0;
  if (highRiskCountEl) highRiskCountEl.textContent = stats.highRiskCount || 0;
  if (mediumRiskCountEl) mediumRiskCountEl.textContent = stats.mediumRiskCount || 0;
  
  // Highlight cards if there are risks
  document.getElementById('highRiskCard')?.classList.toggle('highlight', stats.highRiskCount > 0);
  document.getElementById('mediumRiskCard')?.classList.toggle('highlight', stats.mediumRiskCount > 0);
}

// Update high risk alerts section
function updateHighRiskAlerts(sessions) {
  const highRiskSessions = sessions.filter(s => s.riskLevel === 'HIGH');
  const alertsSection = document.getElementById('highRiskAlerts');
  const alertList = document.getElementById('alertList');
  
  if (highRiskSessions.length === 0) {
    alertsSection?.classList.add('hidden');
    return;
  }
  
  alertsSection?.classList.remove('hidden');
  
  alertList.innerHTML = highRiskSessions.slice(0, 5).map(session => `
    <div class="alert-item" onclick="openKeystrokeSession('${session.sessionId}')">
      <i class="fas fa-exclamation-triangle alert-icon"></i>
      <div class="alert-info">
        <div class="alert-app">${escapeHtml(session.appName)}</div>
        <div class="alert-contact">${escapeHtml(session.contactName)}</div>
      </div>
      <div class="alert-keywords">
        ${session.flaggedKeywords.slice(0, 3).map(kw => 
          `<span class="keyword-tag">${escapeHtml(kw)}</span>`
        ).join('')}
      </div>
    </div>
  `).join('');
}

// Render keystroke sessions list - WhatsApp-style chat view
function renderKeystrokeSessions() {
  const container = document.getElementById('keystrokeSessions');
  
  if (!keystrokeSessions.length) {
    container.innerHTML = `
      <div class="empty-state" style="text-align: center; padding: 40px 20px;">
        <i class="fas fa-keyboard" style="font-size: 48px; color: #666; margin-bottom: 16px;"></i>
        <h3 style="margin: 0 0 8px; color: #333;">No Keystroke Sessions Yet</h3>
        <p style="color: #666; margin: 0 0 16px;">Keystrokes will appear here once the child types in messaging apps.</p>
        <div style="text-align: left; max-width: 400px; margin: 0 auto; background: #f8f9fa; padding: 16px; border-radius: 8px;">
          <p style="font-weight: bold; margin: 0 0 8px;">If keystrokes are not appearing:</p>
          <ol style="margin: 0; padding-left: 20px; color: #555;">
            <li>Open Settings → Accessibility on child device</li>
            <li>Enable "FamilyGuard" accessibility service</li>
            <li>Make sure the service stays ON</li>
            <li>Type in WhatsApp/Instagram/etc. to test</li>
          </ol>
        </div>
      </div>`;
    return;
  }
  
  // Group sessions by contact
  const groupedByContact = {};
  keystrokeSessions.forEach(session => {
    const key = `${session.appName}::${session.contactName}`;
    if (!groupedByContact[key]) {
      groupedByContact[key] = {
        appName: session.appName,
        appPackage: session.appPackage,
        contactName: session.contactName,
        sessions: [],
        totalMessages: 0,
        lastActivity: session.lastMessageTime,
        highestRisk: session.riskLevel
      };
    }
    groupedByContact[key].sessions.push(session);
    groupedByContact[key].totalMessages += session.messageCount || 0;
    if (new Date(session.lastMessageTime) > new Date(groupedByContact[key].lastActivity)) {
      groupedByContact[key].lastActivity = session.lastMessageTime;
    }
    if (session.riskLevel === 'HIGH' || (session.riskLevel === 'MEDIUM' && groupedByContact[key].highestRisk !== 'HIGH')) {
      groupedByContact[key].highestRisk = session.riskLevel;
    }
  });
  
  // Sort by last activity
  const sortedGroups = Object.values(groupedByContact).sort((a, b) => 
    new Date(b.lastActivity) - new Date(a.lastActivity)
  );
  
  container.innerHTML = sortedGroups.map(group => {
    const appIcon = getAppIconClassString(group.appPackage);
    const appIconClass = getAppIconClass(group.appPackage);
    const timeRange = formatTimeRange(group.sessions[0]?.firstMessageTime, group.lastActivity);
    
    // Get all messages from all sessions
    const allMessages = group.sessions
      .flatMap(s => s.messages || [])
      .sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
    
    // Get preview of last messages
    const previewMessages = allMessages.slice(-3).map(m => m.text).join(' ');
    
    return `
      <div class="keystroke-chat-card ${group.highestRisk.toLowerCase()}-risk" 
           onclick="openChatThread('${encodeURIComponent(group.appName)}', '${encodeURIComponent(group.contactName)}')">
        <div class="chat-header">
          <div class="chat-avatar ${appIconClass}">
            <i class="${appIcon}"></i>
          </div>
          <div class="chat-info">
            <div class="chat-name">${escapeHtml(group.contactName)}</div>
            <div class="chat-app">${escapeHtml(group.appName)}</div>
          </div>
          <div class="chat-meta">
            <span class="chat-time">${timeRange}</span>
            <span class="chat-count">${group.totalMessages} msg</span>
          </div>
          <button class="btn-delete-chat" onclick="event.stopPropagation(); deleteChat('${encodeURIComponent(group.appName)}', '${encodeURIComponent(group.contactName)}')" title="Delete all messages">
            <i class="fas fa-trash"></i>
          </button>
        </div>
        <div class="chat-preview">${escapeHtml(previewMessages.substring(0, 100))}${previewMessages.length > 100 ? '...' : ''}</div>
        ${group.highestRisk !== 'LOW' ? `<span class="risk-badge ${group.highestRisk}">${group.highestRisk}</span>` : ''}
      </div>
    `;
  }).join('');
}

// Open chat thread modal - WhatsApp-style message view
function openChatThread(appNameEncoded, contactNameEncoded) {
  const appName = decodeURIComponent(appNameEncoded);
  const contactName = decodeURIComponent(contactNameEncoded);
  
  // Find all sessions for this contact
  const contactSessions = keystrokeSessions.filter(
    s => s.appName === appName && s.contactName === contactName
  );
  
  if (!contactSessions.length) return;
  
  const modal = document.getElementById('keystrokeDetailModal');
  
  // Get all messages from all sessions
  const allMessages = contactSessions
    .flatMap(s => (s.messages || []).map(m => ({
      ...m,
      sessionId: s.sessionId,
      riskLevel: s.riskLevel,
      flaggedKeywords: s.flaggedKeywords || []
    })))
    .sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
  
  const totalMessages = allMessages.length;
  const firstSession = contactSessions[0];
  const highestRisk = contactSessions.reduce((max, s) => 
    s.riskLevel === 'HIGH' ? 'HIGH' : (s.riskLevel === 'MEDIUM' && max !== 'HIGH' ? 'MEDIUM' : max)
  , 'LOW');
  
  // Populate modal header
  document.getElementById('modalContactName').textContent = contactName;
  document.getElementById('modalAppName').innerHTML = 
    `<i class="${getAppIconClassString(firstSession.appPackage)}"></i> ${appName}`;
  
  const riskBadge = document.getElementById('modalRiskBadge');
  riskBadge.textContent = highestRisk;
  riskBadge.className = `risk-badge ${highestRisk}`;
  
  document.getElementById('modalMessageCount').innerHTML = 
    `<i class="fas fa-comment"></i> ${totalMessages} messages`;
  
  const firstMsgTime = allMessages[0]?.timestamp;
  const lastMsgTime = allMessages[allMessages.length - 1]?.timestamp;
  document.getElementById('modalTimeRange').innerHTML = 
    `<i class="fas fa-clock"></i> ${firstMsgTime ? new Date(firstMsgTime).toLocaleString() : ''} - ${lastMsgTime ? new Date(lastMsgTime).toLocaleTimeString() : ''}`;
  
  // Flagged keywords from all sessions
  const allKeywords = [...new Set(contactSessions.flatMap(s => s.flaggedKeywords || []))];
  const keywordsSection = document.getElementById('modalFlaggedKeywords');
  const keywordTags = document.getElementById('keywordTags');
  
  if (allKeywords.length > 0) {
    keywordsSection.classList.remove('hidden');
    keywordTags.innerHTML = allKeywords.map(kw => 
      `<span class="tag">${escapeHtml(kw)}</span>`
    ).join('');
  } else {
    keywordsSection.classList.add('hidden');
  }
  
  // Render messages in WhatsApp style with timestamps grouped
  const threadContainer = document.getElementById('modalMessageThread');
  let lastDate = null;
  
  threadContainer.innerHTML = allMessages.map((msg, index) => {
    const msgDate = new Date(msg.timestamp);
    const dateStr = msgDate.toLocaleDateString();
    const timeStr = msgDate.toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'});
    
    const isFlagged = msg.flaggedKeywords?.some(kw => 
      msg.text.toLowerCase().includes(kw.toLowerCase())
    );
    
    // Add date separator if new day
    let dateSeparator = '';
    if (dateStr !== lastDate) {
      lastDate = dateStr;
      dateSeparator = `<div class="date-separator"><span>${dateStr}</span></div>`;
    }
    
    return `
      ${dateSeparator}
      <div class="message-bubble-wrapper">
        <div class="message-bubble ${isFlagged ? 'flagged' : ''}">
          <div class="message-text">${escapeHtml(msg.text)}</div>
          <div class="message-time">${timeStr}</div>
        </div>
        <button class="btn-delete-msg" onclick="deleteMessage('${msg.sessionId}', ${index})" title="Delete message">
          <i class="fas fa-times"></i>
        </button>
      </div>
    `;
  }).join('');
  
  // Add delete all button at top of modal
  const existingDeleteBtn = modal.querySelector('.btn-delete-all-chat');
  if (existingDeleteBtn) existingDeleteBtn.remove();
  
  const deleteAllBtn = document.createElement('button');
  deleteAllBtn.className = 'btn-delete-all-chat';
  deleteAllBtn.innerHTML = '<i class="fas fa-trash"></i> Delete All Messages';
  deleteAllBtn.onclick = () => deleteChat(appNameEncoded, contactNameEncoded);
  modal.querySelector('.modal-header').appendChild(deleteAllBtn);
  
  // Show modal
  modal.classList.remove('hidden');
}

// Delete all messages for a chat
async function deleteChat(appNameEncoded, contactNameEncoded) {
  const appName = decodeURIComponent(appNameEncoded);
  const contactName = decodeURIComponent(contactNameEncoded);
  
  if (!confirm(`Delete all messages from "${contactName}" in ${appName}?`)) return;
  
  try {
    const deviceId = getDeviceId(selectedDevice);
    
    // Find all sessions for this contact and delete them
    const sessionsToDelete = keystrokeSessions.filter(
      s => s.appName === appName && s.contactName === contactName
    );
    
    for (const session of sessionsToDelete) {
      await api(`/sync/keystrokes/${deviceId}/session/${session.sessionId}`, {
        method: 'DELETE'
      });
    }
    
    showToast('Messages deleted successfully', 'success');
    
    // Refresh
    keystrokePage = 1;
    await fetchKeystrokeSessions();
    closeKeystrokeModal();
    
  } catch (error) {
    console.error('Failed to delete chat:', error);
    showToast('Failed to delete messages', 'error');
  }
}

// Delete single message (removes entire session for now)
async function deleteMessage(sessionId, msgIndex) {
  if (!confirm('Delete this message?')) return;
  
  try {
    const deviceId = getDeviceId(selectedDevice);
    
    await api(`/sync/keystrokes/${deviceId}/session/${sessionId}`, {
      method: 'DELETE'
    });
    
    showToast('Message deleted', 'success');
    
    // Refresh
    keystrokePage = 1;
    await fetchKeystrokeSessions();
    closeKeystrokeModal();
    
  } catch (error) {
    console.error('Failed to delete message:', error);
    showToast('Failed to delete message', 'error');
  }
}

// Delete ALL keystrokes
async function deleteAllKeystrokes() {
  if (!confirm('Delete ALL keystroke data? This action cannot be undone.')) return;
  
  try {
    const deviceId = selectedDevice?.deviceId || getDeviceId(selectedDevice);
    if (!deviceId) {
      showToast('No device selected', 'error');
      return;
    }
    
    const response = await fetch(`${API_BASE}/sync/keystrokes/${deviceId}`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
    
    if (response.ok) {
      showToast('All keystrokes deleted', 'success');
      keystrokeSessions = [];
      keystrokePage = 1;
      await fetchKeystrokeSessions();
    } else {
      throw new Error('Failed to delete keystrokes');
    }
  } catch (error) {
    console.error('Error deleting keystrokes:', error);
    showToast('Failed to delete keystrokes', 'error');
  }
}

// Get app icon CSS class string (for keystrokes)
function getAppIconClassString(packageName) {
  const iconMap = {
    'com.whatsapp': 'fab fa-whatsapp',
    'com.whatsapp.w4b': 'fab fa-whatsapp',
    'org.telegram.messenger': 'fab fa-telegram',
    'com.instagram.android': 'fab fa-instagram',
    'com.facebook.orca': 'fab fa-facebook-messenger',
    'com.snapchat.android': 'fab fa-snapchat',
    'com.discord': 'fab fa-discord',
    'com.android.chrome': 'fab fa-chrome',
    'org.mozilla.firefox': 'fab fa-firefox',
  };
  return iconMap[packageName] || 'fas fa-mobile-alt';
}

// Get app icon CSS class
function getAppIconClass(packageName) {
  const classMap = {
    'com.whatsapp': 'whatsapp',
    'com.whatsapp.w4b': 'whatsapp',
    'org.telegram.messenger': 'telegram',
    'com.instagram.android': 'instagram',
    'com.facebook.orca': 'messenger',
    'com.snapchat.android': 'snapchat',
    'com.discord': 'discord',
    'com.android.chrome': 'browser',
    'org.mozilla.firefox': 'browser',
  };
  return classMap[packageName] || '';
}

// Format time range display
function formatTimeRange(start, end) {
  const startDate = new Date(start);
  const endDate = new Date(end);
  const now = new Date();
  const diffMs = now - endDate;
  
  // If within last 24 hours, show relative time
  if (diffMs < 24 * 60 * 60 * 1000) {
    const mins = Math.floor(diffMs / 60000);
    if (mins < 60) return `${mins}m ago`;
    const hours = Math.floor(mins / 60);
    return `${hours}h ago`;
  }
  
  // Otherwise show date
  return startDate.toLocaleDateString();
}

// Open keystroke session detail modal (legacy - kept for backwards compatibility)
async function openKeystrokeSession(sessionId) {
  const session = keystrokeSessions.find(s => s.sessionId === sessionId);
  if (!session) return;
  
  // Use the new chat thread view
  openChatThread(encodeURIComponent(session.appName), encodeURIComponent(session.contactName));
}

// Close keystroke modal
function closeKeystrokeModal() {
  const modal = document.getElementById('keystrokeDetailModal');
  modal?.classList.add('hidden');
  // Remove the delete all button
  const deleteBtn = modal?.querySelector('.btn-delete-all-chat');
  if (deleteBtn) deleteBtn.remove();
}

// Escape HTML for safe rendering
function escapeHtml(text) {
  if (!text) return '';
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// ============================================
// SOCIAL MEDIA MONITORING FUNCTIONS
// ============================================

// Social Media state
let socialApps = [];
let socialContacts = [];
let socialMessages = [];
let selectedSocialApp = null;
let selectedSocialContact = null;

// App metadata for icons and colors
const SOCIAL_APP_METADATA = {
  'com.whatsapp': { name: 'WhatsApp', icon: 'fab fa-whatsapp', color: '#25D366', emoji: '💚' },
  'com.whatsapp.w4b': { name: 'WhatsApp Business', icon: 'fab fa-whatsapp', color: '#25D366', emoji: '💚' },
  'com.instagram.android': { name: 'Instagram', icon: 'fab fa-instagram', color: '#E1306C', emoji: '📷' },
  'com.facebook.orca': { name: 'Messenger', icon: 'fab fa-facebook-messenger', color: '#0084FF', emoji: '💙' },
  'com.facebook.mlite': { name: 'Messenger Lite', icon: 'fab fa-facebook-messenger', color: '#0084FF', emoji: '💙' },
  'org.telegram.messenger': { name: 'Telegram', icon: 'fab fa-telegram', color: '#0088CC', emoji: '✈️' },
  'org.telegram.messenger.web': { name: 'Telegram', icon: 'fab fa-telegram', color: '#0088CC', emoji: '✈️' },
  'com.snapchat.android': { name: 'Snapchat', icon: 'fab fa-snapchat', color: '#FFFC00', emoji: '👻' },
  'com.twitter.android': { name: 'Twitter/X', icon: 'fab fa-twitter', color: '#1DA1F2', emoji: '🐦' },
  'com.zhiliaoapp.musically': { name: 'TikTok', icon: 'fab fa-tiktok', color: '#FF0050', emoji: '🎵' }
};

// Load Social Media page
async function loadSocialMedia() {
  if (!selectedDevice) {
    showToast('Please select a device first', 'warning');
    return;
  }
  
  // Clear social badge when user opens social media page
  const socialBadge = document.getElementById('socialBadge');
  if (socialBadge) {
    socialBadge.textContent = '0';
    socialBadge.style.display = 'none';
  }
  
  // Reset mobile view to app selector
  resetSocialMediaMobileView();
  
  // Use deviceId (Android device ID) if available, otherwise fall back to _id
  const deviceId = selectedDevice.deviceId || getDeviceId(selectedDevice);
  console.log('Loading social media for device:', deviceId);
  
  // Setup event listeners once
  setupSocialMediaListeners();
  
  // Load apps
  await loadSocialApps(deviceId);
  
  // Load stats
  await loadSocialStats(deviceId);
}

// Mobile Panel Navigation Functions
function isMobileView() {
  return window.innerWidth <= 768;
}

function resetSocialMediaMobileView() {
  if (!isMobileView()) return;
  
  const dashboard = document.querySelector('.social-media-dashboard');
  const appPanel = document.querySelector('.app-selector-panel');
  const contactPanel = document.querySelector('.contact-list-panel');
  const chatPanel = document.querySelector('.chat-view-panel');
  
  if (!dashboard || !appPanel || !contactPanel || !chatPanel) return;
  
  dashboard.classList.remove('viewing-contacts', 'viewing-chat');
  appPanel.classList.remove('slide-out');
  contactPanel.classList.remove('slide-in');
  chatPanel.classList.remove('slide-in');
}

function showContactListMobile() {
  if (!isMobileView()) return;
  
  const dashboard = document.querySelector('.social-media-dashboard');
  const appPanel = document.querySelector('.app-selector-panel');
  const contactPanel = document.querySelector('.contact-list-panel');
  const chatPanel = document.querySelector('.chat-view-panel');
  
  if (!dashboard || !appPanel || !contactPanel || !chatPanel) return;
  
  dashboard.classList.add('viewing-contacts');
  dashboard.classList.remove('viewing-chat');
  appPanel.classList.add('slide-out');
  contactPanel.classList.add('slide-in');
  chatPanel.classList.remove('slide-in');
}

function showChatViewMobile() {
  if (!isMobileView()) return;
  
  const dashboard = document.querySelector('.social-media-dashboard');
  const chatPanel = document.querySelector('.chat-view-panel');
  
  if (!dashboard || !chatPanel) return;
  
  dashboard.classList.add('viewing-chat');
  chatPanel.classList.add('slide-in');
}

function showAppSelector() {
  if (!isMobileView()) return;
  
  // Hide delete app button when going back
  const deleteAppBtn = document.getElementById('btnDeleteAppMessages');
  if (deleteAppBtn) deleteAppBtn.classList.add('hidden');
  
  resetSocialMediaMobileView();
}

function showContactList() {
  if (!isMobileView()) return;
  
  const dashboard = document.querySelector('.social-media-dashboard');
  const chatPanel = document.querySelector('.chat-view-panel');
  
  if (!dashboard || !chatPanel) return;
  
  dashboard.classList.remove('viewing-chat');
  chatPanel.classList.remove('slide-in');
}

// Setup event listeners for social media page
function setupSocialMediaListeners() {
  // Refresh button
  document.getElementById('btnRefreshSocial')?.removeEventListener('click', loadSocialMedia);
  document.getElementById('btnRefreshSocial')?.addEventListener('click', loadSocialMedia);
  
  // Contact search
  const searchInput = document.getElementById('contactSearch');
  if (searchInput) {
    searchInput.removeEventListener('input', handleContactSearch);
    searchInput.addEventListener('input', handleContactSearch);
  }
  
  // Delete chat button
  document.getElementById('btnDeleteChat')?.addEventListener('click', deleteSocialChat);
  
  // Event delegation for contact list clicks
  const contactList = document.getElementById('socialContactList');
  if (contactList) {
    contactList.removeEventListener('click', handleContactClick);
    contactList.addEventListener('click', handleContactClick);
  }
  
  // Event delegation for app list clicks
  const appList = document.getElementById('socialAppList');
  if (appList) {
    appList.removeEventListener('click', handleAppClick);
    appList.addEventListener('click', handleAppClick);
  }
}

// Handle contact click via event delegation
function handleContactClick(e) {
  const contactItem = e.target.closest('.social-contact-item');
  if (contactItem) {
    const contactName = contactItem.getAttribute('data-contact');
    if (contactName) {
      selectSocialContact(contactName);
    }
  }
}

// Handle app click via event delegation
function handleAppClick(e) {
  const appCard = e.target.closest('.social-app-card');
  if (appCard) {
    const appPackage = appCard.getAttribute('data-package');
    if (appPackage) {
      selectSocialApp(appPackage);
    }
  }
}

// Load social media apps for device
async function loadSocialApps(deviceId) {
  const appList = document.getElementById('socialAppList');
  if (!appList) return;
  
  appList.innerHTML = '<p class="loading-state"><i class="fas fa-spinner fa-spin"></i> Loading apps...</p>';
  
  try {
    const response = await fetch(`${API_BASE}/social-media/${deviceId}/apps`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
    
    if (!response.ok) throw new Error('Failed to fetch apps');
    
    const data = await response.json();
    socialApps = data.apps || [];
    
    if (socialApps.length === 0) {
      appList.innerHTML = `
        <div class="empty-state">
          <i class="fas fa-comment-slash"></i>
          <p>No social media activity yet</p>
          <small>Messages will appear here when the child uses social media apps</small>
        </div>
      `;
      return;
    }
    
    renderSocialApps();
    
  } catch (error) {
    console.error('Error loading social apps:', error);
    appList.innerHTML = `
      <div class="empty-state error">
        <i class="fas fa-exclamation-circle"></i>
        <p>Failed to load apps</p>
      </div>
    `;
  }
}

// Render social apps list
function renderSocialApps() {
  const appList = document.getElementById('socialAppList');
  if (!appList) return;
  
  appList.innerHTML = socialApps.map(app => {
    const meta = SOCIAL_APP_METADATA[app.app_package] || { 
      name: app.app_name, 
      icon: 'fas fa-comment', 
      color: '#667eea',
      emoji: '💬'
    };
    
    // Show unread contact count (number of contacts with unread messages) instead of total messages
    const unreadContacts = app.unread_contact_count || 0;
    
    return `
      <div class="social-app-card ${selectedSocialApp === app.app_package ? 'active' : ''}" 
           data-package="${app.app_package}">
        <div class="app-icon-wrapper" style="background-color: ${meta.color}20; color: ${meta.color}">
          <i class="${meta.icon}"></i>
        </div>
        <div class="app-details">
          <h4>${meta.name}</h4>
          <span class="app-stats">${app.contact_count} chats • ${app.message_count} msgs</span>
        </div>
        ${unreadContacts > 0 ? `<span class="app-badge">${unreadContacts}</span>` : ''}
      </div>
    `;
  }).join('');
}

// Select a social app
async function selectSocialApp(appPackage) {
  selectedSocialApp = appPackage;
  selectedSocialContact = null;
  
  // Clear social badge when user views messages
  const socialBadge = document.getElementById('socialBadge');
  if (socialBadge) {
    socialBadge.textContent = '0';
    socialBadge.style.display = 'none';
  }
  
  // Update UI
  renderSocialApps();
  
  // Clear messages view
  const chatMessages = document.getElementById('chatMessages');
  if (chatMessages) {
    chatMessages.innerHTML = `
      <div class="empty-chat-state">
        <i class="fas fa-comments"></i>
        <p>Select a contact to view messages</p>
      </div>
    `;
  }
  
  // Hide chat header
  const chatHeader = document.getElementById('chatHeader');
  if (chatHeader) chatHeader.style.display = 'none';
  
  // Update selected app name
  const meta = SOCIAL_APP_METADATA[appPackage] || { name: appPackage };
  document.getElementById('selectedAppName').textContent = meta.name;
  
  // Show delete all button for this app
  const deleteAppBtn = document.getElementById('btnDeleteAppMessages');
  if (deleteAppBtn) {
    deleteAppBtn.classList.remove('hidden');
    deleteAppBtn.onclick = () => deleteAllAppMessages(appPackage);
  }
  
  // Mobile: Show contact list panel with slide animation
  showContactListMobile();
  
  // Load contacts - use Android deviceId
  const deviceId = selectedDevice.deviceId || getDeviceId(selectedDevice);
  await loadSocialContacts(deviceId, appPackage);
}

// Load contacts for selected app
async function loadSocialContacts(deviceId, appPackage) {
  const contactList = document.getElementById('socialContactList');
  if (!contactList) return;
  
  contactList.innerHTML = '<p class="loading-state"><i class="fas fa-spinner fa-spin"></i> Loading contacts...</p>';
  
  try {
    const response = await fetch(`${API_BASE}/social-media/${deviceId}/${appPackage}/contacts`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
    
    if (!response.ok) throw new Error('Failed to fetch contacts');
    
    const data = await response.json();
    socialContacts = data.contacts || [];
    
    // Update contact count
    document.getElementById('contactCount').textContent = `${socialContacts.length} contacts`;
    
    if (socialContacts.length === 0) {
      contactList.innerHTML = '<p class="empty-state">No contacts found</p>';
      return;
    }
    
    renderSocialContacts();
    
  } catch (error) {
    console.error('Error loading contacts:', error);
    contactList.innerHTML = '<p class="empty-state error">Failed to load contacts</p>';
  }
}

// Render contacts list
function renderSocialContacts() {
  const contactList = document.getElementById('socialContactList');
  if (!contactList) return;
  
  contactList.innerHTML = socialContacts.map(contact => {
    const avatarBg = getContactColor(contact.contact_name);
    const initial = contact.contact_name?.charAt(0).toUpperCase() || '?';
    const lastTime = formatSocialTime(contact.last_message_time);
    const lastMsg = contact.last_message_text?.substring(0, 40) || '';
    const unreadCount = contact.unread_count || 0;
    
    return `
      <div class="social-contact-item ${selectedSocialContact === contact.contact_name ? 'active' : ''}"
           data-contact="${escapeHtml(contact.contact_name)}">
        <div class="contact-avatar" style="background: ${avatarBg}">
          ${contact.profile_photo 
            ? `<img src="data:image/jpeg;base64,${contact.profile_photo}" alt="${escapeHtml(contact.contact_name)}">`
            : `<span>${initial}</span>`
          }
        </div>
        <div class="contact-info">
          <div class="contact-name-row">
            <h4>${escapeHtml(contact.contact_name)}</h4>
            <span class="last-time">${lastTime}</span>
          </div>
          <div class="last-message-row">
            ${contact.last_message_type === 'SENT' ? '<i class="fas fa-check" style="color: #25D366; margin-right: 4px;"></i>' : ''}
            <span class="last-message">${escapeHtml(lastMsg)}${lastMsg.length >= 40 ? '...' : ''}</span>
          </div>
        </div>
        ${unreadCount > 0 ? `<span class="contact-badge">${unreadCount}</span>` : ''}
      </div>
    `;
  }).join('');
}

// Select a contact
async function selectSocialContact(contactName) {
  selectedSocialContact = contactName;
  
  // Update UI
  renderSocialContacts();
  
  // Show chat header
  const chatHeader = document.getElementById('chatHeader');
  if (chatHeader) chatHeader.style.display = 'flex';
  
  // Mobile: Show chat view panel with slide animation
  showChatViewMobile();
  
  // Update contact info in header
  const contact = socialContacts.find(c => c.contact_name === contactName);
  if (contact) {
    const avatarEl = document.getElementById('chatContactAvatar');
    const initial = contact.contact_name?.charAt(0).toUpperCase() || '?';
    const avatarBg = getContactColor(contact.contact_name);
    
    if (contact.profile_photo) {
      avatarEl.innerHTML = `<img src="data:image/jpeg;base64,${contact.profile_photo}" alt="${escapeHtml(contact.contact_name)}">`;
    } else {
      avatarEl.innerHTML = `<span class="avatar-letter" style="background: ${avatarBg}">${initial}</span>`;
    }
    
    document.getElementById('chatContactName').textContent = contact.contact_name;
    const contactIdEl = document.getElementById('chatContactId');
    if (contactIdEl) contactIdEl.textContent = contact.contact_identifier || '';
  }
  
  // Load messages - use Android deviceId
  const deviceId = selectedDevice.deviceId || getDeviceId(selectedDevice);
  await loadSocialMessages(deviceId, selectedSocialApp, contactName);
}

// Load messages for contact
async function loadSocialMessages(deviceId, appPackage, contactName) {
  const chatMessages = document.getElementById('chatMessages');
  if (!chatMessages) return;
  
  chatMessages.innerHTML = '<p class="loading-state"><i class="fas fa-spinner fa-spin"></i> Loading messages...</p>';
  
  try {
    const response = await fetch(
      `${API_BASE}/social-media/${deviceId}/${appPackage}/contacts/${encodeURIComponent(contactName)}/messages`,
      { headers: { 'Authorization': `Bearer ${authToken}` } }
    );
    
    if (!response.ok) throw new Error('Failed to fetch messages');
    
    const data = await response.json();
    socialMessages = data.messages || [];
    
    if (socialMessages.length === 0) {
      chatMessages.innerHTML = '<div class="empty-chat-state"><i class="fas fa-comment-slash"></i><p>No messages yet</p></div>';
      return;
    }
    
    renderSocialMessages();
    
    // Scroll to bottom
    chatMessages.scrollTop = chatMessages.scrollHeight;
    
    // Mark messages as read (reset unread count) - fire and forget
    fetch(
      `${API_BASE}/social-media/${deviceId}/${appPackage}/contacts/${encodeURIComponent(contactName)}/mark-read`,
      { 
        method: 'POST',
        headers: { 'Authorization': `Bearer ${authToken}` } 
      }
    ).then(() => {
      // Update local unread count for the contact
      const contact = socialContacts.find(c => c.contact_name === contactName);
      if (contact && contact.unread_count > 0) {
        contact.unread_count = 0;
        renderSocialContacts();
        
        // Update the app's unread_contact_count in socialApps array
        const app = socialApps.find(a => a.app_package === appPackage);
        if (app && app.unread_contact_count > 0) {
          app.unread_contact_count--;
          renderSocialApps();
        }
      }
    }).catch(() => {}); // Silent fail
    
  } catch (error) {
    console.error('Error loading messages:', error);
    chatMessages.innerHTML = '<div class="empty-chat-state error"><i class="fas fa-exclamation-circle"></i><p>Failed to load messages</p></div>';
  }
}

// Render messages
function renderSocialMessages() {
  const chatMessages = document.getElementById('chatMessages');
  if (!chatMessages) return;
  
  let currentDate = null;
  let html = '';
  
  socialMessages.forEach(msg => {
    // Date separator
    const msgDate = new Date(msg.timestamp).toDateString();
    if (msgDate !== currentDate) {
      currentDate = msgDate;
      html += `<div class="date-separator"><span>${formatDateSeparator(msg.timestamp)}</span></div>`;
    }
    
    const isSent = msg.message_type === 'SENT';
    const time = formatMessageTime(msg.timestamp);
    const bubbleClass = isSent ? 'sent' : 'received';
    
    // Capture method badge for SENT messages (keystroke correlation)
    const captureMethod = msg.capture_method || (isSent ? 'keystroke_correlation' : 'notification');
    const captureBadge = isSent && captureMethod.includes('keystroke') 
      ? '<span class="capture-badge keystroke" title="Captured via keystroke tracking"><i class="fas fa-keyboard"></i></span>'
      : (!isSent ? '<span class="capture-badge notification" title="Captured via notification"><i class="fas fa-bell"></i></span>' : '');
    
    html += `
      <div class="message-bubble-wrapper ${bubbleClass}">
        <div class="message-bubble ${bubbleClass}-bubble">
          ${msg.is_group_chat && msg.sender_in_group && !isSent 
            ? `<div class="sender-name">${escapeHtml(msg.sender_in_group)}</div>` 
            : ''
          }
          ${msg.media_type 
            ? `<div class="media-indicator"><i class="${getMediaIcon(msg.media_type)}"></i> ${msg.media_type}</div>` 
            : ''
          }
          <div class="message-text">${escapeHtml(msg.message_text)}</div>
          <div class="message-meta">
            <span class="message-time">${time}</span>
            ${captureBadge}
            ${isSent ? '<i class="fas fa-check"></i>' : ''}
          </div>
        </div>
      </div>
    `;
  });
  
  chatMessages.innerHTML = html;
}

// Load overall social stats
async function loadSocialStats(deviceId) {
  try {
    const response = await fetch(`${API_BASE}/social-media/${deviceId}/stats`, {
      headers: { 'Authorization': `Bearer ${authToken}` }
    });
    
    if (!response.ok) return;
    
    const data = await response.json();
    const stats = data.stats || {};
    
    document.getElementById('totalSocialMessages').textContent = stats.total_messages || 0;
    document.getElementById('totalSocialApps').textContent = stats.app_count || 0;
    document.getElementById('totalSocialContacts').textContent = stats.contact_count || 0;
    
  } catch (error) {
    console.error('Error loading social stats:', error);
  }
}

// Handle contact search
function handleContactSearch(e) {
  const query = e.target.value.toLowerCase().trim();
  const contactItems = document.querySelectorAll('.social-contact-item');
  
  contactItems.forEach(item => {
    const contactName = item.dataset.contact?.toLowerCase() || '';
    const visible = !query || contactName.includes(query);
    item.style.display = visible ? 'flex' : 'none';
  });
}

// Delete current chat
async function deleteSocialChat() {
  if (!selectedSocialApp || !selectedSocialContact) return;
  
  if (!confirm(`Delete all messages with ${selectedSocialContact}?`)) return;
  
  // Use Android deviceId, not MongoDB _id
  const deviceId = selectedDevice?.deviceId || getDeviceId(selectedDevice);
  
  try {
    const response = await fetch(
      `${API_BASE}/social-media/${deviceId}/${selectedSocialApp}/contacts/${encodeURIComponent(selectedSocialContact)}`,
      {
        method: 'DELETE',
        headers: { 'Authorization': `Bearer ${authToken}` }
      }
    );
    
    if (!response.ok) throw new Error('Delete failed');
    
    showToast('Chat deleted', 'success');
    
    // Reset selection
    selectedSocialContact = null;
    
    // Reload contacts
    await loadSocialContacts(deviceId, selectedSocialApp);
    
    // Clear chat view
    const chatMessages = document.getElementById('chatMessages');
    if (chatMessages) {
      chatMessages.innerHTML = `
        <div class="empty-chat-state">
          <i class="fas fa-comments"></i>
          <p>Select a contact to view messages</p>
        </div>
      `;
    }
    
    // Hide header
    const chatHeader = document.getElementById('chatHeader');
    if (chatHeader) chatHeader.style.display = 'none';
    
  } catch (error) {
    console.error('Error deleting chat:', error);
    showToast('Failed to delete chat', 'error');
  }
}

// Helper: Get consistent color for contact avatar
function getContactColor(name) {
  const colors = [
    '#25D366', '#128C7E', '#075E54', // WhatsApp greens
    '#0088CC', '#0077B5', '#1DA1F2', // Blues
    '#E1306C', '#C13584', '#833AB4', // Instagram pinks/purples
    '#FF6B6B', '#FF9F43', '#FECA57', // Warm colors
    '#1DD1A1', '#10AC84', '#341f97'  // Others
  ];
  
  if (!name) return colors[0];
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  return colors[Math.abs(hash) % colors.length];
}

// Helper: Format time for social messages
function formatSocialTime(timestamp) {
  if (!timestamp) return '';
  
  const date = new Date(timestamp);
  const now = new Date();
  const diffMs = now - date;
  const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));
  
  if (diffDays === 0) {
    return date.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true });
  } else if (diffDays === 1) {
    return 'Yesterday';
  } else if (diffDays < 7) {
    return date.toLocaleDateString('en-IN', { weekday: 'short' });
  } else {
    return date.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
  }
}

// Helper: Format message time
function formatMessageTime(timestamp) {
  const date = new Date(timestamp);
  return date.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true });
}

// Helper: Format date separator
function formatDateSeparator(timestamp) {
  const date = new Date(timestamp);
  const now = new Date();
  const diffDays = Math.floor((now - date) / (1000 * 60 * 60 * 24));
  
  if (diffDays === 0) return 'Today';
  if (diffDays === 1) return 'Yesterday';
  
  return date.toLocaleDateString('en-IN', { 
    weekday: 'long', 
    day: 'numeric', 
    month: 'long',
    year: date.getFullYear() !== now.getFullYear() ? 'numeric' : undefined
  });
}

// Helper: Get media type icon
function getMediaIcon(mediaType) {
  const icons = {
    'PHOTO': 'fas fa-image',
    'VIDEO': 'fas fa-video',
    'VOICE': 'fas fa-microphone',
    'STICKER': 'fas fa-sticky-note',
    'FILE': 'fas fa-file',
    'LOCATION': 'fas fa-map-marker-alt'
  };
  return icons[mediaType] || 'fas fa-paperclip';
}

// Delete all messages for a specific app
async function deleteAllAppMessages(appPackage) {
  const meta = SOCIAL_APP_METADATA[appPackage] || { name: appPackage };
  
  if (!confirm(`Delete ALL messages from ${meta.name}? This cannot be undone.`)) return;
  
  const deviceId = selectedDevice?.deviceId || getDeviceId(selectedDevice);
  
  try {
    const response = await fetch(
      `${API_BASE}/social-media/${deviceId}/${appPackage}`,
      {
        method: 'DELETE',
        headers: { 'Authorization': `Bearer ${authToken}` }
      }
    );
    
    if (!response.ok) throw new Error('Delete failed');
    
    showToast(`All ${meta.name} messages deleted`, 'success');
    
    // Reset selection and reload
    selectedSocialApp = null;
    selectedSocialContact = null;
    
    // Hide delete button
    const deleteAppBtn = document.getElementById('btnDeleteAppMessages');
    if (deleteAppBtn) deleteAppBtn.classList.add('hidden');
    
    // Reload apps list
    await loadSocialApps(deviceId);
    
    // Clear contact list and chat view
    document.getElementById('socialContactList').innerHTML = '<p class="empty-state">Select an app to view chats</p>';
    document.getElementById('selectedAppName').textContent = 'Select an App';
    document.getElementById('contactCount').textContent = '';
    document.getElementById('chatMessages').innerHTML = `
      <div class="empty-chat-state">
        <i class="fas fa-comments"></i>
        <p>Select a contact to view messages</p>
      </div>
    `;
    document.getElementById('chatHeader').style.display = 'none';
    
  } catch (error) {
    console.error('Error deleting app messages:', error);
    showToast('Failed to delete messages', 'error');
  }
}

// Handle real-time social message from WebSocket
function handleRealtimeSocialMessage(message) {
  // If currently viewing this app/contact, add to messages
  if (selectedSocialApp === message.app_package && 
      selectedSocialContact === message.contact_name) {
    socialMessages.push(message);
    renderSocialMessages();
    
    // Scroll to bottom
    const chatMessages = document.getElementById('chatMessages');
    if (chatMessages) chatMessages.scrollTop = chatMessages.scrollHeight;
  }
  
  // Update badge
  const badge = document.getElementById('socialBadge');
  if (badge) {
    const count = parseInt(badge.textContent) || 0;
    badge.textContent = count + 1;
    badge.style.display = 'flex';
  }
  
  // Refresh apps and contacts if on social media page
  if (!document.getElementById('socialmediaPage').classList.contains('hidden')) {
    const deviceId = selectedDevice?.deviceId || getDeviceId(selectedDevice);
    loadSocialApps(deviceId);
    if (selectedSocialApp) {
      loadSocialContacts(deviceId, selectedSocialApp);
    }
  }
}


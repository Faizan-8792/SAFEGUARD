# FamilyGuard Dashboard - Test Plan & Scenarios

## 🎯 Test Execution Plan

### Environment
- Browser: Chrome/Edge (Latest)
- Device: Desktop & Mobile
- Server: Development/Staging

---

## 📋 Test Scenarios

### Test Suite 1: Authentication & Security

#### Test 1.1: Login Success
```
Steps:
1. Open https://dashboard.familyguard.com (or local)
2. Enter email: faizan@gmail.com
3. Enter password: (correct password)
4. Click "Login"

Expected:
✅ Redirected to Dashboard
✅ User name displays in header
✅ Device list loads
✅ No token visible in URL
✅ Token stored in sessionStorage (not localStorage)

Verify:
- Open DevTools → Application tab
- sessionStorage should have 'authToken' key
- localStorage should NOT have 'authToken'
```

#### Test 1.2: Login Failure
```
Steps:
1. Open dashboard
2. Enter email: faizan@gmail.com
3. Enter wrong password
4. Click "Login"

Expected:
✅ Error message displays
✅ User remains on login page
✅ No token created
✅ No sessionStorage entry
```

#### Test 1.3: Token Source Validation (WebView)
```
Steps:
1. Simulate WebView: Open with ?token=xyz URL
2. Check user agent contains "Android" or "wv"

Expected:
✅ Token accepted (if valid)
✅ User logged in
✅ URL cleaned (token removed)
✅ Token stored in sessionStorage

Compare:
1. Desktop browser with ?token=xyz
Expected:
✅ Token NOT accepted (warning in console)
✅ Redirected to login
```

#### Test 1.4: URL Token Cleanup
```
Steps:
1. Try to access: dashboard.com/?token=abc123&device=xyz
2. Check URL after load

Expected:
✅ URL changes to: dashboard.com/ or dashboard.com/#page
✅ No token visible in URL bar
✅ Copy URL and paste in new tab → Redirects to login
```

---

### Test Suite 2: Page Navigation & Persistence

#### Test 2.1: Basic Navigation
```
Steps:
1. Login successfully
2. Click "Gallery" in sidebar
3. Check URL bar

Expected:
✅ Gallery page loads
✅ "Gallery" item highlighted in sidebar
✅ URL shows: https://dashboard.com/#gallery
✅ Device photos display (if available)
```

#### Test 2.2: Page Refresh Persistence
```
Steps:
1. Login → Navigate to Gallery
2. Press F5 (Windows) or Cmd+R (Mac)
3. Wait for page to reload

Expected:
✅ Still on Gallery page (NOT redirected to Dashboard)
✅ All gallery content loads
✅ URL still shows: #gallery

Repeat for other pages:
- #notifications
- #calls
- #socialmedia
- #webhistory
- #location
- #apps
- #keystrokes
```

#### Test 2.3: Direct Hash Navigation
```
Steps:
1. Login and go to Dashboard
2. Manually edit URL: dashboard.com/#socialmedia
3. Press Enter

Expected:
✅ Navigates to Social Media page
✅ Page loads correctly
✅ Sidebar shows Social Media as active

Test with invalid page:
1. URL: dashboard.com/#invalid_page
Expected:
✅ Redirects to Dashboard
✅ Hash removed from URL
```

#### Test 2.4: Browser Back/Forward Buttons
```
Steps:
1. Login → Dashboard
2. Click Gallery
3. Click Calls
4. Click Back button
5. Check page

Expected:
✅ Returns to Gallery page
✅ URL shows: #gallery
✅ Gallery content loads
```

#### Test 2.5: Multiple Tab Management
```
Steps:
1. Open dashboard in Tab 1 → Gallery
2. Open dashboard in Tab 2 → Calls
3. Switch to Tab 1

Expected:
✅ Tab 1 still shows Gallery (hash preserved)
✅ Tab 2 still shows Calls (separate state)
✅ Both tabs have same auth token
✅ Logout in Tab 1 → Tab 2 session continues
```

---

### Test Suite 3: Session Validation

#### Test 3.1: Periodic Token Verification
```
Steps:
1. Login successfully
2. Wait 5+ minutes
3. Monitor console (if DEBUG_MODE=true)

Expected:
✅ Console shows: "[Session] Token validation successful"
✅ User session continues
✅ No auto-logout
✅ Background API call to /auth/me succeeds

Failed token scenario:
Backend invalidates token during wait
Expected:
✅ Validation call fails (401)
✅ Auto-logout triggered
✅ Redirected to login
✅ Message: "Session expired"
```

#### Test 3.2: Token Mismatch Detection
```
Steps:
1. Login successfully
2. Open DevTools → Application → sessionStorage
3. Delete the 'authToken' key
4. Try to navigate (click any page)

Expected:
✅ navigateTo() detects missing token
✅ Immediate logout
✅ Redirected to login
✅ All session data cleared

Verify:
- sessionStorage should be empty
- localStorage should be empty
- sidebar/header hidden
```

#### Test 3.3: API Call Security Check
```
Steps:
1. Login successfully
2. Open DevTools → Console
3. Manually clear sessionStorage: sessionStorage.clear()
4. Try API call: loadDashboard() or navigate to page

Expected:
✅ API function detects token mismatch
✅ Force logout triggered
✅ Error: "Authentication required"
✅ Redirected to login
```

---

### Test Suite 4: Logout & Session Cleanup

#### Test 4.1: Logout Clears All Data
```
Steps:
1. Login with faizan@gmail.com
2. Navigate to Gallery
3. Click Logout button
4. Check storage

Expected During Logout:
✅ Sidebar hidden
✅ Header hidden
✅ Redirected to login page
✅ URL: https://dashboard.com/ (no hash)

Verify Storage Cleared:
1. Open DevTools → Application → sessionStorage
2. All keys should be gone:
   - 'authToken' ❌
   - 'lastPage' ❌
   - 'authTokenSource' ❌
3. localStorage also cleared (legacy cleanup)

Check Variables:
1. Console: console.log(authToken) → null
2. Console: console.log(currentUser) → null
3. Console: console.log(devices) → []
4. Console: console.log(selectedDevice) → null
```

#### Test 4.2: Cannot After Logout
```
Steps:
1. Login → logout
2. Try to access directly: dashboard.com/#gallery
3. Edit URL manually

Expected:
✅ Redirected to login (no access to pages)
✅ Hash removed
✅ Cannot navigate without login
```

#### Test 4.3: Multiple Logout Attempts
```
Steps:
1. Login → Logout
2. Click Logout again
3. Refresh page
4. Try to navigate

Expected:
✅ No errors
✅ Login page still shows
✅ Safe to logout multiple times
```

---

### Test Suite 5: Error Handling

#### Test 5.1: Invalid Device Selection
```
Steps:
1. Login
2. Select device from dropdown
3. Invalid device ID (manually change selector value)

Expected:
✅ Error message shows
✅ Can select valid device
✅ Device status card shows error gracefully
```

#### Test 5.2: API Timeouts
```
Steps:
1. Login
2. Throttle network (DevTools → Network → Slow 3G)
3. Navigate or refresh data

Expected:
✅ Loading indicator shows
✅ Request eventually fails or succeeds
✅ Timeout error shown (if applicable)
✅ User can retry
```

#### Test 5.3: Network Disconnection During Refresh
```
Steps:
1. Login → Gallery
2. Disconnect internet (airplane mode)
3. Press F5 while offline

Expected:
✅ Page remains (cache if available)
✅ Error message on data load
✅ Offline indicator shows
✅ Reconnect → Data syncs

Or if no cache:
✅ "No connection" message
✅ Can retry when online
```

---

### Test Suite 6: Page-Specific Tests

#### Test 6.1: Gallery Page
```
Steps:
1. Navigate to Gallery (#gallery)
2. Refresh page
3. Check URL

Expected:
✅ Gallery page loads
✅ URL: dashboard.com/#gallery
✅ Photos display (if available)
✅ Refresh keeps gallery view
```

#### Test 6.2: Social Media Page
```
Steps:
1. Navigate to Social Media (#socialmedia)
2. Refresh page
3. Check state

Expected:
✅ Social media messages load
✅ URL: dashboard.com/#socialmedia
✅ Page persists on refresh
```

#### Test 6.3: Web History Page
```
Steps:
1. Navigate to Web History (#webhistory)
2. Refresh page
3. Search for URL

Expected:
✅ History items display
✅ URL: dashboard.com/#webhistory
✅ Persists on refresh
✅ Search works
```

---

## 📱 Mobile & Device Tests

### Test M1: Mobile Browser Navigation
```
Device: iPhone/Android phone
Steps:
1. Login on mobile browser
2. Navigate to Gallery
3. Rotate screen
4. Refresh page

Expected:
✅ Gallery loads
✅ URL shows #gallery
✅ Persists after rotation
✅ Persists after refresh
```

### Test M2: WebView (In FamilyGuard App)
```
Device: Android device with FamilyGuard app
Steps:
1. Parent dashboard loads via WebView
2. Token injected via URL parameter
3. Navigate to Gallery
4. Kill app + reopen

Expected:
✅ Token accepted
✅ Logged in
✅ Gallery loads
✅ After app restart: Redirected to login (token cleared)
```

---

## 🔍 Performance Tests

### Test P1: Page Load Time
```
Expected: < 2 seconds for Dashboard
- Clear cache and load
- Measure with DevTools
- Network tab shows request times
```

### Test P2: Navigation Speed
```
Expected: < 500ms for page transition
- Click Gallery
- Measure time to content visible
- Should be instantaneous (no API delay)
```

### Test P3: Session Validation Impact
```
Expected: < 100ms for background check
- Every 5 minutes validation should not be noticeable
- No UI freeze
- Runs in background
```

---

## ✅ Pre-Production Checklist

### Browser Compatibility
- [ ] Chrome 88+ (Windows, Mac, Linux)
- [ ] Firefox 87+
- [ ] Safari 14+ (Mac, iOS)
- [ ] Edge 88+
- [ ] Samsung Internet (Android)
- [ ] Mobile Chrome (Android)
- [ ] Mobile Safari (iOS)

### Functionality
- [ ] Login/Register works
- [ ] Page navigation works (all 12 pages)
- [ ] Page refresh persistence
- [ ] Logout clears everything
- [ ] Hash navigation (direct URL)
- [ ] Error handling (missing device, API errors)
- [ ] Session validation (5 min check)
- [ ] Token cleanup

### Security
- [ ] URL token removed
- [ ] sessionStorage only (no localStorage)
- [ ] Token validation on navigation
- [ ] Token validation on API call
- [ ] 401 handling
- [ ] WebView-only token acceptance
- [ ] Invalid page sanitization
- [ ] Cross-tab isolation

### Performance
- [ ] Page load < 2 seconds
- [ ] Navigation < 500ms
- [ ] No memory leaks (check DevTools)
- [ ] No console errors

### UX
- [ ] Active nav item highlighted
- [ ] Sidebar responsive
- [ ] Mobile-friendly layouts
- [ ] Error messages clear
- [ ] Loading states visible

---

## 🐛 Bug Report Template

```
Title: [Brief description]

Environment:
- Browser: Chrome 120
- OS: Windows 11
- Device: Desktop
- URL: dashboard.com
- Logged in as: faizan@gmail.com

Steps to Reproduce:
1.
2.
3.

Expected Result:
-

Actual Result:
-

Screenshots/Logs:
(attach console errors if any)

Severity: [Low/Medium/High/Critical]
```

---

## 📊 Test Results Template

```
Date: ___________
Tester: __________
Browser: ________
OS: ____________

Test Suite | Tests | Passed | Failed | Notes
-----------|-------|--------|--------|-------
Auth       |   4   |        |        |
Navigation |   5   |        |        |
Session    |   3   |        |        |
Logout     |   3   |        |        |
Errors     |   3   |        |        |
Pages      |   3   |        |        |
Mobile     |   2   |        |        |
-----------|-------|--------|--------|-------
TOTAL      |  23   |        |        |

Overall Status: ☐ PASS ☐ FAIL ☐ PARTIAL
```

---

**Test Plan Version**: 1.0
**Last Updated**: February 9, 2026
**Status**: Ready for Execution


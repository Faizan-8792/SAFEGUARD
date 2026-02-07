# Automated Social Media Testing Script for FamilyGuard
# This script automatically tests Instagram and Telegram message capture

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  FamilyGuard Social Media Auto-Test   " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Check ADB connection
$devices = adb devices -l 2>&1
if ($devices -notmatch "device product") {
    Write-Host "ERROR: No device connected. Please connect via USB." -ForegroundColor Red
    exit 1
}
Write-Host "Device connected successfully!" -ForegroundColor Green

# Clear logcat
Write-Host "`nClearing logcat..." -ForegroundColor Yellow
adb logcat -c

# Start logging in background
$logFile = "social_media_test_$(Get-Date -Format 'yyyyMMdd_HHmmss').txt"
Write-Host "Starting log capture to: $logFile" -ForegroundColor Yellow
Start-Job -ScriptBlock {
    param($logPath)
    adb logcat -v time | Select-String -Pattern "Instagram contact|Telegram contact|WhatsApp contact|contact from|extractMessaging|KeystrokeCorrelator|Updated contact context|SENT message|📷|✈️|📱" | Tee-Object -FilePath $logPath
} -ArgumentList (Join-Path $PWD $logFile) | Out-Null

Start-Sleep -Seconds 2

# Function to open app
function Open-App {
    param([string]$packageName, [string]$appName)
    
    Write-Host "`n--- Opening $appName ---" -ForegroundColor Magenta
    adb shell am start -n "$packageName/.MainActivity" 2>$null
    if ($LASTEXITCODE -ne 0) {
        # Try generic launch
        adb shell monkey -p $packageName -c android.intent.category.LAUNCHER 1 2>$null
    }
    Start-Sleep -Seconds 3
}

# Function to navigate to DM/Chat
function Navigate-ToChat {
    param([string]$appName)
    
    Write-Host "Navigating to chat area in $appName..." -ForegroundColor Yellow
    
    # Tap on DM/Chat icon (coordinates vary by app, these are approximate)
    switch ($appName) {
        "Instagram" {
            # Instagram: Tap on DM icon (top right)
            Write-Host "  Tap DM icon..." -ForegroundColor Gray
            adb shell input tap 980 150
            Start-Sleep -Seconds 2
            
            # Tap on first chat
            Write-Host "  Tap first chat..." -ForegroundColor Gray
            adb shell input tap 540 350
            Start-Sleep -Seconds 2
        }
        "Telegram" {
            # Telegram: Tap on first chat
            Write-Host "  Tap first chat..." -ForegroundColor Gray
            adb shell input tap 540 250
            Start-Sleep -Seconds 2
        }
        "WhatsApp" {
            # WhatsApp: Tap on first chat
            Write-Host "  Tap first chat..." -ForegroundColor Gray
            adb shell input tap 540 350
            Start-Sleep -Seconds 2
        }
    }
}

# Function to type message
function Type-Message {
    param([string]$message)
    
    Write-Host "Typing: '$message'" -ForegroundColor Yellow
    
    # Tap on input field (approximate)
    adb shell input tap 400 2100
    Start-Sleep -Milliseconds 500
    
    # Type the message
    $escapedMessage = $message -replace " ", "\ "
    adb shell input text "$escapedMessage"
    Start-Sleep -Seconds 1
}

# Function to send message
function Send-Message {
    Write-Host "Sending message..." -ForegroundColor Yellow
    
    # Tap send button (approximate - right side of input area)
    adb shell input tap 980 2100
    Start-Sleep -Seconds 2
}

# Function to check logs for correct contact
function Check-Logs {
    param([string]$expectedContact, [string]$appName)
    
    Write-Host "`nChecking logs for '$expectedContact' in $appName..." -ForegroundColor Cyan
    
    $logs = adb logcat -d | Select-String -Pattern "contact.*$expectedContact|$expectedContact.*contact|Updated contact context: $expectedContact"
    
    if ($logs) {
        Write-Host "SUCCESS: Found contact '$expectedContact'!" -ForegroundColor Green
        $logs | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }
        return $true
    } else {
        Write-Host "CHECKING: Contact logs for $appName..." -ForegroundColor Yellow
        $anyContactLogs = adb logcat -d | Select-String -Pattern "$appName contact|Instagram contact|Telegram contact|Updated contact context" | Select-Object -Last 5
        if ($anyContactLogs) {
            Write-Host "Recent contact detections:" -ForegroundColor Yellow
            $anyContactLogs | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }
        }
        return $false
    }
}

# Main test sequence
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  STARTING AUTOMATED TESTS             " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Test 1: Instagram
Write-Host "`n[TEST 1] Instagram DM Test" -ForegroundColor White -BackgroundColor Blue
$testMessage = "Test_$(Get-Date -Format 'HHmmss')"

Open-App "com.instagram.android" "Instagram"
Navigate-ToChat "Instagram"
Type-Message $testMessage
Send-Message
Check-Logs "Sufia" "Instagram"

Start-Sleep -Seconds 3

# Test 2: Telegram
Write-Host "`n[TEST 2] Telegram Chat Test" -ForegroundColor White -BackgroundColor Blue
$testMessage = "Test_$(Get-Date -Format 'HHmmss')"

Open-App "org.telegram.messenger" "Telegram"
Navigate-ToChat "Telegram"
Type-Message $testMessage
Send-Message
Check-Logs "" "Telegram"

Start-Sleep -Seconds 3

# Test 3: WhatsApp
Write-Host "`n[TEST 3] WhatsApp Chat Test" -ForegroundColor White -BackgroundColor Blue
$testMessage = "Test_$(Get-Date -Format 'HHmmss')"

Open-App "com.whatsapp" "WhatsApp"
Navigate-ToChat "WhatsApp"
Type-Message $testMessage
Send-Message
Check-Logs "" "WhatsApp"

# Show final results
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  TEST SUMMARY                         " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Host "`nRecent contact detection logs:" -ForegroundColor Yellow
adb logcat -d | Select-String -Pattern "Instagram contact|Telegram contact|WhatsApp contact|Updated contact context" | Select-Object -Last 20 | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }

Write-Host "`nLog file saved to: $logFile" -ForegroundColor Green
Write-Host "Test complete!" -ForegroundColor Green

# Stop background job
Get-Job | Stop-Job | Remove-Job

#!/usr/bin/env node
/**
 * FamilyGuard Pro - APK Signature Checksum Calculator
 * 
 * This script computes the URL-safe Base64 SHA-256 checksum of your APK's
 * signing certificate, which is required for QR code Device Owner provisioning.
 * 
 * Usage:
 *   node compute-apk-checksum.js <path-to-signed-apk>
 * 
 * After running, set the output as your environment variable:
 *   APK_SIGNATURE_CHECKSUM=<output-value>
 * 
 * Also copy the APK to: downloads/familyguard.apk
 */

const { execSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const apkPath = process.argv[2];

if (!apkPath) {
  console.log('');
  console.log('=== FamilyGuard APK Checksum Calculator ===');
  console.log('');
  console.log('Usage: node compute-apk-checksum.js <path-to-signed-apk>');
  console.log('');
  console.log('This computes the SHA-256 checksum needed for QR Device Owner provisioning.');
  console.log('');
  console.log('Steps to set up QR Provisioning:');
  console.log('  1. Build your signed release APK in Android Studio');
  console.log('  2. Run: node compute-apk-checksum.js path/to/app-release.apk');
  console.log('  3. Set the APK_SIGNATURE_CHECKSUM env var with the output value');
  console.log('  4. Copy the APK to: downloads/familyguard.apk');
  console.log('  5. Deploy and test QR provisioning');
  console.log('');
  process.exit(1);
}

if (!fs.existsSync(apkPath)) {
  console.error(`ERROR: File not found: ${apkPath}`);
  process.exit(1);
}

console.log('');
console.log('=== FamilyGuard APK Checksum Calculator ===');
console.log('');
console.log(`APK: ${apkPath}`);
console.log(`Size: ${(fs.statSync(apkPath).size / 1024 / 1024).toFixed(2)} MB`);
console.log('');

// Method 1: Compute SHA-256 of the entire APK file (fallback)
const apkBuffer = fs.readFileSync(apkPath);
const apkHash = crypto.createHash('sha256').update(apkBuffer).digest();
const apkChecksumBase64 = apkHash.toString('base64')
  .replace(/\+/g, '-')
  .replace(/\//g, '_')
  .replace(/=+$/, '');

console.log('--- APK File Checksum (PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM) ---');
console.log(`Value: ${apkChecksumBase64}`);
console.log('');

// Try Method 2: Use apksigner or keytool if available
let sigChecksum = null;
try {
  // Try apksigner first
  const apksignerOutput = execSync(`apksigner verify --print-certs "${apkPath}" 2>&1`, { encoding: 'utf8' });
  const sha256Match = apksignerOutput.match(/Signer #1 certificate SHA-256 digest:\s*([a-f0-9]+)/i);
  if (sha256Match) {
    const hexStr = sha256Match[1];
    const certBytes = Buffer.from(hexStr, 'hex');
    sigChecksum = certBytes.toString('base64')
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
    
    console.log('--- Signing Certificate Checksum (PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM) ---');
    console.log(`Value: ${sigChecksum}`);
    console.log('');
  }
} catch (e) {
  // apksigner not available, try keytool via jar extraction
  try {
    // For debug builds, try extracting cert from APK
    const AdmZip = require('adm-zip'); // may not be installed
    const zip = new AdmZip(apkPath);
    const certEntry = zip.getEntries().find(e => e.entryName.match(/META-INF\/.*\.(RSA|DSA|EC)/));
    if (certEntry) {
      const certData = certEntry.getData();
      const certHash = crypto.createHash('sha256').update(certData).digest();
      sigChecksum = certHash.toString('base64')
        .replace(/\+/g, '-')
        .replace(/\//g, '_')
        .replace(/=+$/, '');
      console.log('--- Signing Certificate Checksum (extracted from APK) ---');
      console.log(`Value: ${sigChecksum}`);
      console.log('');
    }
  } catch (e2) {
    // Neither method available
  }
}

console.log('============================================================');
console.log('');
console.log('SET THIS ENVIRONMENT VARIABLE on your server:');
console.log('');
if (sigChecksum) {
  console.log(`  APK_SIGNATURE_CHECKSUM=${sigChecksum}`);
  console.log('');
  console.log('  (This is the signing certificate checksum - works on Android 7+)');
} else {
  console.log(`  APK_SIGNATURE_CHECKSUM=${apkChecksumBase64}`);
  console.log('');
  console.log('  (This is the APK file checksum - used as fallback)');
  console.log('  NOTE: For best results, install Android SDK Build Tools and run again');
  console.log('        so apksigner can extract the signing certificate checksum.');
}
console.log('');
console.log('ALSO: Copy your APK to the downloads folder:');

const downloadDir = path.join(__dirname, 'downloads');
if (!fs.existsSync(downloadDir)) {
  fs.mkdirSync(downloadDir, { recursive: true });
  console.log(`  Created: ${downloadDir}`);
}

const targetPath = path.join(downloadDir, 'familyguard.apk');
console.log(`  copy "${apkPath}" "${targetPath}"`);
console.log('');

// Ask if they want to auto-copy
if (process.argv.includes('--copy')) {
  fs.copyFileSync(apkPath, targetPath);
  console.log(`  ✓ APK copied to ${targetPath}`);
  console.log('');
}

console.log('============================================================');
console.log('');

const mongoose = require('mongoose');

const MONGODB_URI = process.env.MONGODB_URI || 'mongodb://localhost:27017/familyguard';

async function test() {
  try {
    await mongoose.connect(MONGODB_URI);
    console.log('Connected to MongoDB');
    
    const { Photo, User, Device } = require('./models');
    
    // Check photo stats
    const stats = await Photo.aggregate([
      { $group: { _id: null, totalSize: { $sum: '$size' }, count: { $sum: 1 } } }
    ]);
    console.log('\n=== Photo Stats ===');
    console.log('Total Photos:', stats[0]?.count || 0);
    console.log('Total Size:', ((stats[0]?.totalSize || 0) / 1024 / 1024).toFixed(2), 'MB');
    
    // Check users and their quota
    const users = await User.find().select('email photoStorageUsed photoStorageLimit');
    console.log('\n=== Users ===');
    users.forEach(u => {
      const used = (u.photoStorageUsed || 0) / 1024 / 1024;
      const limit = (u.photoStorageLimit || 200 * 1024 * 1024) / 1024 / 1024;
      console.log(`${u.email}: ${used.toFixed(2)} MB / ${limit.toFixed(0)} MB`);
    });
    
    // Check devices
    const devices = await Device.find().select('name deviceId');
    console.log('\n=== Devices ===');
    devices.forEach(d => {
      console.log(`${d.name}: ${d.deviceId}`);
    });
    
    // Check sample photos
    const samplePhotos = await Photo.find().limit(3).select('fileName size filePath deviceId');
    console.log('\n=== Sample Photos ===');
    samplePhotos.forEach(p => {
      console.log(`${p.fileName}: ${(p.size / 1024).toFixed(1)} KB - ${p.filePath}`);
    });
    
    await mongoose.disconnect();
  } catch (error) {
    console.error('Error:', error);
    process.exit(1);
  }
}

test();

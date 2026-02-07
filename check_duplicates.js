const mongoose = require('mongoose');
require('dotenv').config();

// Use online connection (Azure/Atlas)
const MONGO_URI = 'mongodb+srv://faizansiddiqui4292:Allah%40%40011@cluster0.tirkwow.mongodb.net/familyguard?retryWrites=true&w=majority&appName=Cluster0';

mongoose.connect(MONGO_URI).then(async () => {
  console.log('Connected to MongoDB');
  
  const SocialContact = mongoose.model('SocialContact', new mongoose.Schema({}, { strict: false }));
  const SocialMessage = mongoose.model('SocialMessage', new mongoose.Schema({}, { strict: false }));
  
  // Get all contacts for device
  const contacts = await SocialContact.find({ device_id: '2e5751b3cf41e796' }).lean();
  
  console.log('=== All Contacts ===');
  contacts.forEach(c => {
    const name = c.contact_name || 'NO_NAME';
    const nameHex = Buffer.from(name).toString('hex');
    console.log(`[${c.app_package}] "${name}" (length: ${name.length}) | hex: ${nameHex.substring(0, 40)}`);
  });
  
  // Check for similar names (possible duplicates due to whitespace or invisible chars)
  console.log('\n=== Checking for Similar Names ===');
  const normalized = {};
  contacts.forEach(c => {
    const name = c.contact_name || 'NO_NAME';
    // Normalize: lowercase, remove extra spaces, trim
    const normalizedName = name.toLowerCase().replace(/\s+/g, ' ').trim();
    if (!normalized[normalizedName]) normalized[normalizedName] = [];
    normalized[normalizedName].push(c.contact_name);
  });
  
  Object.entries(normalized).filter(([k, v]) => v.length > 1).forEach(([k, v]) => {
    console.log(`POSSIBLE DUPLICATES for "${k}": ${JSON.stringify(v)}`);
  });
  
  // Get message count by contact_name
  console.log('\n=== Message Count Per Contact ===');
  const msgCounts = await SocialMessage.aggregate([
    { $match: { device_id: '2e5751b3cf41e796' } },
    { $group: { _id: '$contact_name', count: { $sum: 1 } } },
    { $sort: { count: -1 } },
    { $limit: 20 }
  ]);
  
  msgCounts.forEach(c => {
    console.log(`"${c._id}": ${c.count} messages`);
  });
  
  process.exit(0);
}).catch(e => { console.error(e); process.exit(1); });

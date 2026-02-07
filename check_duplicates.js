const mongoose = require('mongoose');
require('dotenv').config();

mongoose.connect(process.env.MONGODB_URI).then(async () => {
  console.log('Connected to MongoDB');
  
  const SocialContact = mongoose.model('SocialContact', new mongoose.Schema({}, { strict: false }));
  const SocialMessage = mongoose.model('SocialMessage', new mongoose.Schema({}, { strict: false }));
  
  const deviceId = '2e5751b3cf41e796';
  const contacts = await SocialContact.find({ device_id: deviceId }).lean();
  
  console.log(`Found ${contacts.length} contacts`);
  
  // Group contacts by normalized name
  const normalized = {};
  contacts.forEach(c => {
    let cleanName = c.contact_name || '';
    // Remove "(N messages)" pattern
    cleanName = cleanName.replace(/\s*\(\d+\s+messages?\)/gi, '').trim();
    // Remove "(N)" pattern
    cleanName = cleanName.replace(/\s*\(\d+\)/g, '').trim();
    // Remove ": Sender Name" suffix only if not an email/username
    if (cleanName.includes(':') && !cleanName.includes('@')) {
      cleanName = cleanName.split(':')[0].trim();
    }
    
    const key = `${c.device_id}|${c.app_package}|${cleanName}`;
    if (!normalized[key]) {
      normalized[key] = { cleanName, contacts: [] };
    }
    normalized[key].contacts.push(c);
  });
  
  let merged = 0;
  let deleted = 0;
  let updated = 0;
  
  for (const [key, data] of Object.entries(normalized)) {
    if (data.contacts.length > 1) {
      console.log(`Merging ${data.contacts.length} contacts into "${data.cleanName}"`);
      
      for (const dup of data.contacts) {
        if (dup.contact_name !== data.cleanName) {
          // Update all messages
          const upd = await SocialMessage.updateMany(
            { device_id: dup.device_id, app_package: dup.app_package, contact_name: dup.contact_name },
            { $set: { contact_name: data.cleanName } }
          );
          updated += upd.modifiedCount || 0;
          console.log(`  - Updated ${upd.modifiedCount} messages from "${dup.contact_name}"`);
          
          // Delete duplicate contact
          await SocialContact.deleteOne({ _id: dup._id });
          deleted++;
        }
      }
      merged++;
    }
  }
  
  console.log(`\n✅ Done! Merged: ${merged} groups, Deleted: ${deleted} duplicate contacts, Updated: ${updated} messages`);
  process.exit(0);
}).catch(e => { console.error('Error:', e.message); process.exit(1); });

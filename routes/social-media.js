/**
 * Social Media Routes - API endpoints for social media chat monitoring
 * Supports: WhatsApp, Instagram, Messenger, Telegram, Snapchat, Twitter
 */

const express = require('express');
const router = express.Router();
const { SocialMessage, SocialContact } = require('../models');

// App metadata for icons and colors
const APP_METADATA = {
  'com.whatsapp': { name: 'WhatsApp', icon: '💚', color: '#25D366' },
  'com.whatsapp.w4b': { name: 'WhatsApp Business', icon: '💚', color: '#25D366' },
  'com.instagram.android': { name: 'Instagram', icon: '📷', color: '#E1306C' },
  'com.facebook.orca': { name: 'Messenger', icon: '💙', color: '#0084FF' },
  'com.facebook.mlite': { name: 'Messenger Lite', icon: '💙', color: '#0084FF' },
  'org.telegram.messenger': { name: 'Telegram', icon: '✈️', color: '#0088CC' },
  'org.telegram.messenger.web': { name: 'Telegram', icon: '✈️', color: '#0088CC' },
  'com.snapchat.android': { name: 'Snapchat', icon: '👻', color: '#FFFC00' },
  'com.twitter.android': { name: 'Twitter/X', icon: '🐦', color: '#1DA1F2' },
  'com.zhiliaoapp.musically': { name: 'TikTok', icon: '🎵', color: '#FF0050' }
};

/**
 * POST /api/social-media/cleanup-duplicates
 * Remove duplicate messages from database
 * NOTE: This route MUST come before /:deviceId routes
 */
router.post('/cleanup-duplicates', async (req, res) => {
  try {
    const { deviceId } = req.body;
    
    // Find all messages grouped by content
    const pipeline = [
      ...(deviceId ? [{ $match: { device_id: deviceId } }] : []),
      {
        $group: {
          _id: {
            device_id: '$device_id',
            app_package: '$app_package',
            contact_name: '$contact_name',
            message_text: '$message_text',
            // Round timestamp to nearest second for grouping
            timestamp_second: { $subtract: ['$timestamp', { $mod: ['$timestamp', 1000] }] }
          },
          count: { $sum: 1 },
          docs: { $push: '$_id' },
          firstDoc: { $first: '$_id' }
        }
      },
      { $match: { count: { $gt: 1 } } }
    ];
    
    const duplicates = await SocialMessage.aggregate(pipeline);
    
    let totalDeleted = 0;
    
    for (const dup of duplicates) {
      // Keep the first document, delete the rest
      const idsToDelete = dup.docs.filter(id => !id.equals(dup.firstDoc));
      if (idsToDelete.length > 0) {
        const result = await SocialMessage.deleteMany({ _id: { $in: idsToDelete } });
        totalDeleted += result.deletedCount;
      }
    }
    
    console.log(`🧹 Cleaned up ${totalDeleted} duplicate messages`);
    
    res.json({
      success: true,
      duplicateGroupsFound: duplicates.length,
      messagesDeleted: totalDeleted
    });
    
  } catch (error) {
    console.error('Error cleaning duplicates:', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * POST /api/social-media/fix-contacts
 * Fix duplicate contacts by merging variations (e.g., "Group (5 messages): Sender" -> "Group")
 */
router.post('/fix-contacts', async (req, res) => {
  try {
    const { deviceId } = req.body;
    
    // Get all contacts for device
    const query = deviceId ? { device_id: deviceId } : {};
    const contacts = await SocialContact.find(query).lean();
    
    console.log(`📋 Found ${contacts.length} contacts to check`);
    
    // Group contacts by normalized name
    const normalized = {};
    contacts.forEach(c => {
      // Normalize: remove "(N messages)", "(N)", ": Sender" patterns
      let cleanName = c.contact_name || '';
      cleanName = cleanName.replace(/\s*\(\d+\s+messages?\)/gi, '').trim();
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
    
    let contactsMerged = 0;
    let contactsDeleted = 0;
    let messagesUpdated = 0;
    
    for (const [key, data] of Object.entries(normalized)) {
      if (data.contacts.length > 1) {
        console.log(`🔄 Merging ${data.contacts.length} contacts into "${data.cleanName}"`);
        
        // Get the main contact (highest message count or most recent)
        const mainContact = data.contacts.sort((a, b) => 
          (b.message_count || 0) - (a.message_count || 0)
        )[0];
        
        // Update all messages from duplicate contacts to use clean name
        for (const dupContact of data.contacts) {
          if (dupContact.contact_name !== data.cleanName) {
            // Update messages
            const updateResult = await SocialMessage.updateMany(
              {
                device_id: dupContact.device_id,
                app_package: dupContact.app_package,
                contact_name: dupContact.contact_name
              },
              { $set: { contact_name: data.cleanName } }
            );
            messagesUpdated += updateResult.modifiedCount;
            
            // Delete duplicate contact
            await SocialContact.deleteOne({ _id: dupContact._id });
            contactsDeleted++;
          }
        }
        
        // Ensure main contact has clean name
        if (mainContact.contact_name !== data.cleanName) {
          await SocialContact.findByIdAndUpdate(mainContact._id, {
            $set: { contact_name: data.cleanName }
          });
        }
        
        // Recalculate message count for main contact
        const msgCount = await SocialMessage.countDocuments({
          device_id: mainContact.device_id,
          app_package: mainContact.app_package,
          contact_name: data.cleanName
        });
        
        await SocialContact.findOneAndUpdate(
          {
            device_id: mainContact.device_id,
            app_package: mainContact.app_package,
            contact_name: data.cleanName
          },
          { $set: { message_count: msgCount } },
          { upsert: true }
        );
        
        contactsMerged++;
      }
    }
    
    console.log(`✅ Fixed contacts: ${contactsMerged} merged, ${contactsDeleted} deleted, ${messagesUpdated} messages updated`);
    
    res.json({
      success: true,
      contactsMerged,
      contactsDeleted,
      messagesUpdated
    });
    
  } catch (error) {
    console.error('Error fixing contacts:', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * POST /api/social-media/message
 * Upload a SENT message from accessibility service
 * NOTE: Must be before /:deviceId routes
 */
router.post('/message', async (req, res) => {
  try {
    const {
      deviceId,
      appPackage,
      appName,
      contactName,
      contactIdentifier,
      messageText,
      timestamp,
      messageType,
      isGroupChat,
      groupName,
      senderInGroup,
      mediaType
    } = req.body;
    
    if (!deviceId || !appPackage || !messageText) {
      return res.status(400).json({
        success: false,
        error: 'Missing required fields: deviceId, appPackage, messageText'
      });
    }
    
    const msgTimestamp = timestamp || Date.now();
    const msgContact = contactName || 'Unknown';
    
    // Check for duplicate (same content within 2 seconds)
    const timestampWindow = 2000;
    const existing = await SocialMessage.findOne({
      device_id: deviceId,
      app_package: appPackage,
      contact_name: msgContact,
      message_text: messageText,
      timestamp: { $gte: msgTimestamp - timestampWindow, $lte: msgTimestamp + timestampWindow }
    });
    
    if (existing) {
      console.log(`⏭️ Duplicate SENT message skipped: ${messageText.substring(0, 30)}...`);
      return res.json({ success: true, messageId: existing.message_id, duplicate: true });
    }
    
    const APP_META = {
      'com.whatsapp': { name: 'WhatsApp' },
      'com.whatsapp.w4b': { name: 'WhatsApp Business' },
      'com.instagram.android': { name: 'Instagram' },
      'com.facebook.orca': { name: 'Messenger' },
      'com.facebook.mlite': { name: 'Messenger Lite' },
      'org.telegram.messenger': { name: 'Telegram' },
      'com.snapchat.android': { name: 'Snapchat' },
      'com.twitter.android': { name: 'Twitter/X' },
      'com.zhiliaoapp.musically': { name: 'TikTok' }
    };
    
    // Create the message
    const message = new SocialMessage({
      message_id: `sent_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
      device_id: deviceId,
      app_package: appPackage,
      app_name: appName || APP_META[appPackage]?.name || 'Unknown',
      contact_name: msgContact,
      contact_identifier: contactIdentifier || msgContact,
      message_text: messageText,
      timestamp: msgTimestamp,
      message_type: messageType || 'SENT',
      is_group_chat: isGroupChat || false,
      group_name: groupName,
      sender_in_group: senderInGroup,
      media_type: mediaType
    });
    
    await message.save();
    
    // Update or create contact
    await SocialContact.findOneAndUpdate(
      {
        device_id: deviceId,
        app_package: appPackage,
        contact_name: msgContact
      },
      {
        $set: {
          contact_identifier: contactIdentifier || msgContact,
          last_message_text: messageText,
          last_message_time: msgTimestamp,
          last_message_type: messageType || 'SENT'
        },
        $inc: { message_count: 1 }
      },
      { upsert: true, new: true }
    );
    
    console.log(`📤 SENT message saved: ${appPackage} -> ${msgContact}: "${messageText.substring(0, 30)}..."`);
    
    res.json({
      success: true,
      messageId: message.message_id
    });
    
  } catch (error) {
    console.error('Error saving SENT message:', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * GET /api/social-media/:deviceId/apps
 * Get all social media apps with message stats
 */
router.get('/:deviceId/apps', async (req, res) => {
  try {
    const { deviceId } = req.params;
    
    const appStats = await SocialMessage.aggregate([
      { $match: { device_id: deviceId } },
      {
        $group: {
          _id: '$app_package',
          app_name: { $first: '$app_name' },
          message_count: { $sum: 1 },
          last_message_time: { $max: '$timestamp' },
          last_message_text: { $last: '$message_text' },
          contacts: { $addToSet: '$contact_name' }
        }
      },
      {
        $project: {
          app_package: '$_id',
          app_name: 1,
          message_count: 1,
          last_message_time: 1,
          last_message_text: 1,
          contact_count: { $size: '$contacts' }
        }
      },
      { $sort: { last_message_time: -1 } }
    ]);
    
    // Add metadata
    const appsWithMetadata = appStats.map(app => ({
      ...app,
      icon: APP_METADATA[app.app_package]?.icon || '💬',
      color: APP_METADATA[app.app_package]?.color || '#667eea'
    }));
    
    res.json({
      success: true,
      apps: appsWithMetadata
    });
    
  } catch (error) {
    console.error('Error fetching social media apps:', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * GET /api/social-media/:deviceId/:appPackage/contacts
 * Get all contacts for a specific app
 */
router.get('/:deviceId/:appPackage/contacts', async (req, res) => {
  try {
    const { deviceId, appPackage } = req.params;
    
    // Get contacts from SocialContact collection
    let contacts = await SocialContact.find({
      device_id: deviceId,
      app_package: appPackage
    }).sort({ last_message_time: -1 });
    
    // If no contacts in SocialContact, aggregate from messages
    if (contacts.length === 0) {
      const contactsFromMessages = await SocialMessage.aggregate([
        { $match: { device_id: deviceId, app_package: appPackage } },
        { $sort: { timestamp: -1 } },
        {
          $group: {
            _id: '$contact_name',
            contact_identifier: { $first: '$contact_identifier' },
            last_message_time: { $first: '$timestamp' },
            last_message_text: { $first: '$message_text' },
            last_message_type: { $first: '$message_type' },
            message_count: { $sum: 1 },
            profile_photo: { $first: '$profile_photo' },
            is_group_chat: { $first: '$is_group_chat' }
          }
        },
        {
          $project: {
            contact_name: '$_id',
            contact_identifier: 1,
            last_message_time: 1,
            last_message_text: 1,
            last_message_type: 1,
            message_count: 1,
            profile_photo: 1,
            is_group_chat: 1
          }
        },
        { $sort: { last_message_time: -1 } }
      ]);
      
      contacts = contactsFromMessages;
    }
    
    res.json({
      success: true,
      contacts: contacts
    });
    
  } catch (error) {
    console.error('Error fetching contacts:', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * GET /api/social-media/:deviceId/:appPackage/contacts/:contactName/messages
 * Get messages for a specific contact
 */
router.get('/:deviceId/:appPackage/contacts/:contactName/messages', async (req, res) => {
  try {
    const { deviceId, appPackage, contactName } = req.params;
    const { limit = 100, before } = req.query;
    
    const query = {
      device_id: deviceId,
      app_package: appPackage,
      contact_name: decodeURIComponent(contactName)
    };
    
    if (before) {
      query.timestamp = { $lt: parseInt(before) };
    }
    
    const messages = await SocialMessage.find(query)
      .sort({ timestamp: 1 })
      .limit(parseInt(limit));
    
    // Group messages by date
    const messagesByDate = {};
    messages.forEach(msg => {
      const date = new Date(msg.timestamp).toDateString();
      if (!messagesByDate[date]) {
        messagesByDate[date] = [];
      }
      messagesByDate[date].push(msg);
    });
    
    res.json({
      success: true,
      messages: messages,
      messagesByDate: messagesByDate,
      count: messages.length,
      hasMore: messages.length >= parseInt(limit)
    });
    
  } catch (error) {
    console.error('Error fetching messages:', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * POST /api/social-media/:deviceId/message
 * Save new message from Android device
 */
router.post('/:deviceId/message', async (req, res) => {
  try {
    const { deviceId } = req.params;
    const messageData = req.body;
    
    // Check for duplicate using message_id
    const existing = await SocialMessage.findOne({ message_id: messageData.message_id });
    if (existing) {
      return res.json({ success: true, duplicate: true });
    }
    
    // Save message
    const message = new SocialMessage({
      ...messageData,
      device_id: deviceId
    });
    await message.save();
    
    // Update or create contact
    await SocialContact.findOneAndUpdate(
      {
        device_id: deviceId,
        app_package: messageData.app_package,
        contact_name: messageData.contact_name
      },
      {
        $set: {
          contact_identifier: messageData.contact_identifier,
          last_message_time: messageData.timestamp,
          last_message_text: messageData.message_text,
          last_message_type: messageData.message_type,
          updated_at: new Date()
        },
        $inc: { message_count: 1 },
        $setOnInsert: {
          profile_photo: messageData.profile_photo
        }
      },
      { upsert: true, new: true }
    );
    
    // Update profile photo if provided and newer
    if (messageData.profile_photo) {
      await SocialContact.findOneAndUpdate(
        {
          device_id: deviceId,
          app_package: messageData.app_package,
          contact_name: messageData.contact_name,
          profile_photo: { $exists: false }
        },
        { $set: { profile_photo: messageData.profile_photo } }
      );
    }
    
    console.log(`💬 Social message saved: ${messageData.app_name} - ${messageData.contact_name}`);
    
    res.json({ success: true, saved: true });
    
  } catch (error) {
    // Handle duplicate key error
    if (error.code === 11000) {
      return res.json({ success: true, duplicate: true });
    }
    console.error('Error saving social message:', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * DELETE /api/social-media/:deviceId/:appPackage/contacts/:contactName
 * Delete all messages for a contact
 */
router.delete('/:deviceId/:appPackage/contacts/:contactName', async (req, res) => {
  try {
    const { deviceId, appPackage, contactName } = req.params;
    
    const result = await SocialMessage.deleteMany({
      device_id: deviceId,
      app_package: appPackage,
      contact_name: decodeURIComponent(contactName)
    });
    
    // Also delete contact record
    await SocialContact.deleteOne({
      device_id: deviceId,
      app_package: appPackage,
      contact_name: decodeURIComponent(contactName)
    });
    
    res.json({
      success: true,
      deleted: result.deletedCount
    });
    
  } catch (error) {
    console.error('Error deleting messages:', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * DELETE /api/social-media/:deviceId/:appPackage
 * Delete all messages for an app
 */
router.delete('/:deviceId/:appPackage', async (req, res) => {
  try {
    const { deviceId, appPackage } = req.params;
    
    const result = await SocialMessage.deleteMany({
      device_id: deviceId,
      app_package: appPackage
    });
    
    // Also delete all contacts for this app
    await SocialContact.deleteMany({
      device_id: deviceId,
      app_package: appPackage
    });
    
    res.json({
      success: true,
      deleted: result.deletedCount
    });
    
  } catch (error) {
    console.error('Error deleting app messages:', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * GET /api/social-media/:deviceId/stats
 * Get overall social media stats
 */
router.get('/:deviceId/stats', async (req, res) => {
  try {
    const { deviceId } = req.params;
    
    const stats = await SocialMessage.aggregate([
      { $match: { device_id: deviceId } },
      {
        $group: {
          _id: null,
          total_messages: { $sum: 1 },
          apps: { $addToSet: '$app_package' },
          contacts: { $addToSet: '$contact_name' },
          sent_count: {
            $sum: { $cond: [{ $eq: ['$message_type', 'SENT'] }, 1, 0] }
          },
          received_count: {
            $sum: { $cond: [{ $eq: ['$message_type', 'RECEIVED'] }, 1, 0] }
          },
          last_activity: { $max: '$timestamp' }
        }
      },
      {
        $project: {
          total_messages: 1,
          app_count: { $size: '$apps' },
          contact_count: { $size: '$contacts' },
          sent_count: 1,
          received_count: 1,
          last_activity: 1
        }
      }
    ]);
    
    res.json({
      success: true,
      stats: stats[0] || {
        total_messages: 0,
        app_count: 0,
        contact_count: 0,
        sent_count: 0,
        received_count: 0,
        last_activity: null
      }
    });
    
  } catch (error) {
    console.error('Error fetching stats:', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

/**
 * GET /api/social-media/:deviceId/recent
 * Get recent messages across all apps
 */
router.get('/:deviceId/recent', async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { limit = 50 } = req.query;
    
    const messages = await SocialMessage.find({ device_id: deviceId })
      .sort({ timestamp: -1 })
      .limit(parseInt(limit));
    
    res.json({
      success: true,
      messages: messages
    });
    
  } catch (error) {
    console.error('Error fetching recent messages:', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

module.exports = router;

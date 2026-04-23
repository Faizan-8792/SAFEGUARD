require('dotenv').config();
const dns = require('dns');
const mongoose = require('mongoose');
const { SocialMessage, Notification } = require('../models');

dns.setServers(['8.8.8.8', '8.8.4.4']);

async function cleanupSocialDuplicates() {
  const socialDup = await SocialMessage.aggregate([
    {
      $group: {
        _id: {
          device_id: '$device_id',
          app_package: '$app_package',
          message_text: { $ifNull: ['$message_text', ''] }
        },
        docs: { $push: { _id: '$_id', timestamp: '$timestamp' } },
        count: { $sum: 1 }
      }
    },
    { $match: { count: { $gt: 1 } } }
  ]).allowDiskUse(true);

  let deletedCount = 0;
  for (const group of socialDup) {
    const orderedDocs = (group.docs || []).sort((a, b) => Number(a.timestamp || 0) - Number(b.timestamp || 0));
    const keptId = orderedDocs[0]?._id;
    const idsToDelete = orderedDocs
      .slice(1)
      .map(doc => doc._id)
      .filter(Boolean);

    if (idsToDelete.length > 0) {
      const result = await SocialMessage.deleteMany({ _id: { $in: idsToDelete } });
      deletedCount += result.deletedCount || 0;
    }

    if (keptId) {
      await SocialMessage.updateOne({ _id: keptId }, { $set: { timestamp: Number(orderedDocs[0]?.timestamp || 0) } });
    }
  }

  return { groups: socialDup.length, deleted: deletedCount };
}

async function cleanupNotificationDuplicates() {
  const notifDup = await Notification.aggregate([
    {
      $group: {
        _id: {
          deviceId: '$deviceId',
          packageName: '$packageName',
          message: { $ifNull: ['$content', { $ifNull: ['$title', ''] }] }
        },
        docs: { $push: { _id: '$_id', timestamp: '$timestamp' } },
        count: { $sum: 1 }
      }
    },
    { $match: { count: { $gt: 1 } } }
  ]).allowDiskUse(true);

  let deletedCount = 0;
  for (const group of notifDup) {
    const orderedDocs = (group.docs || []).sort((a, b) => new Date(a.timestamp || 0).getTime() - new Date(b.timestamp || 0).getTime());
    const keptId = orderedDocs[0]?._id;
    const idsToDelete = orderedDocs
      .slice(1)
      .map(doc => doc._id)
      .filter(Boolean);

    if (idsToDelete.length > 0) {
      const result = await Notification.deleteMany({ _id: { $in: idsToDelete } });
      deletedCount += result.deletedCount || 0;
    }

    if (keptId) {
      const earliestTs = orderedDocs[0]?.timestamp ? new Date(orderedDocs[0].timestamp) : new Date();
      await Notification.updateOne({ _id: keptId }, { $set: { timestamp: earliestTs } });
    }
  }

  return { groups: notifDup.length, deleted: deletedCount };
}

async function main() {
  const mongoUri = process.env.MONGODB_URI || 'mongodb://localhost:27017/familyguard';
  await mongoose.connect(mongoUri);

  const social = await cleanupSocialDuplicates();
  const notifications = await cleanupNotificationDuplicates();

  console.log(JSON.stringify({
    socialGroups: social.groups,
    socialDeleted: social.deleted,
    notificationGroups: notifications.groups,
    notificationDeleted: notifications.deleted
  }));

  await mongoose.disconnect();
}

main().catch(async (error) => {
  console.error(error);
  try {
    await mongoose.disconnect();
  } catch (_) {}
  process.exit(1);
});

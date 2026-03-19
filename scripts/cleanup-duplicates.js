require('dotenv').config();
const mongoose = require('mongoose');
const { SocialMessage, Notification } = require('../models');

async function cleanupSocialDuplicates() {
  const socialDup = await SocialMessage.aggregate([
    {
      $group: {
        _id: {
          device_id: '$device_id',
          app_package: '$app_package',
          contact_name: '$contact_name',
          message_text: '$message_text',
          timestamp: '$timestamp'
        },
        docs: { $push: '$_id' },
        count: { $sum: 1 },
        firstDoc: { $first: '$_id' }
      }
    },
    { $match: { count: { $gt: 1 } } }
  ]).allowDiskUse(true);

  let deletedCount = 0;
  for (const group of socialDup) {
    const idsToDelete = group.docs.filter(id => !id.equals(group.firstDoc));
    if (idsToDelete.length > 0) {
      const result = await SocialMessage.deleteMany({ _id: { $in: idsToDelete } });
      deletedCount += result.deletedCount || 0;
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
          timestamp: '$timestamp',
          message: { $ifNull: ['$content', '$title'] }
        },
        docs: { $push: '$_id' },
        count: { $sum: 1 },
        firstDoc: { $first: '$_id' }
      }
    },
    { $match: { count: { $gt: 1 } } }
  ]).allowDiskUse(true);

  let deletedCount = 0;
  for (const group of notifDup) {
    const idsToDelete = group.docs.filter(id => !id.equals(group.firstDoc));
    if (idsToDelete.length > 0) {
      const result = await Notification.deleteMany({ _id: { $in: idsToDelete } });
      deletedCount += result.deletedCount || 0;
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

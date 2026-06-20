import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";

initializeApp();

// On each per-recipient inbox write, push a silent FCM data message to that
// recipient if they have a token and notify is not disabled (default ON).
export const notifyReader = onDocumentCreated(
  "inbox/{deviceId}/messages/{messageId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const data = snap.data();
    const deviceId = event.params.deviceId as string;
    const db = getFirestore();

    const deviceSnap = await db.collection("devices").doc(deviceId).get();
    const token = deviceSnap.get("fcmToken") as string | undefined;
    if (!token) return;

    const subId = `${deviceId}__${data.sourceDeviceId}`;
    const subSnap = await db.collection("subscriptions").doc(subId).get();
    const notify = subSnap.exists ? subSnap.get("notify") !== false : true;
    if (!notify) return;

    await getMessaging().send({
      token,
      data: {
        type: "new_sms",
        message_id: String(data.messageId),
        source_device_id: String(data.sourceDeviceId),
        device_id: deviceId,
      },
      android: { priority: "high" },
    });
  }
);

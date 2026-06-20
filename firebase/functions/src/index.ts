import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";

initializeApp();

const db = getFirestore();
const messaging = getMessaging();

// On each per-recipient inbox write, push a silent FCM data message to that
// recipient if they have a token and notify is not disabled (default ON).
export const notifyReader = onDocumentCreated(
  "inbox/{deviceId}/messages/{messageId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const data = snap.data();
    if (!data.sourceDeviceId) return;
    const deviceId = event.params.deviceId as string;

    const deviceSnap = await db.collection("devices").doc(deviceId).get();
    const token = deviceSnap.get("fcmToken");
    if (!token || typeof token !== "string") return;

    const subId = `${deviceId}__${data.sourceDeviceId}`;
    const subSnap = await db.collection("subscriptions").doc(subId).get();
    const notify = subSnap.exists ? subSnap.get("notify") !== false : true;
    if (!notify) return;

    try {
      await messaging.send({
        token,
        data: {
          type: "new_sms",
          message_id: String(data.messageId),
          source_device_id: String(data.sourceDeviceId),
          device_id: deviceId,
        },
        android: { priority: "high" },
      });
    } catch (e: any) {
      if (e?.errorInfo?.code === "messaging/registration-token-not-registered") {
        await db.collection("devices").doc(deviceId).update({ fcmToken: FieldValue.delete() });
      }
      console.error("FCM send failed", e);
    }
  }
);

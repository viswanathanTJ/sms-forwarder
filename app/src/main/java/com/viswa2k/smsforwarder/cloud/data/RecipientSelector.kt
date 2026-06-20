package com.viswa2k.smsforwarder.cloud.data

object RecipientSelector {
    fun recipientDeviceIds(
        sourceDeviceId: String,
        matrix: List<AccessGrant>,
        adminDeviceIds: Set<String>,
    ): Set<String> =
        // readers granted access to this source + the super-admin (always) + the source device
        // itself (so a device's own forwarded SMS appear in its own cloud view).
        matrix.filter { it.sourceDeviceId == sourceDeviceId }.map { it.readerDeviceId }.toSet() +
            adminDeviceIds + sourceDeviceId
}

package com.viswa2k.smsforwarder.cloud.data

object RecipientSelector {
    fun recipientDeviceIds(
        sourceDeviceId: String,
        matrix: List<AccessGrant>,
        adminDeviceIds: Set<String>,
    ): Set<String> =
        matrix.filter { it.sourceDeviceId == sourceDeviceId }.map { it.readerDeviceId }.toSet() + adminDeviceIds
}

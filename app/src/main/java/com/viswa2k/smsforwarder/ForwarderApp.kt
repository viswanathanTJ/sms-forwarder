package com.viswa2k.smsforwarder

import android.app.Application

class ForwarderApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

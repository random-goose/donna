package com.example.cactuspoc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
            android.util.Log.d("Donna", "Call started")
        }
        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            android.util.Log.d("Donna", "Call ended")
        }
    }
}

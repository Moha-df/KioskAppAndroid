package com.example.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Optionnel : Actions à effectuer quand on sort de la plage horaire
        // Par exemple, envoyer un broadcast à l'activité principale
        // Mais actuellement pas utilisé
        context?.let {
            val sleepIntent = Intent("com.example.kiosk.SLEEP_TIME")
            it.sendBroadcast(sleepIntent)
        }
    }
}
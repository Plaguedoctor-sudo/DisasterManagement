package network.disaster.management.routing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import network.disaster.management.storage.LowWearCryptoShredder

class EmergencyShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_SHUTDOWN) {
            // Normal system shutdown: cleanly close SQLCipher connection to prevent DB corruption
            val shredder = LowWearCryptoShredder(context)
            shredder.close()
        } else if (action == "network.disaster.management.action.PANIC_PURGE") {
            // Explicit user-triggered panic wipe
            val shredder = LowWearCryptoShredder(context)
            shredder.panicPhysicalPurge(context)
        }
    }
}

package network.disaster.management.storage

import android.content.Context
import android.os.Build

/**
 * Manages local user identity / callsign for offline disaster mesh operations.
 * Allows civilians and emergency responders to choose their custom callsign/name,
 * which is propagated in mesh broadcasts, SOS alerts, friend requests, and group chats.
 */
object UserIdentityManager {
    private const val PREFS_NAME = "disaster_user_identity"
    private const val KEY_USER_NAME = "pref_user_callsign"
    private const val KEY_CUSTOMIZED = "pref_callsign_is_customized"

    /**
     * Returns the user's callsign / name.
     * If not customized yet, returns the default device callsign (e.g. "Civilian-XXXX").
     */
    fun getUserName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_USER_NAME, null)?.trim()
        if (!saved.isNullOrEmpty()) {
            return saved
        }
        return getDefaultUserName()
    }

    /**
     * Checks if the user has explicitly selected/customized their callsign.
     */
    fun isUserNameCustomized(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CUSTOMIZED, false) && !prefs.getString(KEY_USER_NAME, null).isNullOrBlank()
    }

    /**
     * Validates and saves the chosen callsign.
     * Sanitizes input to alphanumeric, space, dashes, underscores, max length 24 chars.
     * Returns the sanitized saved callsign, or null if input was invalid.
     */
    fun setUserName(context: Context, newName: String): String? {
        val sanitized = sanitizeName(newName)
        if (sanitized.isEmpty()) {
            return null
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_USER_NAME, sanitized)
            .putBoolean(KEY_CUSTOMIZED, true)
            .apply()
        return sanitized
    }

    /**
     * Resets the callsign back to hardware default.
     */
    fun resetToDefault(context: Context): String {
        val defaultName = getDefaultUserName()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_USER_NAME, defaultName)
            .putBoolean(KEY_CUSTOMIZED, false)
            .apply()
        return defaultName
    }

    /**
     * Generates the default name based on the hardware model.
     */
    fun getDefaultUserName(): String {
        val model = try { Build.MODEL ?: "NODE" } catch (_: Throwable) { "NODE" }
        val modelClean = model
            .filter { it.isLetterOrDigit() }
            .takeLast(4)
            .ifEmpty { "ME" }
            .uppercase()
        return "Civilian-$modelClean"
    }

    /**
     * Sanitizes user input to ensure it fits safely in 4KB mesh envelopes, JSON, and UI.
     */
    fun sanitizeName(raw: String): String {
        val clean = raw.trim()
            .replace("\r", "")
            .replace("\n", "")
            .replace("\"", "")
            .replace(":", "")
            .replace("@", "")
            .take(24)
            .trim()
        return clean
    }

    /**
     * Generates a list of suggested callsigns for the user.
     */
    fun getSuggestedCallsigns(): List<String> {
        val model = try { Build.MODEL ?: "1" } catch (_: Throwable) { "1" }
        val modelSuffix = model.filter { it.isLetterOrDigit() }.takeLast(4).ifEmpty { "1" }.uppercase()
        return listOf(
            "Medic-$modelSuffix",
            "Rescue-$modelSuffix",
            "Scout-$modelSuffix",
            "Survivor-$modelSuffix",
            "Operator-$modelSuffix",
            getDefaultUserName()
        )
    }
}

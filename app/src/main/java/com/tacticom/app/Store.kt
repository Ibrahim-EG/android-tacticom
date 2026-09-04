package com.tacticom.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Profile(val id: String, val name: String, val ringtone: String?)

object Store {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences("tacticom", Context.MODE_PRIVATE)
        }
    }

    val myId: String
        get() {
            var v = prefs.getString("id", null)
            if (v == null) {
                v = UUID.randomUUID().toString().take(8)
                prefs.edit().putString("id", v).apply()
            }
            return v
        }

    var themeDark: Boolean
        get() = prefs.getBoolean("dark", true)
        set(v) { prefs.edit().putBoolean("dark", v).apply() }

    var manualIps: List<String>
        get() {
            val arr = JSONArray(prefs.getString("ips", "[]"))
            return (0 until arr.length()).map { arr.getString(it) }
        }
        set(v) { prefs.edit().putString("ips", JSONArray(v).toString()).apply() }

    fun profiles(): List<Profile> {
        val arr = JSONArray(prefs.getString("profiles", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Profile(o.getString("id"), o.getString("name"), o.optString("ringtone", "").ifEmpty { null })
        }
    }

    fun saveProfiles(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("ringtone", it.ringtone ?: ""))
        }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    var activeProfileId: String
        get() = prefs.getString("active", "") ?: ""
        set(v) { prefs.edit().putString("active", v).apply() }

    fun activeProfile(): Profile {
        val list = profiles().toMutableList()
        if (list.isEmpty()) {
            val p = Profile(myId, "OP-" + (1000..9999).random(), null)
            list.add(p)
            saveProfiles(list)
            activeProfileId = p.id
            return p
        }
        return list.firstOrNull { it.id == activeProfileId } ?: list[0]
    }

    fun activeName(): String = activeProfile().name
}

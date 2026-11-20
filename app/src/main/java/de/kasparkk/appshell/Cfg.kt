package de.kasparkk.appshell

import android.content.Context
import android.graphics.Color
import android.net.Uri
import org.json.JSONObject

/** assets/config.json, geschrieben von tools/prepare.py aus apps/<app>.json */
class Cfg(
    val name: String,
    val url: String,
    private val hosts: List<String>,
    val theme: Int,
    val bg: Int,
) {
    fun isInternal(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return hosts.any { p ->
            if (p.startsWith("*.")) h == p.substring(2) || h.endsWith(p.substring(1)) else h == p
        }
    }

    companion object {
        fun load(ctx: Context): Cfg {
            val j = JSONObject(ctx.assets.open("config.json").bufferedReader().use { it.readText() })
            val url = j.getString("url")
            val hosts = ArrayList<String>()
            Uri.parse(url).host?.let { hosts.add(it.lowercase()) }
            j.optJSONArray("internalHosts")?.let { a -> for (i in 0 until a.length()) hosts.add(a.getString(i).lowercase()) }
            fun color(k: String, d: String) = try { Color.parseColor(j.optString(k, d)) } catch (_: Exception) { Color.parseColor(d) }
            return Cfg(j.optString("name", "App"), url, hosts, color("themeColor", "#2f5e3e"), color("backgroundColor", "#ffffff"))
        }
    }
}

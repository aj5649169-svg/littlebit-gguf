package com.littlebit.chat.data

import android.content.Context
import org.json.JSONObject

data class ModelEntry(
    val id: String,
    val name: String,
    val tier: String,
    val tierLabel: String,
    val ramNote: String,
    val quant: String,
    val sizeGB: Double,
    val url: String,
    val tags: List<String>,
    val ramWarning: String?,
    val experimental: Boolean,
    val recommended: Boolean,
)

/** Catalog parsed from assets/models.json (edit the JSON to change the hub). */
object ModelsCatalog {

    /** Display order for the Model Hub tiers. */
    val TIER_ORDER = listOf("ultra-light", "balanced", "high", "heavyweight", "littlebit")

    fun load(context: Context): List<ModelEntry> {
        val text = context.assets.open("models.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("models")
        val out = ArrayList<ModelEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val tags = ArrayList<String>()
            val tarr = o.optJSONArray("tags")
            if (tarr != null) for (j in 0 until tarr.length()) tags.add(tarr.getString(j))
            out.add(
                ModelEntry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    tier = o.getString("tier"),
                    tierLabel = o.getString("tierLabel"),
                    ramNote = o.getString("ramNote"),
                    quant = o.getString("quant"),
                    sizeGB = o.optDouble("sizeGB", 0.0),
                    url = o.getString("url"),
                    tags = tags,
                    ramWarning = if (o.has("ramWarning")) o.getString("ramWarning") else null,
                    experimental = o.optBoolean("experimental", false),
                    recommended = o.optBoolean("recommended", false),
                ),
            )
        }
        return out
    }
}

package com.readcam.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Note(
    val id: Long,
    var title: String,
    var body: String,
    var updated: Long,
) {
    /** Title to show in the list: the typed title, or the first line of the script. */
    fun displayTitle(): String {
        if (title.isNotBlank()) return title.trim()
        val firstLine = body.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        return firstLine.ifEmpty { "Untitled" }
    }
}

/** Simple on-device storage for scripts (kept in the app's private preferences). */
object NotesStore {
    private const val PREFS = "notes"
    private const val KEY = "list"

    fun all(context: Context): MutableList<Note> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableListOf()
        val result = mutableListOf<Note>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    Note(
                        id = o.getLong("id"),
                        title = o.optString("title", ""),
                        body = o.optString("body", ""),
                        updated = o.optLong("updated", 0L),
                    )
                )
            }
        } catch (_: Exception) {
            // Corrupt data: start fresh rather than crash.
        }
        result.sortByDescending { it.updated }
        return result
    }

    fun get(context: Context, id: Long): Note? = all(context).firstOrNull { it.id == id }

    fun save(context: Context, note: Note) {
        val list = all(context)
        note.updated = System.currentTimeMillis()
        val index = list.indexOfFirst { it.id == note.id }
        if (index >= 0) list[index] = note else list.add(note)
        write(context, list)
    }

    fun delete(context: Context, id: Long) {
        write(context, all(context).filterNot { it.id == id })
    }

    fun newNote(title: String = "", body: String = ""): Note =
        Note(id = System.currentTimeMillis(), title = title, body = body, updated = System.currentTimeMillis())

    private fun write(context: Context, list: List<Note>) {
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(
                JSONObject()
                    .put("id", n.id)
                    .put("title", n.title)
                    .put("body", n.body)
                    .put("updated", n.updated)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }
}

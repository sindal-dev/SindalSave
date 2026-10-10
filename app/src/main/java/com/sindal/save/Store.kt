package com.sindal.save

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 자동 정리 규칙. kind: name(이름에 포함), ext(확장자), age(N일 지난 종류) */
data class Rule(val kind: String, val value: String, val days: Int, val target: String, val on: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("k", kind).put("v", value).put("d", days).put("t", target).put("on", on)

    fun describe(): String {
        val to = File(target).name
        return when (kind) {
            "name" -> "이름에 '$value' → $to"
            "ext" -> "${value.uppercase()} 파일 → $to"
            else -> "${Kind.fromKey(value)?.label ?: "모든 파일"} ${days}일 지나면 → $to"
        }
    }

    companion object {
        fun fromJson(o: JSONObject) = Rule(o.optString("k"), o.optString("v"), o.optInt("d", 7), o.optString("t"), o.optBoolean("on", true))
    }
}

class Store private constructor(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("save", Context.MODE_PRIVATE)

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }

        val EXT: File get() = Environment.getExternalStorageDirectory()

        /** 폴더 색 고르기 목록 */
        val FOLDER_COLORS = listOf(0xFFFF6B6B, 0xFFFFC043, 0xFFA78BFA, 0xFF64D2FF, 0xFF30D158, 0xFFFF8FC7, 0xFFFF9F0A, 0xFF4C6EF5, 0xFF63E6BE, 0xFFF5F5F7).map { it.toInt() }
    }

    // 디자인 도우미(Th.load)가 쓰는 값. 정리함은 런처와 같은 모노 테마로 고정
    val accentKey = "mono"
    val customAccent = 0xFFF5F5F7.toInt()
    val cardAlpha = 100

    /** 내 저장소 맨 위 폴더 */
    var rootPath: String
        get() = sp.getString("root", null) ?: File(EXT, "정리함").absolutePath
        set(v) = sp.edit().putString("root", v).apply()
    val root: File get() = File(rootPath)

    var view: String   // grid, list
        get() = sp.getString("view", "grid") ?: "grid"
        set(v) = sp.edit().putString("view", v).apply()
    var sort: String   // date, name, size
        get() = sp.getString("sort", "date") ?: "date"
        set(v) = sp.edit().putString("sort", v).apply()
    var group: String  // kind, source, none
        get() = sp.getString("group", "kind") ?: "kind"
        set(v) = sp.edit().putString("group", v).apply()

    private fun list(key: String): List<String> = try {
        val a = JSONArray(sp.getString(key, "[]")); (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }
    private fun putList(key: String, v: List<String>) = sp.edit().putString(key, JSONArray(v).toString()).apply()

    /** 받은 파일을 모을 폴더 (빈 목록이면 기본값) */
    var sources: List<String>
        get() = list("sources").ifEmpty { defaultSources() }
        set(v) = putList("sources", v)

    private fun defaultSources(): List<String> = listOf(
        "Download", "Download/KakaoTalk", "Documents/KakaoTalk", "Pictures/Screenshots", "DCIM/Screenshots",
    ).map { File(EXT, it).absolutePath }

    /** 최근 옮긴 곳 (앞이 최신) */
    var recentTargets: List<String>
        get() = list("recent")
        set(v) = putList("recent", v.distinct().take(6))

    fun pushRecent(path: String) { recentTargets = listOf(path) + recentTargets }

    /** 폴더 색 (경로 → ARGB) */
    fun folderColor(path: String, index: Int): Int {
        val o = try { JSONObject(sp.getString("fcolors", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        return if (o.has(path)) o.getInt(path) else FOLDER_COLORS[index % FOLDER_COLORS.size]
    }

    fun setFolderColor(path: String, color: Int) {
        val o = try { JSONObject(sp.getString("fcolors", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        o.put(path, color)
        sp.edit().putString("fcolors", o.toString()).apply()
    }

    fun renameFolderKeys(old: String, new: String) {
        val o = try { JSONObject(sp.getString("fcolors", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        if (o.has(old)) { o.put(new, o.getInt(old)); o.remove(old); sp.edit().putString("fcolors", o.toString()).apply() }
        recentTargets = recentTargets.map { if (it == old) new else it }
        rules = rules.map { if (it.target == old) it.copy(target = new) else it }
    }

    var rules: List<Rule>
        get() = try {
            val a = JSONArray(sp.getString("rules", "[]")); (0 until a.length()).map { Rule.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
        set(v) = sp.edit().putString("rules", JSONArray(v.map { it.toJson() }).toString()).apply()

    /** 옮긴 시각 기록 (이번 주 정리율 계산용, 최근 500개) */
    fun logMoved(n: Int) {
        val now = System.currentTimeMillis()
        val l = (List(n) { now.toString() } + list("moved")).take(500)
        putList("moved", l)
    }

    fun movedSince(t: Long): Int = list("moved").count { (it.toLongOrNull() ?: 0L) >= t }
}

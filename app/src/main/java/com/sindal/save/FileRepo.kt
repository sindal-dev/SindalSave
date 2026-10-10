package com.sindal.save

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** 파일 종류. 받은 파일함의 개수 카드와 묶음에 쓰임 */
enum class Kind(val key: String, val label: String, val color: Int, val exts: Set<String>) {
    PDF("pdf", "PDF", 0xFFFF6B6B.toInt(), setOf("pdf")),
    IMAGE("image", "이미지", 0xFF64D2FF.toInt(), setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")),
    ZIP("zip", "압축", 0xFFFFC043.toInt(), setOf("zip", "rar", "7z", "alz", "egg", "tar", "gz")),
    HTML("html", "HTML", 0xFF63E6BE.toInt(), setOf("html", "htm", "xhtml", "mhtml", "mht")),
    DOC("doc", "문서", 0xFF7AA2FF.toInt(), setOf("hwp", "hwpx", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt", "md", "csv", "rtf", "odt", "key", "pages")),
    VIDEO("video", "영상", 0xFFBF5AF2.toInt(), setOf("mp4", "mov", "mkv", "avi", "webm", "3gp")),
    AUDIO("audio", "음악·녹음", 0xFF30D158.toInt(), setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")),
    OTHER("other", "기타", 0xFF8E8E93.toInt(), emptySet());

    companion object {
        fun of(name: String): Kind {
            val e = name.substringAfterLast('.', "").lowercase()
            return values().firstOrNull { e in it.exts } ?: OTHER
        }
        fun fromKey(k: String) = values().firstOrNull { it.key == k }
    }
}

class FItem(val file: File, val source: String) {
    val name: String = file.name
    val ext: String = name.substringAfterLast('.', "").lowercase()
    val base: String = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
    val size: Long = file.length()
    val modified: Long = file.lastModified()
    val kind: Kind = Kind.of(name)
    val path: String get() = file.absolutePath
}

object FileRepo {
    private val SKIP_EXT = setOf("crdownload", "part", "tmp", "download", "partial")

    fun sourceLabel(path: String): String {
        val f = File(path)
        return when {
            f.name.equals("KakaoTalk", true) -> "카카오톡"
            f.name.equals("Screenshots", true) -> "스크린샷"
            f.name.equals("Download", true) -> "다운로드"
            else -> f.name
        }
    }

    /** 실제로 있는 받는 곳만 (경로, 이름) */
    fun sources(store: Store): List<Pair<String, String>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        for (p in store.sources) {
            val f = File(p)
            if (!f.isDirectory) continue
            var label = sourceLabel(p)
            // 같은 이름(카카오톡, 스크린샷)이 두 곳이면 하나로 합쳐 보이게 이름 뒤에 붙임
            if (!seen.add(label)) label = "$label · ${f.parentFile?.name ?: ""}"
            out.add(p to label)
        }
        return out
    }

    private fun usable(f: File): Boolean =
        f.isFile && !f.name.startsWith(".") && f.name.substringAfterLast('.', "").lowercase() !in SKIP_EXT

    /** 한 폴더 바로 아래 파일 (하위 폴더는 들어가지 않음) */
    fun filesIn(dir: File, source: String = sourceLabel(dir.absolutePath)): List<FItem> =
        (dir.listFiles() ?: emptyArray()).filter { usable(it) }.map { FItem(it, source) }

    /** 받은 파일함: 모든 받는 곳의 파일 */
    fun inbox(store: Store): List<FItem> {
        val root = store.root.absolutePath
        return sources(store).flatMap { (p, label) -> if (p.startsWith(root)) emptyList() else filesIn(File(p), label) }
    }

    /** 내 저장소의 하위 폴더 (깊이 2까지) */
    fun tree(store: Store): List<Pair<File, Int>> {
        val out = ArrayList<Pair<File, Int>>()
        fun walk(d: File, depth: Int) {
            val subs = (d.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
            for (s in subs) { out.add(s to depth); if (depth < 1) walk(s, depth + 1) }
        }
        walk(store.root, 0)
        return out
    }

    fun countFiles(d: File): Int = (d.listFiles() ?: emptyArray()).count { usable(it) }

    // ======================= 옮기기 · 복사 · 삭제 =======================
    /** 같은 이름이 있으면 '이름 (2).pdf'처럼 비어 있는 이름을 찾음 */
    fun freeName(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val ext = name.substringAfterLast('.', "")
        val base = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
        var i = 2
        while (true) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            if (!f.exists()) return f
            i++
        }
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { i -> FileOutputStream(dst).use { o -> i.copyTo(o, 64 * 1024) } }
        dst.setLastModified(src.lastModified())
    }

    /** 옮긴 개수를 돌려줌 */
    fun move(ctx: Context, items: List<File>, target: File): Int {
        target.mkdirs()
        var n = 0
        val scan = ArrayList<String>()
        for (f in items) {
            if (!f.exists() || f.parentFile == target) continue
            val dst = freeName(target, f.name)
            val ok = f.renameTo(dst) || try { copyFile(f, dst); f.delete(); true } catch (e: Exception) { dst.delete(); false }
            if (ok) { n++; scan.add(f.absolutePath); scan.add(dst.absolutePath) }
        }
        scanMedia(ctx, scan)
        return n
    }

    fun copy(ctx: Context, items: List<File>, target: File): Int {
        target.mkdirs()
        var n = 0
        val scan = ArrayList<String>()
        for (f in items) {
            val dst = freeName(target, f.name)
            try { copyFile(f, dst); n++; scan.add(dst.absolutePath) } catch (_: Exception) {}
        }
        scanMedia(ctx, scan)
        return n
    }

    fun delete(ctx: Context, items: List<File>): Int {
        var n = 0
        val scan = ArrayList<String>()
        for (f in items) if (f.delete()) { n++; scan.add(f.absolutePath) }
        scanMedia(ctx, scan)
        return n
    }

    /** 이름 일괄 변경. prefixMode면 앞에 붙이고, 아니면 '새 이름_01'처럼 번호를 붙임 */
    fun batchRename(ctx: Context, items: List<File>, text: String, prefixMode: Boolean): Int {
        var n = 0
        val scan = ArrayList<String>()
        val width = items.size.toString().length.coerceAtLeast(2)
        items.sortedBy { it.lastModified() }.forEachIndexed { i, f ->
            val ext = f.name.substringAfterLast('.', "")
            val newName = if (prefixMode) text + f.name
            else text + "_" + (i + 1).toString().padStart(width, '0') + (if (ext.isEmpty()) "" else ".$ext")
            if (newName == f.name) return@forEachIndexed
            val dst = freeName(f.parentFile ?: return@forEachIndexed, newName)
            if (f.renameTo(dst)) { n++; scan.add(f.absolutePath); scan.add(dst.absolutePath) }
        }
        scanMedia(ctx, scan)
        return n
    }

    /** ZIP 풀기: 같은 자리에 압축 파일 이름의 폴더를 만들어 풂. 한글 이름은 UTF-8이 아니면 CP949로 다시 시도 */
    fun unzip(ctx: Context, zip: File): File? {
        val outDir = freeName(zip.parentFile ?: return null, zip.name.substringBeforeLast('.'))
        for (cs in listOf(Charsets.UTF_8, Charset.forName("MS949"))) {
            try {
                outDir.mkdirs()
                val canon = outDir.canonicalPath + File.separator
                ZipInputStream(FileInputStream(zip), cs).use { zin ->
                    var e: ZipEntry? = zin.nextEntry
                    while (e != null) {
                        val out = File(outDir, e.name)
                        if (!out.canonicalPath.startsWith(canon)) throw SecurityException("zip slip")
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zin.copyTo(it, 64 * 1024) }
                        }
                        e = zin.nextEntry
                    }
                }
                scanMedia(ctx, listOf(outDir.absolutePath))
                return outDir
            } catch (ex: IllegalArgumentException) {
                outDir.deleteRecursively()   // 이름 인코딩이 틀림 → 다음 문자셋으로
            } catch (ex: Exception) {
                outDir.deleteRecursively()
                return null
            }
        }
        return null
    }

    private fun scanMedia(ctx: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        try { MediaScannerConnection.scanFile(ctx.applicationContext, paths.toTypedArray(), null, null) } catch (_: Exception) {}
    }

    // ======================= 자동 규칙 =======================
    /** 켜진 규칙을 받은 파일함에 적용하고 옮긴 개수를 돌려줌 */
    fun applyRules(ctx: Context, store: Store): Int {
        val rules = store.rules.filter { it.on && it.target.isNotEmpty() }
        if (rules.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var moved = 0
        val items = inbox(store)
        for (r in rules) {
            val target = File(r.target)
            val hit = items.filter { it.file.exists() }.filter { f ->
                when (r.kind) {
                    "name" -> r.value.isNotBlank() && f.name.contains(r.value, ignoreCase = true)
                    "ext" -> r.value.isNotBlank() && f.ext == r.value.lowercase().trimStart('.')
                    "age" -> (r.value.isEmpty() || f.kind.key == r.value) && now - f.modified > r.days * 86_400_000L
                    else -> false
                }
            }
            if (hit.isNotEmpty()) moved += move(ctx, hit.map { it.file }, target)
        }
        if (moved > 0) store.logMoved(moved)
        return moved
    }

    fun human(size: Long): String = when {
        size >= 1L shl 30 -> String.format("%.1fGB", size / (1L shl 30).toDouble())
        size >= 1L shl 20 -> String.format("%.1fMB", size / (1L shl 20).toDouble())
        size >= 1024 -> "${size / 1024}KB"
        else -> "${size}B"
    }
}

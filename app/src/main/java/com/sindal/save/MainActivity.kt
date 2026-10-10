package com.sindal.save

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private val main = Handler(Looper.getMainLooper())

    /** inbox, src:<경로>, dir:<경로> */
    private var page = "inbox"
    /** all, pdf, image, zip, rest */
    private var filter = "all"
    private var query = ""
    private val selected = LinkedHashSet<String>()
    private val expanded = HashSet<String>()
    private var items: List<FItem> = emptyList()

    private var listBox: LinearLayout? = null
    private var barBox: FrameLayout? = null
    private var toolsRow: LinearLayout? = null
    private var summaryRow: LinearLayout? = null
    private var busy = false

    private val DIALOG = android.R.style.Theme_Material_Dialog_Alert
    private val HOT_BG = 0x1A64D2FF
    private val HOT = 0xFF64D2FF.toInt()
    /** 개수 카드가 따로 있는 종류 (나머지는 '문서·기타') */
    private val MAIN_KINDS = setOf(Kind.PDF, Kind.IMAGE, Kind.ZIP, Kind.HTML)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        Th.load(store)
        root = FrameLayout(this).apply { setBackgroundColor(Th.BG) }
        setContentView(root)
        window.statusBarColor = Th.BG
        window.navigationBarColor = Th.BG
    }

    override fun onResume() {
        super.onResume()
        if (!hasAccess()) { showPermission(); return }
        ensureRoot()
        // 자동 규칙은 앱을 열 때마다 조용히 적용
        Thread {
            val n = try { FileRepo.applyRules(this, store) } catch (e: Exception) { 0 }
            main.post { if (n > 0) toast("자동 규칙으로 ${n}개를 옮겼어요"); reload() }
        }.start()
    }

    override fun onBackPressed() {
        when {
            selected.isNotEmpty() -> { selected.clear(); renderList() }
            page != "inbox" -> { if (page.startsWith("dir:")) goParent() else go("inbox") }
            else -> super.onBackPressed()
        }
    }

    private fun hasAccess() = Environment.isExternalStorageManager()

    private fun ensureRoot() {
        val r = store.root
        if (!r.exists()) {
            r.mkdirs()
            if (!store.sp.getBoolean("seeded", false)) {
                listOf("공부 자료", "과제", "개인").forEach { File(r, it).mkdirs() }
                store.sp.edit().putBoolean("seeded", true).apply()
            }
        }
    }

    // ======================= 권한 안내 =======================
    private fun showPermission() {
        root.removeAllViews()
        val card = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Th.CARD, 26)
            setPadding(dp(40), dp(36), dp(40), dp(32))
        }
        card.add(txt("파일 접근 권한이 필요해요", 24f, 700, Th.FG).apply { gravity = Gravity.CENTER })
        card.add(txt("다운로드, 카카오톡, 스크린샷 폴더의 파일을 모으고 내 저장소로 옮기려면\n'모든 파일 접근'을 허용해야 합니다. 파일은 이 태블릿 밖으로 나가지 않습니다.", 14f, 400, Th.FG2).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.4f)
        }, 12)
        card.add(pill("허용하러 가기", 0, 46) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {}
            }
        }, 24, false)
        root.addView(card, FrameLayout.LayoutParams(dp(560), -2, Gravity.CENTER))
    }

    // ======================= 불러오기 / 그리기 =======================
    private fun reload() {
        items = when {
            page == "inbox" -> FileRepo.inbox(store)
            page.startsWith("src:") -> File(page.removePrefix("src:")).let { FileRepo.filesIn(it, FileRepo.sourceLabel(it.absolutePath)) }
            page.startsWith("dir:") -> FileRepo.filesIn(File(page.removePrefix("dir:")), "내 저장소")
            else -> emptyList()
        }
        val alive = items.map { it.path }.toSet()
        selected.retainAll(alive)
        render()
    }

    private fun go(p: String) {
        page = p; filter = "all"; query = ""; selected.clear(); expanded.clear()
        reload()
    }

    private fun goParent() {
        val f = File(page.removePrefix("dir:"))
        val parent = f.parentFile
        if (parent == null || f == store.root || parent.absolutePath.length < store.root.absolutePath.length) go("inbox")
        else if (parent == store.root) go("inbox") else go("dir:" + parent.absolutePath)
    }

    private fun render() {
        Th.load(store)
        root.removeAllViews()
        val body = hbox().apply { gravity = Gravity.TOP }
        body.addFixed(buildSidebar(), 236, -1)
        body.addFixed(View(this).apply { setBackgroundColor(0xFF111113.toInt()) }, 1, -1)
        val mainFrame = FrameLayout(this)
        mainFrame.addFull(buildMain())
        val bar = FrameLayout(this)
        barBox = bar
        mainFrame.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(20) })
        body.addWeighted(mainFrame, 0, 1f, -1)
        root.addFull(body)
        renderList()
    }

    // ======================= 사이드바 =======================
    private fun buildSidebar(): View {
        val side = vbox()
        side.setBackgroundColor(0xFF050505.toInt())
        side.setPadding(dp(14), dp(26), dp(14), dp(16))

        val logo = hbox()
        logo.setPadding(dp(10), 0, 0, dp(12))
        logo.addView(icon(R.drawable.ic_folder, Th.FG, 22))
        logo.addView(txt("정리함", 19f, 700, Th.FG), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        side.add(logo)

        val mid = vbox()
        mid.add(secTitle("모아 보기"))
        mid.add(menuRow("받은 파일함", FileRepo.inbox(store).size, page == "inbox", null) { go("inbox") }, 2)
        for ((p, label) in FileRepo.sources(store)) {
            if (p.startsWith(store.root.absolutePath)) continue
            mid.add(menuRow(label, FileRepo.countFiles(File(p)), page == "src:$p", null) { go("src:$p") }, 2)
        }
        mid.add(secTitle("내 저장소"), 6)
        val tree = FileRepo.tree(store)
        var topIndex = 0
        var lastTopColor = Th.FG2
        for ((d, depth) in tree) {
            val color = if (depth == 0) store.folderColor(d.absolutePath, topIndex++).also { lastTopColor = it } else lastTopColor
            val row = menuRow(d.name, FileRepo.countFiles(d), page == "dir:${d.absolutePath}", if (depth == 0) color else null, depth) {
                go("dir:" + d.absolutePath)
            }
            row.setOnLongClickListener { folderMenu(d, it); true }
            makeDropTarget(row, d)
            mid.add(row, 2)
        }
        mid.add(txt("+ 새 폴더", 13.5f, 500, Th.FG3).apply {
            setPadding(dp(10), dp(10), 0, dp(10))
            setOnClickListener { newFolderDialog(store.root) }
        })
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(mid) }
        side.addWeighted(scroll)

        // 자동 규칙 카드
        val rules = store.rules
        val rc = cardBox(14, 12, 14)
        rc.add(txt("자동 정리 규칙 ${rules.count { it.on }}개", 12.5f, 700, Th.FG))
        if (rules.isEmpty()) rc.add(txt("눌러서 규칙 만들기", 11.5f, 400, Th.FG2), 4)
        rules.filter { it.on }.take(3).forEach { rc.add(txt(it.describe(), 11.5f, 400, Th.FG2).oneLine(), 4) }
        rc.setOnClickListener { rulesDialog() }
        side.add(rc, 10)
        return side
    }

    private fun secTitle(s: String): View = txt(s, 11f, 600, Th.FG3).apply { setPadding(dp(10), dp(12), 0, dp(6)) }

    private fun menuRow(name: String, count: Int, on: Boolean, dot: Int?, depth: Int = 0, onClick: () -> Unit): LinearLayout {
        val r = hbox()
        r.minimumHeight = dp(36)
        r.setPadding(dp(10 + depth * 20), 0, dp(10), 0)
        if (on) r.background = rounded(Th.CARD3, 10)
        if (dot != null) {
            r.addView(View(this).apply { background = rounded(dot, 3) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(9) })
        }
        r.addWeighted(txt(name, if (depth > 0) 13f else 13.5f, if (on) 700 else 500, if (on) Th.FG else Th.FG2).oneLine())
        r.addView(txt(count.toString(), 11.5f, 500, Th.FG3))
        r.setOnClickListener { onClick() }
        r.tag = r.background
        return r
    }

    /** 사이드바 폴더에 파일을 끌어다 놓으면 옮김 */
    private fun makeDropTarget(row: View, dir: File) {
        val normal = row.background
        row.setOnDragListener { v, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    v.background = rounded(HOT_BG, 10, HOT, 2); v.elevation = dpf(8f); true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { v.background = normal; v.elevation = 0f; true }
                DragEvent.ACTION_DROP -> {
                    v.background = normal
                    val paths = e.clipData?.getItemAt(0)?.text?.toString()?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
                    if (paths.isNotEmpty()) doMove(paths.map { File(it) }, dir)
                    true
                }
                else -> true
            }
        }
    }

    private fun folderMenu(d: File, anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "열기")
        pm.menu.add(0, 2, 1, "하위 폴더 만들기")
        pm.menu.add(0, 3, 2, "이름 바꾸기")
        if (d.parentFile == store.root) pm.menu.add(0, 4, 3, "색 바꾸기")
        pm.menu.add(0, 5, 4, "삭제 (빈 폴더만)")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> go("dir:" + d.absolutePath)
                2 -> newFolderDialog(d)
                3 -> textDialog("폴더 이름 바꾸기", d.name) { n ->
                    val dst = File(d.parentFile, n)
                    if (dst.exists()) toast("같은 이름의 폴더가 있어요")
                    else if (d.renameTo(dst)) {
                        store.renameFolderKeys(d.absolutePath, dst.absolutePath)
                        if (page == "dir:${d.absolutePath}") page = "dir:${dst.absolutePath}"
                        reload()
                    } else toast("이름을 바꾸지 못했어요")
                }
                4 -> colorDialog(d)
                5 -> {
                    val kids = d.listFiles() ?: emptyArray()
                    if (kids.isNotEmpty()) toast("폴더가 비어 있어야 지울 수 있어요")
                    else { d.delete(); if (page == "dir:${d.absolutePath}") page = "inbox"; reload() }
                }
            }
            true
        }
        pm.show()
    }

    private fun colorDialog(d: File) {
        val box = hbox().apply { setPadding(dp(24), dp(16), dp(24), dp(8)) }
        lateinit var dlg: AlertDialog
        Store.FOLDER_COLORS.forEachIndexed { i, c ->
            val v = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                setOnClickListener { store.setFolderColor(d.absolutePath, c); dlg.dismiss(); reload() }
            }
            box.addFixed(v, 30, 30, if (i == 0) 0 else 10)
        }
        dlg = AlertDialog.Builder(this, DIALOG).setTitle("${d.name} 색").setView(box).setNegativeButton("취소", null).create()
        dlg.show()
    }

    // ======================= 본문 =======================
    private fun buildMain(): View {
        val m = vbox()
        m.setPadding(dp(38), dp(22), dp(38), 0)

        // 머리
        val head = hbox()
        val titles = vbox()
        val (sub, title) = when {
            page == "inbox" -> FileRepo.sources(store).filter { !it.first.startsWith(store.root.absolutePath) }
                .map { it.second }.distinct().joinToString(" · ").ifEmpty { "받는 곳 없음" } + "에서 모음" to "받은 파일함"
            page.startsWith("src:") -> File(page.removePrefix("src:")).absolutePath.removePrefix(Store.EXT.absolutePath) to FileRepo.sourceLabel(page.removePrefix("src:"))
            else -> {
                val f = File(page.removePrefix("dir:"))
                ("내 저장소" + f.absolutePath.removePrefix(store.root.absolutePath).replace("/", " › ").removeSuffix(" › " + f.name)) to f.name
            }
        }
        val subV = txt(sub, 12.5f, 400, Th.FG2).oneLine()
        if (page.startsWith("dir:")) { subV.text = "‹ " + sub; subV.setOnClickListener { goParent() } }
        titles.add(subV)
        titles.add(txt(title, 40f, 100, Th.FG).apply { letterSpacing = -0.01f }.oneLine(), 2)
        head.addWeighted(titles)

        // 이번 주 정리율
        val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, firstDayOfWeek); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        val moved = store.movedSince(cal.timeInMillis)
        val waiting = FileRepo.inbox(store).size
        val pct = if (moved + waiting == 0) 100 else moved * 100 / (moved + waiting)
        head.addView(RingView(this, pct / 100f), LinearLayout.LayoutParams(dp(46), dp(46)))
        val rt = vbox()
        rt.add(txt("$pct%", 20f, 700, Th.FG))
        rt.add(txt("이번 주 정리 · ${moved}개", 11.5f, 400, Th.FG2), 2)
        head.addView(rt, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12); marginEnd = dp(18) })
        head.addFixed(searchField(), 220, 38)
        m.add(head)

        // 하위 폴더 (내 저장소 화면)
        if (page.startsWith("dir:")) {
            val subs = (File(page.removePrefix("dir:")).listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }
            val row = hbox()
            subs.forEach { s ->
                val chip = hbox().apply {
                    background = rounded(Th.CARD, 12); setPadding(dp(14), 0, dp(14), 0); minimumHeight = dp(38)
                    addView(icon(R.drawable.ic_folder, Th.FG2, 15))
                    addView(txt("${s.name}  ${FileRepo.countFiles(s)}", 13f, 600, Th.FG), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                    setOnClickListener { go("dir:" + s.absolutePath) }
                }
                makeDropTarget(chip, s)
                row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
            row.addView(txt("+ 하위 폴더", 13f, 500, Th.FG3).apply { setPadding(dp(8), dp(10), dp(8), dp(10)); setOnClickListener { newFolderDialog(File(page.removePrefix("dir:"))) } })
            m.add(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, 14)
        }

        // 개수 카드
        val sum = hbox()
        summaryRow = sum
        m.add(sum, 14)

        // 도구 줄
        val tl = hbox()
        tl.addFixed(segmented(listOf("격자", "목록"), if (store.view == "list") 1 else 0) {
            store.view = if (it == 1) "list" else "grid"; renderList(); rebuildToolbarState(tl)
        }, 128, -2)
        tl.addView(dd("정렬 · " + mapOf("date" to "날짜 ↓", "name" to "이름", "size" to "크기 ↓")[store.sort]) {
            store.sort = when (store.sort) { "date" -> "name"; "name" -> "size"; else -> "date" }; render()
        }, LinearLayout.LayoutParams(-2, dp(34)).apply { marginStart = dp(8) })
        tl.addView(dd("묶기 · " + mapOf("kind" to "종류", "source" to "받은 곳", "none" to "없음")[store.group]) {
            store.group = when (store.group) { "kind" -> "source"; "source" -> "none"; else -> "kind" }; render()
        }, LinearLayout.LayoutParams(-2, dp(34)).apply { marginStart = dp(8) })
        tl.addSpace()
        val tools = hbox()
        toolsRow = tools
        tools.addView(tool("복사") { pickFolder("복사할 곳") { d -> doCopy(selFiles(), d) } })
        tools.addView(tool("이름 일괄 변경") { renameDialog() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        tools.addView(tool("압축 풀기") { doUnzip() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        tools.addView(tool("삭제", Th.RED) { confirmDelete() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        tl.addView(tools)
        m.add(tl, 14)

        // 목록
        val list = vbox()
        list.setPadding(0, 0, 0, dp(110))
        listBox = list
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        m.addWeighted(scroll, 4)
        return m
    }

    private fun rebuildToolbarState(@Suppress("UNUSED_PARAMETER") tl: LinearLayout) { render() }

    private fun dd(text: String, onClick: () -> Unit): TextView = txt(text, 12f, 500, 0xFFC7C7CC.toInt()).apply {
        gravity = Gravity.CENTER; background = rounded(Th.CARD, 10); setPadding(dp(12), 0, dp(12), 0); setOnClickListener { onClick() }
    }

    private fun tool(text: String, color: Int = Th.FG, onClick: () -> Unit): TextView = txt(text, 12.5f, 600, color).apply {
        gravity = Gravity.CENTER; minHeight = dp(34); background = rounded(Th.CARD2, 10); setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { if (selected.isEmpty()) toast("먼저 파일을 골라 주세요") else onClick() }
    }

    private fun searchField(): View {
        val box = hbox()
        box.background = rounded(Th.CARD, 11)
        box.setPadding(dp(12), 0, dp(8), 0)
        box.addView(icon(R.drawable.ic_search, Th.FG3, 14))
        val et = EditText(this).apply {
            hint = "검색 · 이름 · 확장자"; setHintTextColor(Th.FG3); setTextColor(Th.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); typeface = Th.font(this@MainActivity, 400)
            background = null; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(8), 0, 0, 0); setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; renderList() }
            })
            setOnEditorActionListener { v, _, _ ->
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); true
            }
        }
        box.addWeighted(et, 0, 1f, 38)
        return box
    }

    // ======================= 목록 그리기 (선택·검색이 바뀔 때마다) =======================
    private fun filtered(): List<FItem> {
        var l = items
        if (query.isNotEmpty()) l = l.filter { it.name.contains(query, true) || it.ext == query.lowercase().trimStart('.') }
        l = when (filter) {
            "pdf" -> l.filter { it.kind == Kind.PDF }
            "image" -> l.filter { it.kind == Kind.IMAGE }
            "zip" -> l.filter { it.kind == Kind.ZIP }
            "html" -> l.filter { it.kind == Kind.HTML }
            "rest" -> l.filter { it.kind !in MAIN_KINDS }
            else -> l
        }
        return when (store.sort) {
            "name" -> l.sortedBy { it.name.lowercase() }
            "size" -> l.sortedByDescending { it.size }
            else -> l.sortedByDescending { it.modified }
        }
    }

    private fun renderList() {
        val list = listBox ?: return
        list.removeAllViews()
        renderSummary()
        toolsRow?.alpha = if (selected.isEmpty()) 0.35f else 1f

        val shown = filtered()
        if (shown.isEmpty()) {
            list.add(txt(if (items.isEmpty()) "여기에는 파일이 없어요" else "조건에 맞는 파일이 없어요", 15f, 500, Th.FG3), 40)
        }
        val groups: List<Triple<String, Int, List<FItem>>> = when (store.group) {
            "kind" -> Kind.values().mapNotNull { k -> shown.filter { it.kind == k }.takeIf { it.isNotEmpty() }?.let { Triple(k.label, k.color, it) } }
            "source" -> shown.groupBy { it.source }.map { (s, l) -> Triple(s, Th.FG2, l) }
            else -> if (shown.isEmpty()) emptyList() else listOf(Triple("전체", Th.FG2, shown))
        }
        for ((title, color, l) in groups) {
            val h = hbox()
            h.addView(View(this).apply { background = rounded(color, 4) }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })
            h.addView(txt(title, 15f, 700, Th.FG))
            h.addView(txt("${l.size}개", 12f, 400, Th.FG3), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            h.addSpace()
            val allSel = l.all { it.path in selected }
            h.addView(txt(if (allSel) "선택 해제" else "모두 선택", 12f, 500, Th.FG2).apply {
                setPadding(dp(8), dp(6), 0, dp(6))
                setOnClickListener { if (allSel) l.forEach { selected.remove(it.path) } else l.forEach { selected.add(it.path) }; renderList() }
            })
            list.add(h, 16)
            val limit = if (title in expanded) l.size else 60
            val part = l.take(limit)
            if (store.view == "list") {
                part.forEach { list.add(listRow(it), 0) }
            } else {
                part.chunked(3).forEachIndexed { i, row ->
                    val r = hbox()
                    row.forEachIndexed { j, it -> r.addWeighted(gridCell(it), if (j == 0) 0 else 10) }
                    repeat(3 - row.size) { r.addWeighted(View(this), 10) }
                    list.add(r, if (i == 0) 8 else 10)
                }
            }
            if (l.size > limit) list.add(pill("${l.size - limit}개 더 보기", 1, 36) { expanded.add(title); renderList() }, 10, false)
        }
        renderBar()
    }

    private fun renderSummary() {
        val sum = summaryRow ?: return
        sum.removeAllViews()
        val rest = items.count { it.kind !in MAIN_KINDS }
        val cards = listOf(
            Triple("all", "전체", items.size) to Th.FG,
            Triple("pdf", "PDF", items.count { it.kind == Kind.PDF }) to Kind.PDF.color,
            Triple("image", "이미지", items.count { it.kind == Kind.IMAGE }) to Kind.IMAGE.color,
            Triple("zip", "압축", items.count { it.kind == Kind.ZIP }) to Kind.ZIP.color,
            Triple("html", "HTML", items.count { it.kind == Kind.HTML }) to Kind.HTML.color,
            Triple("rest", "문서·기타", rest) to Kind.DOC.color,
        )
        cards.forEachIndexed { i, (t, color) ->
            val (key, label, n) = t
            val on = filter == key
            val c = FrameLayout(this)
            c.background = rounded(if (on) Th.FG else Th.CARD, 16)
            c.clipToOutline = true
            if (!on) c.addView(View(this).apply {
                background = GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dpf(40f)
                    colors = intArrayOf((color and 0x00FFFFFF) or 0x66000000, color and 0x00FFFFFF)
                }
            }, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.END or Gravity.TOP).apply { topMargin = -dp(28); marginEnd = -dp(28) })
            val col = vbox().apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
            col.add(txt(n.toString(), 22f, 700, if (on) Th.BG else Th.FG))
            col.add(txt(label, 12f, 400, if (on) 0xFF333333.toInt() else Th.FG2), 6)
            c.addView(col, FrameLayout.LayoutParams(-1, -1))
            c.setOnClickListener { filter = key; renderList() }
            sum.addWeighted(c, if (i == 0) 0 else 10, 1f, 70)
        }
    }

    private fun badge(it: FItem, sizeDp: Int): View {
        val c = it.kind.color
        val f = FrameLayout(this)
        f.background = android.graphics.drawable.LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(
            rounded(0xFF141416.toInt(), 12, 0xFF232326.toInt(), 1),
            GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dpf(sizeDp * 0.55f)
                colors = intArrayOf((c and 0x00FFFFFF) or 0x48000000, c and 0x00FFFFFF); cornerRadius = dpf(12f)
            }))
        val label = (if (it.ext.isEmpty()) "?" else it.ext).uppercase().take(4)
        val t = txt(label, if (label.length > 3) 9f else 10.5f, 700, c).apply {
            gravity = Gravity.CENTER; setShadowLayer(dpf(6f), 0f, 0f, c)
        }
        f.addView(t, FrameLayout.LayoutParams(-1, -1))
        return f
    }

    private fun meta(it: FItem): String {
        val d = Date(it.modified)
        val today = Calendar.getInstance()
        val c = Calendar.getInstance().apply { time = d }
        val days = ((today.timeInMillis - c.timeInMillis) / 86_400_000L).toInt()
        val ds = when {
            today.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) && today.get(Calendar.YEAR) == c.get(Calendar.YEAR) -> "오늘"
            days <= 1 -> "어제"
            today.get(Calendar.YEAR) == c.get(Calendar.YEAR) -> SimpleDateFormat("M월 d일", Locale.KOREAN).format(d)
            else -> SimpleDateFormat("yyyy.M.d", Locale.KOREAN).format(d)
        }
        return "${FileRepo.human(it.size)} · ${it.source} · $ds"
    }

    private fun checkView(on: Boolean): View {
        val f = FrameLayout(this)
        f.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (on) setColor(Th.FG) else { setColor(0); setStroke(dp(2), Th.KNOB) }
        }
        if (on) f.addView(icon(R.drawable.ic_check, Th.BG, 12), FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
        return f
    }

    private fun bindItem(v: View, check: View, it: FItem) {
        v.setOnClickListener { _ -> if (selected.isEmpty()) openFile(it) else toggle(it) }
        check.setOnClickListener { _ -> toggle(it) }
        v.setOnLongClickListener { view ->
            if (it.path !in selected) { selected.add(it.path); renderList() }
            val data = ClipData.newPlainText("files", selected.joinToString("\n"))
            view.startDragAndDrop(data, View.DragShadowBuilder(view), null, 0)
            true
        }
    }

    private fun toggle(it: FItem) {
        if (!selected.remove(it.path)) selected.add(it.path)
        renderList()
    }

    private fun gridCell(it: FItem): View {
        val on = it.path in selected
        val r = hbox()
        r.minimumHeight = dp(60)
        r.setPadding(dp(12), 0, dp(12), 0)
        r.background = if (on) rounded(0xFF131317.toInt(), 15, Th.KNOB, 1) else rounded(Th.CARD, 15)
        r.addFixed(badge(it, 40), 40, 40)
        val t = vbox()
        t.add(txt(it.base, 13f, 600, Th.FG).oneLine())
        t.add(txt(meta(it), 11f, 400, Th.FG2).oneLine(), 4)
        r.addWeighted(t, 12)
        val ck = checkView(on)
        r.addFixed(ck, 22, 22, 10)
        bindItem(r, ck, it)
        return r
    }

    private fun listRow(it: FItem): View {
        val on = it.path in selected
        val r = hbox()
        r.minimumHeight = dp(46)
        r.setPadding(dp(10), 0, dp(10), 0)
        if (on) r.background = rounded(0xFF121216.toInt(), 8)
        val ck = checkView(on)
        r.addFixed(ck, 20, 20)
        r.addFixed(badge(it, 28), 28, 28, 12)
        r.addWeighted(txt(it.name, 13f, 500, Th.FG).oneLine(), 12, 3f)
        r.addWeighted(txt(it.ext.uppercase(), 12f, 400, Th.FG2), 8, 0.6f)
        r.addWeighted(txt(FileRepo.human(it.size), 12f, 400, Th.FG2), 8, 0.7f)
        r.addWeighted(txt(it.source, 12f, 400, Th.FG2).oneLine(), 8, 0.8f)
        r.addWeighted(txt(meta(it).substringAfterLast(" · "), 12f, 400, Th.FG2), 8, 0.7f)
        bindItem(r, ck, it)
        val wrap = vbox()
        wrap.add(r)
        wrap.addView(View(this).apply { setBackgroundColor(0xFF121214.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrap
    }

    // ======================= 하단 막대 =======================
    private fun renderBar() {
        val bar = barBox ?: return
        bar.removeAllViews()
        if (selected.isEmpty()) return
        val b = hbox()
        b.background = rounded(0xF7161618.toInt(), 29, 0xFF2A2A2D.toInt(), 1)
        b.elevation = dpf(16f)
        b.setPadding(dp(20), dp(8), dp(8), dp(8))
        b.addView(txt("${selected.size}개 선택됨", 14f, 700, Th.FG))
        b.addView(txt("빠른 옮기기", 12f, 400, Th.FG2), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14); marginEnd = dp(6) })
        val tree = FileRepo.tree(store)
        val quick = (store.recentTargets.map { File(it) }.filter { it.isDirectory } +
            tree.filter { it.second == 0 }.map { it.first }).distinct().take(3)
        quick.forEach { d ->
            val color = folderColorOf(d, tree)
            val q = hbox().apply {
                background = rounded(if (d == quick.first()) Th.FG else 0xFF232326.toInt(), 20)
                setPadding(dp(14), 0, dp(14), 0); minimumHeight = dp(40)
                addView(View(this@MainActivity).apply { background = rounded(color, 3) }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(7) })
                addView(txt(d.name, 13f, 600, if (d == quick.first()) Th.BG else Th.FG))
                setOnClickListener { doMove(selFiles(), d) }
            }
            b.addView(q, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }
        b.addView(pill("다른 폴더…", 1, 40, 13f) { pickFolder("옮길 곳") { d -> doMove(selFiles(), d) } }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        b.addView(txt("취소", 13f, 600, Th.FG2).apply {
            setPadding(dp(14), dp(10), dp(12), dp(10)); setOnClickListener { selected.clear(); renderList() }
        })
        bar.addView(b)
    }

    private fun folderColorOf(d: File, tree: List<Pair<File, Int>>): Int {
        var top = d
        while (top.parentFile != null && top.parentFile != store.root) top = top.parentFile!!
        val idx = tree.filter { it.second == 0 }.indexOfFirst { it.first == top }
        return if (idx < 0) Th.FG2 else store.folderColor(top.absolutePath, idx)
    }

    private fun selFiles(): List<File> = selected.map { File(it) }

    // ======================= 동작 =======================
    private fun doMove(files: List<File>, target: File) {
        if (busy || files.isEmpty()) return
        busy = true
        Thread {
            val n = FileRepo.move(this, files, target)
            main.post {
                busy = false
                if (n > 0) { store.logMoved(n); store.pushRecent(target.absolutePath) }
                toast("${n}개를 ${target.name}(으)로 옮겼어요")
                selected.clear(); reload()
            }
        }.start()
    }

    private fun doCopy(files: List<File>, target: File) {
        if (busy) return
        busy = true
        toast("복사하는 중…")
        Thread {
            val n = FileRepo.copy(this, files, target)
            main.post { busy = false; toast("${n}개를 ${target.name}에 복사했어요"); store.pushRecent(target.absolutePath); selected.clear(); reload() }
        }.start()
    }

    private fun doUnzip() {
        val zips = selFiles().filter { it.name.endsWith(".zip", true) }
        if (zips.isEmpty()) { toast("ZIP 파일을 골라 주세요 (RAR·7Z는 아직 못 풀어요)"); return }
        if (busy) return
        busy = true
        toast("압축을 푸는 중…")
        Thread {
            var ok = 0
            for (z in zips) if (FileRepo.unzip(this, z) != null) ok++
            main.post { busy = false; toast(if (ok == zips.size) "${ok}개를 풀었어요" else "${ok}/${zips.size}개만 풀었어요"); selected.clear(); reload() }
        }.start()
    }

    private fun confirmDelete() {
        val files = selFiles()
        AlertDialog.Builder(this, DIALOG).setTitle("${files.size}개를 지울까요?")
            .setMessage("휴지통 없이 바로 지워집니다.")
            .setPositiveButton("지우기") { _, _ ->
                val n = FileRepo.delete(this, files); toast("${n}개를 지웠어요"); selected.clear(); reload()
            }.setNegativeButton("취소", null).show()
    }

    private fun renameDialog() {
        val files = selFiles()
        var prefix = false
        val box = vbox().apply { setPadding(dp(24), dp(12), dp(24), 0) }
        val et = EditText(this).apply { isSingleLine = true; setTextColor(Th.FG); hint = "예: 운영체제_강의" ; setHintTextColor(Th.FG3) }
        val prev = txt("", 12f, 400, Th.FG2).apply { setLineSpacing(0f, 1.3f) }
        fun update() {
            val t = et.text.toString().ifEmpty { "새이름" }
            val sorted = files.sortedBy { it.lastModified() }
            val w = files.size.toString().length.coerceAtLeast(2)
            prev.text = "미리보기\n" + sorted.take(3).mapIndexed { i, f ->
                val ext = f.name.substringAfterLast('.', "")
                if (prefix) t + f.name else t + "_" + (i + 1).toString().padStart(w, '0') + (if (ext.isEmpty()) "" else ".$ext")
            }.joinToString("\n") + if (files.size > 3) "\n…" else ""
        }
        val seg = FrameLayout(this)
        fun segView(): View = segmented(listOf("새 이름 + 번호", "앞에 붙이기"), if (prefix) 1 else 0) { prefix = it == 1; seg.removeAllViews(); seg.addView(segView()); update() }
        seg.addView(segView())
        box.add(seg)
        box.add(et, 12)
        box.add(prev, 12)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = update()
        })
        update()
        AlertDialog.Builder(this, DIALOG).setTitle("${files.size}개 이름 바꾸기").setView(box)
            .setPositiveButton("바꾸기") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isEmpty()) { toast("이름을 입력해 주세요"); return@setPositiveButton }
                val n = FileRepo.batchRename(this, files, t, prefix)
                toast("${n}개 이름을 바꿨어요"); selected.clear(); reload()
            }.setNegativeButton("취소", null).show()
    }

    /** 내 저장소 폴더 고르기 (맨 아래 '새 폴더') */
    private fun pickFolder(title: String, onPick: (File) -> Unit) {
        val tree = FileRepo.tree(store)
        val names = tree.map { (d, depth) -> "    ".repeat(depth) + d.name } + "+ 새 폴더 만들기"
        AlertDialog.Builder(this, DIALOG).setTitle(title)
            .setItems(names.toTypedArray()) { _, i ->
                if (i == tree.size) newFolderDialog(store.root) { onPick(it) } else onPick(tree[i].first)
            }.setNegativeButton("취소", null).show()
    }

    private fun newFolderDialog(parent: File, then: ((File) -> Unit)? = null) {
        textDialog(if (parent == store.root) "새 폴더" else "${parent.name} 안에 새 폴더", "") { n ->
            val d = File(parent, n)
            if (d.exists()) toast("같은 이름의 폴더가 있어요")
            else if (d.mkdirs()) { if (then != null) then(d) else reload() } else toast("폴더를 만들지 못했어요")
        }
    }

    private fun textDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply { setText(initial); setSelection(initial.length); isSingleLine = true; setTextColor(Th.FG); inputType = InputType.TYPE_CLASS_TEXT }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(et) }
        AlertDialog.Builder(this, DIALOG).setTitle(title).setView(box)
            .setPositiveButton("확인") { _, _ ->
                val n = et.text.toString().trim().replace("/", "_")
                if (n.isEmpty()) toast("이름을 입력해 주세요") else onOk(n)
            }.setNegativeButton("취소", null).show()
    }

    private fun openFile(it: FItem) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", it.file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.ext) ?: "*/*"
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { toast("이 파일을 열 앱이 없어요") }
    }

    // ======================= 자동 규칙 =======================
    private fun rulesDialog() {
        lateinit var dlg: AlertDialog
        val box = vbox().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val rules = store.rules
        if (rules.isEmpty()) box.add(txt("아직 규칙이 없어요. 규칙을 만들면 앱을 열 때마다 받은 파일을 알아서 옮깁니다.", 13f, 400, Th.FG2).apply { setLineSpacing(0f, 1.3f) })
        rules.forEachIndexed { i, r ->
            val row = hbox(); row.minimumHeight = dp(48)
            row.addWeighted(txt(r.describe(), 13.5f, 500, if (r.on) Th.FG else Th.FG3))
            row.addFixed(toggle(r.on) { on -> store.rules = store.rules.mapIndexed { j, x -> if (j == i) x.copy(on = on) else x } }, 44, 26, 8)
            row.addView(txt("삭제", 12.5f, 600, Th.RED).apply {
                setPadding(dp(12), dp(10), 0, dp(10))
                setOnClickListener { store.rules = store.rules.filterIndexed { j, _ -> j != i }; dlg.dismiss(); rulesDialog(); render() }
            })
            box.add(row)
        }
        dlg = AlertDialog.Builder(this, DIALOG).setTitle("자동 정리 규칙").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("지금 적용") { _, _ ->
                Thread { val n = FileRepo.applyRules(this, store); main.post { toast("규칙으로 ${n}개를 옮겼어요"); reload() } }.start()
            }
            .setNeutralButton("규칙 추가") { _, _ -> addRuleDialog() }
            .setNegativeButton("닫기") { _, _ -> render() }
            .create()
        dlg.show()
    }

    private fun addRuleDialog() {
        var kind = "name"
        var ageKind = ""
        var target: File? = null
        val box = vbox().apply { setPadding(dp(24), dp(12), dp(24), 0) }
        val segBox = FrameLayout(this)
        val value = EditText(this).apply { isSingleLine = true; setTextColor(Th.FG); setHintTextColor(Th.FG3) }
        val days = EditText(this).apply { isSingleLine = true; setTextColor(Th.FG); inputType = InputType.TYPE_CLASS_NUMBER; setText("7") }
        val ageBox = FrameLayout(this)
        val targetBtn = pill("옮길 폴더 고르기", 1, 40) {}
        fun refresh() {
            segBox.removeAllViews()
            segBox.addView(segmented(listOf("이름에 포함", "확장자", "오래된 파일"), listOf("name", "ext", "age").indexOf(kind)) {
                kind = listOf("name", "ext", "age")[it]; refresh()
            })
            value.visibility = if (kind == "age") View.GONE else View.VISIBLE
            value.hint = if (kind == "name") "예: 운영체제" else "예: zip"
            days.visibility = if (kind == "age") View.VISIBLE else View.GONE
            ageBox.removeAllViews()
            if (kind == "age") {
                val keys = listOf("", "pdf", "image", "zip", "html", "doc")
                ageBox.addView(segmented(listOf("전체", "PDF", "이미지", "압축", "HTML", "문서"), keys.indexOf(ageKind).coerceAtLeast(0)) { ageKind = keys[it]; refresh() })
            }
            targetBtn.text = target?.let { "옮길 곳 · ${it.name}" } ?: "옮길 폴더 고르기"
        }
        targetBtn.setOnClickListener { pickFolder("옮길 곳") { target = it; refresh() } }
        box.add(segBox)
        box.add(value, 12)
        box.add(ageBox, 12)
        box.add(days.apply { hint = "며칠 지나면" }, 8)
        box.add(targetBtn, 14)
        refresh()
        AlertDialog.Builder(this, DIALOG).setTitle("규칙 추가").setView(box)
            .setPositiveButton("저장") { _, _ ->
                val t = target
                val v = if (kind == "age") ageKind else value.text.toString().trim()
                when {
                    t == null -> toast("옮길 폴더를 골라 주세요")
                    kind != "age" && v.isEmpty() -> toast("조건을 입력해 주세요")
                    else -> {
                        store.rules = store.rules + Rule(kind, v, days.text.toString().toIntOrNull()?.coerceIn(1, 365) ?: 7, t.absolutePath, true)
                        rulesDialog(); render()
                    }
                }
            }.setNegativeButton("취소", null).show()
    }
}

/** 이번 주 정리율 링 */
class RingView(ctx: android.content.Context, private val value: Float) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val sw = width * 0.11f
        p.strokeWidth = sw
        r.set(sw, sw, width - sw, height - sw)
        p.shader = null; p.color = Th.CARD2
        c.drawArc(r, 0f, 360f, false, p)
        p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), 0xFF5A5A5E.toInt(), Th.FG, Shader.TileMode.CLAMP)
        c.drawArc(r, -90f, 360f * value.coerceIn(0f, 1f), false, p)
    }
}

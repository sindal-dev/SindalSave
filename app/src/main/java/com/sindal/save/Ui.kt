package com.sindal.save

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat

// ======================= 디자인 토큰 =======================
object Th {
    const val BG = 0xFF000000.toInt()
    const val SIDE = 0xFF050505.toInt()
    const val CARD = 0xFF0E0E0F.toInt()
    const val CARD2 = 0xFF1C1C1E.toInt()
    const val CARD3 = 0xFF151517.toInt()
    const val LINE = 0xFF1C1C1E.toInt()
    const val FG = 0xFFF5F5F7.toInt()
    const val FG2 = 0xFF8E8E93.toInt()
    const val FG3 = 0xFF48484A.toInt()
    const val KNOB = 0xFF3A3A3E.toInt()
    const val RED = 0xFFFF453A.toInt()

    /** c1: 진한 쪽, c2: 밝은 쪽(주 강조 색) */
    data class Accent(val key: String, val name: String, val c1: Int, val c2: Int)

    val ACCENTS = listOf(
        Accent("mono", "모노", 0xFF8E8E93.toInt(), 0xFFF5F5F7.toInt()),
        Accent("amber", "앰버", 0xFFFF9F0A.toInt(), 0xFFFFC043.toInt()),
        Accent("ice", "아이스", 0xFF0A84FF.toInt(), 0xFF64D2FF.toInt()),
        Accent("violet", "바이올렛", 0xFF7D5CF5.toInt(), 0xFFD58CFF.toInt()),
        Accent("mint", "민트", 0xFF30D158.toInt(), 0xFF7CF2C2.toInt()),
    )

    /** 현재 강조 색. HomeActivity.render()가 매번 갱신 */
    var accent: Accent = ACCENTS[0]

    /** 카드 배경색(카드 투명도 반영) */
    var cardColor: Int = CARD

    fun load(store: Store) {
        accent = if (store.accentKey == "custom") {
            val c = store.customAccent
            Accent("custom", "직접", darken(c), c)
        } else ACCENTS.firstOrNull { it.key == store.accentKey } ?: ACCENTS[0]
        val a = (store.cardAlpha.coerceIn(0, 100) * 255 / 100)
        cardColor = (a shl 24) or (CARD and 0x00FFFFFF)
    }

    /** 강조 색이 모노일 때 '포인트'로 쓸 색 (시계 점 등은 회색 유지) */
    fun point(): Int = if (accent.key == "mono") FG3 else accent.c2

    fun darken(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = hsv[2] * 0.6f
        return Color.HSVToColor(hsv)
    }

    private val fonts = HashMap<Int, Typeface>()

    /** weight: 100(Thin) ~ 900(Black) */
    fun font(ctx: Context, weight: Int): Typeface {
        val id = when {
            weight >= 900 -> R.font.a2z_black
            weight >= 800 -> R.font.a2z_extrabold
            weight >= 700 -> R.font.a2z_bold
            weight >= 600 -> R.font.a2z_semibold
            weight >= 500 -> R.font.a2z_medium
            weight >= 400 -> R.font.a2z_regular
            weight >= 300 -> R.font.a2z_light
            weight >= 200 -> R.font.a2z_extralight
            else -> R.font.a2z_thin
        }
        fonts[id]?.let { return it }
        val tf = try { ResourcesCompat.getFont(ctx, id) } catch (e: Exception) { null } ?: Typeface.DEFAULT
        fonts[id] = tf
        return tf
    }
}

// ======================= 기본 도우미 =======================
fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.dpf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.txt(text: CharSequence, sizeSp: Float = 15f, weight: Int = 400, color: Int = Th.FG): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Th.font(this@txt, weight)
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
    }

fun TextView.oneLine(): TextView { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; return this }

fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp.toFloat())
        if (strokeDp > 0) setStroke(dp(strokeDp).coerceAtLeast(1), strokeColor)
    }

fun Context.icon(res: Int, color: Int = Th.FG, sizeDp: Int = 18): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(color)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** 카드(둥근 사각형 + 안쪽 여백) */
fun Context.cardBox(padH: Int = 22, padV: Int = 20, radius: Int = 20): LinearLayout = vbox().apply {
    background = rounded(Th.cardColor, radius)
    setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
}

/** 알약 버튼. kind 0: 흰색 주버튼, 1: 회색, 2: 테두리, 3: 빨강 글자 */
fun Context.pill(text: String, kind: Int = 1, heightDp: Int = 40, sizeSp: Float = 13.5f, onClick: () -> Unit): TextView {
    val b = txt(text, sizeSp, if (kind == 0) 700 else 600, when (kind) { 0 -> Color.BLACK; 3 -> Th.RED; else -> Th.FG })
    b.gravity = Gravity.CENTER
    b.minHeight = dp(heightDp)
    b.setPadding(dp(18), 0, dp(18), 0)
    b.background = when (kind) {
        0 -> rounded(Th.FG, 12)
        1 -> rounded(Th.CARD2, 12)
        else -> rounded(Color.TRANSPARENT, 12, Th.LINE, 1)
    }
    b.setOnClickListener { onClick() }
    return b
}

// ======================= 레이아웃 도우미 =======================
fun LinearLayout.add(v: View, topDp: Int = 0, fill: Boolean = true): View {
    val lp = LinearLayout.LayoutParams(
        if (fill) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    lp.topMargin = context.dp(topDp)
    addView(v, lp)
    return v
}

fun LinearLayout.addWeighted(v: View, startDp: Int = 0, weight: Float = 1f, heightDp: Int = -2): View {
    val h = if (heightDp < 0) heightDp else context.dp(heightDp)
    val lp = if (orientation == LinearLayout.HORIZONTAL)
        LinearLayout.LayoutParams(0, h, weight).apply { marginStart = context.dp(startDp) }
    else
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight).apply { topMargin = context.dp(startDp) }
    addView(v, lp)
    return v
}

fun LinearLayout.addFixed(v: View, wDp: Int, hDp: Int, startDp: Int = 0, topDp: Int = 0): View {
    val lp = LinearLayout.LayoutParams(if (wDp < 0) wDp else context.dp(wDp), if (hDp < 0) hDp else context.dp(hDp))
    lp.marginStart = context.dp(startDp)
    lp.topMargin = context.dp(topDp)
    addView(v, lp)
    return v
}

/** 남는 공간을 채우는 빈 칸. 높이(가로 줄)나 너비(세로 줄)는 0으로 둬야 줄 전체가 늘어나지 않음 */
fun LinearLayout.addSpace(): View {
    val v = View(context)
    val lp = if (orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, 0, 1f)
    else LinearLayout.LayoutParams(0, 0, 1f)
    addView(v, lp)
    return v
}

fun FrameLayout.addFull(v: View): View {
    addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    return v
}

/** 화면 조각 하나가 오류를 내도 런처 전체가 꺼지지 않게 감쌈 */
inline fun Context.safeView(name: String, build: () -> View): View = try {
    build()
} catch (e: Throwable) {
    android.util.Log.e("SindalLauncher", "view $name failed", e)
    txt("$name 을(를) 불러오지 못했어요", 12f, 400, Th.FG3)
}

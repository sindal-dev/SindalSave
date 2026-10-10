package com.sindal.save

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

/**
 * 슬라이더. style 0: 빠른 제어용 굵은 막대, style 1: 설정용 가는 선 + 동그란 손잡이.
 * onChange(값, 손을 뗐는지)
 */
class SliderView(ctx: Context, private val style: Int, initial: Float, private val onChange: (Float, Boolean) -> Unit) : View(ctx) {
    var value: Float = initial.coerceIn(0f, 1f)
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onTouchStart: (() -> Boolean)? = null   // false를 돌려주면 조작을 막음
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (style == 0) {
            val rad = context.dpf(8f)
            p.color = Th.CARD2; r.set(0f, 0f, w, h); c.drawRoundRect(r, rad, rad, p)
            p.color = Th.KNOB
            c.save(); c.clipRect(0f, 0f, w * value, h); c.drawRoundRect(r, rad, rad, p); c.restore()
        } else {
            val th = context.dpf(4f); val cy = h / 2; val kr = context.dpf(8f)
            val left = kr; val right = w - kr; val x = left + (right - left) * value
            p.color = 0xFF2A2A2D.toInt(); r.set(left, cy - th / 2, right, cy + th / 2); c.drawRoundRect(r, th, th, p)
            p.color = if (isEnabled) Th.FG else Th.FG3; r.set(left, cy - th / 2, x, cy + th / 2); c.drawRoundRect(r, th, th, p)
            c.drawCircle(x, cy, kr, p)
        }
    }

    private var allowed = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                allowed = onTouchStart?.invoke() ?: true
                if (!allowed) return true
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x, false)
            }
            MotionEvent.ACTION_MOVE -> if (allowed) update(e.x, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (allowed) update(e.x, true)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun update(x: Float, done: Boolean) {
        val pad = if (style == 0) 0f else context.dpf(8f)
        value = ((x - pad) / (width - pad * 2)).coerceIn(0f, 1f)
        onChange(value, done)
    }
}

/** 켜고 끄는 스위치 (44×26dp) */
class ToggleView(ctx: Context, initial: Boolean, private val onToggle: (Boolean) -> Unit) : View(ctx) {
    var checked = initial
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        setOnClickListener { checked = !checked; onToggle(checked) }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = if (checked) Th.FG else 0xFF2A2A2D.toInt()
        r.set(0f, 0f, w, h); c.drawRoundRect(r, h / 2, h / 2, p)
        val kr = h / 2 - context.dpf(3f)
        p.color = if (checked) Th.BG else Th.FG2
        c.drawCircle(if (checked) w - h / 2 else h / 2, h / 2, kr, p)
    }
}

/** 여러 개 중 하나 고르는 버튼 묶음 */
fun Context.segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
    val box = hbox()
    box.background = rounded(Th.CARD3, 12)
    val pad = dp(4)
    box.setPadding(pad, pad, pad, pad)
    options.forEachIndexed { i, s ->
        val on = i == selected
        val t = txt(s, 13f, if (on) 700 else 500, if (on) Th.FG else Th.FG2)
        t.gravity = Gravity.CENTER
        if (on) t.background = rounded(0xFF2A2A2D.toInt(), 9)
        t.setOnClickListener { if (!on) onPick(i) }
        box.addWeighted(t, 0, 1f, 36)
    }
    return box
}

/** 설정 한 줄: 왼쪽 이름, 오른쪽 조절 도구 */
fun Context.settingRow(title: String, right: View?, sub: String? = null, rightWDp: Int = -2, rightHDp: Int = -2, onClick: (() -> Unit)? = null): LinearLayout {
    val row = hbox()
    row.minimumHeight = dp(50)
    val left = vbox()
    left.add(txt(title, 14f, 500, Th.FG))
    if (sub != null) left.add(txt(sub, 11.5f, 400, Th.FG2), 4)
    row.addWeighted(left)
    if (right != null) row.addFixed(right, rightWDp, rightHDp, 12)
    if (onClick != null) row.setOnClickListener { onClick() }
    return row
}

fun Context.toggle(initial: Boolean, onToggle: (Boolean) -> Unit): ToggleView = ToggleView(this, initial, onToggle)

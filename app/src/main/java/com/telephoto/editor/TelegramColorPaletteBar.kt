package com.telephoto.editor

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R

/**
 * Telegram's exact 2-row x 7-column dark floating color palette bar with `<` close and `+` custom color picker (frame 00:39).
 */
class TelegramColorPaletteBar(
    context: Context,
    private val onColorSelected: (Int) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    private val row1Colors = intArrayOf(
        0xFFFF453A.toInt(), // Red
        0xFFFF8A00.toInt(), // Orange
        0xFFFFD60A.toInt(), // Yellow
        0xFF34C759.toInt(), // Green
        0xFF63E6E2.toInt(), // Cyan
        0xFF0A84FF.toInt(), // Blue
        0xFFBF5AF2.toInt()  // Purple
    )

    private val row2Colors = intArrayOf(
        0xFF000000.toInt(), // Black
        0xFFFFFFFF.toInt(), // White
        0xFFD4A373.toInt(), // Tan / Peach
        0xFF8AB4F8.toInt(), // Sky Blue
        0xFFB39DDB.toInt(), // Lavender
        0xFFD988A1.toInt(), // Dusty Rose
        0xFFC5E1A5.toInt()  // Sage Green
    )

    private val swatchViews = mutableListOf<Pair<Int, View>>()
    private var selectedColor: Int = row1Colors[0]

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(6f), AndroidUtilities.dp(8f), AndroidUtilities.dp(6f))

        val backBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_chevron_left)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f))
            setOnClickListener { onClose() }
        }
        addView(backBtn, LayoutParams(AndroidUtilities.dp(40f), AndroidUtilities.dp(40f)))

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            background = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadius = AndroidUtilities.dp(20f).toFloat()
            }
        }
        card.addView(buildColorRow(row1Colors))
        val row2 = buildColorRow(row2Colors).apply {
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = AndroidUtilities.dp(8f)
            }
            layoutParams = lp
        }
        card.addView(row2)
        addView(card, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val plusBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_plus)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f))
            setOnClickListener { showCustomColorDialog() }
        }
        addView(plusBtn, LayoutParams(AndroidUtilities.dp(40f), AndroidUtilities.dp(40f)))
    }

    private fun buildColorRow(colors: IntArray): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            colors.forEach { color ->
                val dot = View(context).apply {
                    setOnClickListener {
                        setSelectedColor(color)
                        onColorSelected(color)
                    }
                }
                val sz = AndroidUtilities.dp(28f)
                val lp = LayoutParams(sz, sz).apply {
                    setMargins(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), 0)
                }
                swatchViews.add(color to dot)
                addView(dot, lp)
            }
        }
    }

    fun setSelectedColor(color: Int) {
        selectedColor = color
        swatchViews.forEach { (c, dot) ->
            val active = c == color
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                if (active) {
                    setStroke(AndroidUtilities.dp(2.5f), if (c == Color.WHITE) 0xFF4DA3FF.toInt() else Color.WHITE)
                } else if (c == Color.BLACK) {
                    setStroke(AndroidUtilities.dp(1f), 0x44FFFFFF)
                }
            }
            dot.scaleX = if (active) 1.08f else 1.0f
            dot.scaleY = if (active) 1.08f else 1.0f
        }
    }

    private fun showCustomColorDialog() {
        var r = Color.red(selectedColor)
        var g = Color.green(selectedColor)
        var b = Color.blue(selectedColor)

        val preview = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(r, g, b))
                cornerRadius = AndroidUtilities.dp(12f).toFloat()
            }
        }

        fun updatePreview() {
            (preview.background as? GradientDrawable)?.setColor(Color.rgb(r, g, b))
        }

        fun sliderRow(label: String, initial: Int, onChange: (Int) -> Unit): LinearLayout {
            return LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val tv = TextView(context).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    width = AndroidUtilities.dp(26f)
                }
                val sb = SeekBar(context).apply {
                    max = 255
                    progress = initial
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                            onChange(p)
                            updatePreview()
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                }
                addView(tv)
                addView(sb, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            val pad = AndroidUtilities.dp(20f)
            setPadding(pad, pad, pad, AndroidUtilities.dp(8f))
            addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(44f)).apply {
                bottomMargin = AndroidUtilities.dp(14f)
            })
            addView(sliderRow("R", r) { r = it })
            addView(sliderRow("G", g) { g = it })
            addView(sliderRow("B", b) { b = it })
        }

        AlertDialog.Builder(context)
            .setTitle("Custom Color")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                val custom = Color.rgb(r, g, b)
                setSelectedColor(custom)
                onColorSelected(custom)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

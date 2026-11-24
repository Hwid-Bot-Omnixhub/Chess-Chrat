package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.data.AssistantSharedState

@SuppressLint("ViewConstructor")
class OverlayControlPillView(
  context: Context,
  private val onDragBy: (dx: Int, dy: Int) -> Unit,
  private val onApplyBestMove: () -> Unit,
  private val onLearnBoard: () -> Unit,
  private val onScanBoard: () -> Unit,
  private val onToggleAutoScan: () -> Unit,
  private val onToggleGrid: () -> Unit,
  private val onToggleLock: () -> Unit,
  private val onToggleTapSync: () -> Unit,
  private val onToggleStats: () -> Unit,
  private val onFlipBoard: () -> Unit,
  private val onUndo: () -> Unit,
  private val onClose: () -> Unit,
) : LinearLayout(context) {

  private val evalBadge: TextView
  private val bestMoveBtn: TextView
  private val learnBtn: TextView
  private val scanBtn: TextView
  private val autoBtn: TextView
  private val lockBtn: TextView
  private val syncBtn: TextView
  private val statsBtn: TextView
  private val gridBtn: TextView
  private val flipBtn: TextView
  private val undoBtn: TextView
  private val closeBtn: TextView

  private fun dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
      .toInt()

  init {
    orientation = HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(8), dp(6), dp(8), dp(6))
    background =
      GradientDrawable().apply {
        setColor(Color.parseColor("#EE0D1117"))
        cornerRadius = dp(24).toFloat()
        setStroke(dp(1), Color.parseColor("#AA10B981"))
      }

    // Pinned drag handle
    val dragHandle =
      TextView(context).apply {
        text = "⋮⋮"
        setTextColor(Color.parseColor("#8B949E"))
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(6), dp(8), dp(6))
        contentDescription = "Drag floating toolbar"
        var lastX = 0f
        var lastY = 0f
        setOnTouchListener { _, event ->
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              lastX = event.rawX
              lastY = event.rawY
              true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - lastX).toInt()
              val dy = (event.rawY - lastY).toInt()
              if (dx != 0 || dy != 0) {
                onDragBy(dx, dy)
                lastX = event.rawX
                lastY = event.rawY
              }
              true
            }
            else -> false
          }
        }
      }
    addView(dragHandle)

    evalBadge =
      TextView(context).apply {
        text = "0.0"
        setTextColor(Color.parseColor("#0D1117"))
        textSize = 12f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background =
          GradientDrawable().apply {
            setColor(Color.parseColor("#10B981"))
            cornerRadius = dp(10).toFloat()
          }
      }
    addView(evalBadge)

    bestMoveBtn =
      makeChipButton("Nf3", "#1A222C", "#10B981") { onApplyBestMove() }.apply {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        contentDescription = "Play suggested best move"
      }
    addView(bestMoveBtn, marginParams())

    val strip = LinearLayout(context).apply {
      orientation = HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }

    lockBtn =
      makeChipButton("Align", "#1A222C", "#E6EDF3") { onToggleLock() }.apply {
        contentDescription = "Align 8x8 frame or lock touch pass-through"
      }
    strip.addView(lockBtn, marginParams())

    learnBtn =
      makeChipButton("Learn", "#1A222C", "#F59E0B") { onLearnBoard() }.apply {
        contentDescription = "Learn board theme from starting position"
      }
    strip.addView(learnBtn, marginParams())

    scanBtn =
      makeChipButton("Scan", "#1A222C", "#38BDF8") { onScanBoard() }.apply {
        contentDescription = "Check board position from screen once"
      }
    strip.addView(scanBtn, marginParams())

    autoBtn =
      makeChipButton("Auto", "#1A222C", "#38BDF8") { onToggleAutoScan() }.apply {
        contentDescription = "Toggle automatic visual board checker"
      }
    strip.addView(autoBtn, marginParams())

    syncBtn =
      makeChipButton("Sync", "#1A222C", "#38BDF8") { onToggleTapSync() }.apply {
        contentDescription = "Tap squares on board to sync move manually"
      }
    strip.addView(syncBtn, marginParams())

    statsBtn =
      makeChipButton("!!", "#1A222C", "#38BDF8") { onToggleStats() }.apply {
        contentDescription = "Show or hide Brilliant/Excellent/Good floating stats"
      }
    strip.addView(statsBtn, marginParams())

    gridBtn =
      makeChipButton("Hide", "#1A222C", "#E6EDF3") { onToggleGrid() }.apply {
        contentDescription = "Show or hide arrow overlay"
      }
    strip.addView(gridBtn, marginParams())

    flipBtn =
      makeChipButton("⇅", "#1A222C", "#E6EDF3") { onFlipBoard() }.apply {
        contentDescription = "Flip board orientation"
      }
    strip.addView(flipBtn, marginParams())

    undoBtn =
      makeChipButton("↶", "#1A222C", "#E6EDF3") { onUndo() }.apply {
        contentDescription = "Undo last synced move"
      }
    strip.addView(undoBtn, marginParams())

    closeBtn =
      makeChipButton("✕", "#2D1619", "#EF4444") { onClose() }.apply {
        contentDescription = "Close floating overlay"
      }
    strip.addView(closeBtn, marginParams())

    val scroller = HorizontalScrollView(context).apply {
      isHorizontalScrollBarEnabled = false
      overScrollMode = View.OVER_SCROLL_NEVER
      addView(strip)
    }
    val maxStripWidth = (resources.displayMetrics.widthPixels * 0.68f).toInt().coerceAtLeast(dp(180))
    addView(scroller, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      width = LayoutParams.WRAP_CONTENT
    })
    scroller.post {
      if (scroller.width > maxStripWidth) {
        scroller.layoutParams = scroller.layoutParams.apply { width = maxStripWidth }
      }
    }
  }

  private fun marginParams(): LayoutParams =
    LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(5) }

  private fun makeChipButton(
    label: String,
    bgHex: String,
    fgHex: String,
    onClick: () -> Unit,
  ): TextView {
    return TextView(context).apply {
      text = label
      setTextColor(Color.parseColor(fgHex))
      textSize = 12f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      gravity = Gravity.CENTER
      minWidth = dp(38)
      setPadding(dp(8), dp(4), dp(8), dp(4))
      background =
        GradientDrawable().apply {
          setColor(Color.parseColor(bgHex))
          cornerRadius = dp(12).toFloat()
        }
      setOnClickListener { onClick() }
    }
  }

  private fun setChipColors(view: TextView, bgHex: String, fgHex: String) {
    view.setTextColor(Color.parseColor(fgHex))
    val bg = view.background as? GradientDrawable
    bg?.setColor(Color.parseColor(bgHex))
  }

  fun updateState(state: AssistantSharedState) {
    evalBadge.text = state.analysis.scoreDisplay
    val top = state.analysis.topMoves.firstOrNull()
    bestMoveBtn.text = top?.san ?: "--"

    if (state.overlayGridVisible) {
      gridBtn.text = "Hide"
      setChipColors(gridBtn, "#1A222C", "#E6EDF3")
    } else {
      gridBtn.text = "Show"
      setChipColors(gridBtn, "#10B981", "#0D1117")
    }

    if (state.overlayPassThrough) {
      lockBtn.text = "Align"
      setChipColors(lockBtn, "#1A222C", "#E6EDF3")
    } else {
      lockBtn.text = "Done"
      setChipColors(lockBtn, "#F59E0B", "#0D1117")
    }

    if (state.visionTemplatesReady) {
      setChipColors(learnBtn, "#1A222C", "#10B981")
    } else {
      setChipColors(learnBtn, "#1A222C", "#F59E0B")
    }

    if (state.autoBoardScanEnabled) {
      autoBtn.text = "Auto•"
      setChipColors(autoBtn, "#10B981", "#0D1117")
    } else {
      autoBtn.text = "Auto"
      setChipColors(autoBtn, "#1A222C", "#38BDF8")
    }

    if (state.overlayTapSyncActive) {
      setChipColors(syncBtn, "#38BDF8", "#0D1117")
    } else {
      setChipColors(syncBtn, "#1A222C", "#38BDF8")
    }

    if (state.showFloatingStatsHud) {
      setChipColors(statsBtn, "#38BDF8", "#0D1117")
    } else {
      setChipColors(statsBtn, "#1A222C", "#38BDF8")
    }

    undoBtn.visibility = if (state.moveHistory.isNotEmpty()) View.VISIBLE else View.GONE
  }
}

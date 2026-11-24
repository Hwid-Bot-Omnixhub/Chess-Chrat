package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.example.chess.PieceColor
import com.example.data.AssistantSharedState

/**
 * Sleek, compact floating status bar modeled after live board-watching overlays:
 * Drag Handle | Eval Pill | Live Status ("Waiting..." / Move) | Pause/Play | Grades | Flip | Close
 * No manual Align buttons and no fake clickable move-play buttons.
 */
@SuppressLint("ViewConstructor")
class OverlayControlPillView(
  context: Context,
  private val onDragBy: (dx: Int, dy: Int) -> Unit,
  private val onOpenApp: () -> Unit,
  private val onToggleAutoScan: () -> Unit,
  private val onToggleStats: () -> Unit,
  private val onFlipBoard: () -> Unit,
  private val onClose: () -> Unit,
) : LinearLayout(context) {

  private val evalBadge: TextView
  private val statusLabel: TextView
  private val pauseBtn: TextView
  private val statsBtn: TextView
  private val flipBtn: TextView
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
        setColor(Color.parseColor("#EC12161E"))
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), Color.parseColor("#5530363D"))
      }

    // Drag handle
    val dragHandle =
      TextView(context).apply {
        text = "⋮⋮"
        setTextColor(Color.parseColor("#8B949E"))
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(4), dp(6), dp(4))
        contentDescription = "Drag floating watcher pill"
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

    // Eval badge (pure readout)
    evalBadge =
      TextView(context).apply {
        text = "0.0"
        setTextColor(Color.parseColor("#F0F6FC"))
        textSize = 12f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background =
          GradientDrawable().apply {
            setColor(Color.parseColor("#21262D"))
            cornerRadius = dp(12).toFloat()
          }
      }
    addView(evalBadge)

    // Non-action status readout ("Waiting...", "d4", "Opp d5", "Tap to Enable Capture")
    statusLabel =
      TextView(context).apply {
        text = "Waiting..."
        setTextColor(Color.parseColor("#E6EDF3"))
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(4), dp(10), dp(4))
        contentDescription = "AI board watcher status"
        setOnClickListener {
          // Only open main app if capture isn't ready yet
          onOpenApp()
        }
      }
    addView(statusLabel)

    pauseBtn =
      makeIconButton("⏸", "#1D2430", "#E6EDF3") { onToggleAutoScan() }.apply {
        contentDescription = "Pause or resume AI board watcher"
      }
    addView(pauseBtn, marginParams())

    statsBtn =
      makeIconButton("!!", "#1D2430", "#38BDF8") { onToggleStats() }.apply {
        contentDescription = "Toggle Brilliant, Excellent and Good move counter"
      }
    addView(statsBtn, marginParams())

    flipBtn =
      makeIconButton("⇅", "#1D2430", "#E6EDF3") { onFlipBoard() }.apply {
        contentDescription = "Flip board side"
      }
    addView(flipBtn, marginParams())

    closeBtn =
      makeIconButton("✕", "#2D1619", "#F87171") { onClose() }.apply {
        contentDescription = "Close watcher overlay"
      }
    addView(closeBtn, marginParams())
  }

  private fun marginParams(): LayoutParams =
    LayoutParams(dp(34), dp(34)).apply { marginStart = dp(5) }

  private fun makeIconButton(
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
      background =
        GradientDrawable().apply {
          setColor(Color.parseColor(bgHex))
          cornerRadius = dp(10).toFloat()
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
    val captureReady = state.shizukuReady || state.mediaProjectionReady
    val myColor = if (!state.flippedBoard) PieceColor.WHITE else PieceColor.BLACK
    val isMyTurn = state.board.sideToMove == myColor
    val top = state.analysis.topMoves.firstOrNull()

    when {
      !captureReady -> {
        evalBadge.text = "•"
        setChipColors(evalBadge, "#3B2F12", "#FBBF24")
        statusLabel.text = "Enable Capture"
        statusLabel.setTextColor(Color.parseColor("#FBBF24"))
        statusLabel.isClickable = true
      }
      !state.autoBoardScanEnabled -> {
        evalBadge.text = state.analysis.scoreDisplay
        setChipColors(evalBadge, "#21262D", "#8B949E")
        statusLabel.text = "Paused"
        statusLabel.setTextColor(Color.parseColor("#8B949E"))
        statusLabel.isClickable = false
      }
      !state.isBoardVisibleOnScreen -> {
        evalBadge.text = "•"
        setChipColors(evalBadge, "#18283A", "#38BDF8")
        statusLabel.text = "Waiting..."
        statusLabel.setTextColor(Color.parseColor("#60A5FA"))
        statusLabel.isClickable = false
      }
      else -> {
        evalBadge.text = state.analysis.scoreDisplay
        if (isMyTurn) {
          setChipColors(evalBadge, "#3F181B", "#FCA5A5")
          statusLabel.text = top?.san ?: if (state.isAnalyzing) "..." else "--"
          statusLabel.setTextColor(Color.parseColor("#F8FAFC"))
        } else {
          setChipColors(evalBadge, "#252B36", "#CBD5E1")
          val oppMove = top?.san ?: if (state.isAnalyzing) "..." else "--"
          statusLabel.text = "Opp $oppMove"
          statusLabel.setTextColor(Color.parseColor("#CBD5E1"))
        }
        statusLabel.isClickable = false
      }
    }

    if (state.autoBoardScanEnabled) {
      pauseBtn.text = "⏸"
      setChipColors(pauseBtn, "#1D2430", "#E6EDF3")
    } else {
      pauseBtn.text = "▶"
      setChipColors(pauseBtn, "#10B981", "#0D1117")
    }

    if (state.showFloatingStatsHud) {
      setChipColors(statsBtn, "#38BDF8", "#0D1117")
    } else {
      setChipColors(statsBtn, "#1D2430", "#38BDF8")
    }
  }
}

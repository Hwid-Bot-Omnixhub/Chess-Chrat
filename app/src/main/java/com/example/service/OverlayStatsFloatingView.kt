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
import android.widget.LinearLayout
import android.widget.TextView
import com.example.chess.MoveQuality
import com.example.chess.PieceColor
import com.example.data.AssistantSharedState

@SuppressLint("ViewConstructor")
class OverlayStatsFloatingView(
  context: Context,
  private val onDragBy: (dx: Int, dy: Int) -> Unit,
  private val onDragFinished: () -> Unit,
  private val onCycleTrackedSide: () -> Unit,
  private val onHideStats: () -> Unit,
) : LinearLayout(context) {

  private val sideChip: TextView
  private val accuracyChip: TextView
  private val antiLagChip: TextView
  private val collapseBtn: TextView
  private val bodyContainer: LinearLayout
  private val lastMoveBanner: TextView

  private val brilliantCountText: TextView
  private val excellentCountText: TextView
  private val goodCountText: TextView
  private val footerSummaryText: TextView

  private var collapsed = false

  private fun dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
      .toInt()

  init {
    orientation = VERTICAL
    setPadding(dp(10), dp(8), dp(10), dp(8))
    background =
      GradientDrawable().apply {
        setColor(Color.parseColor("#EE0D1117"))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.parseColor("#9938BDF8"))
      }

    // Header Row (Draggable)
    val headerRow =
      LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }

    val dragTitle =
      TextView(context).apply {
        text = "⋮⋮ SF MOVE GRADES"
        setTextColor(Color.parseColor("#E6EDF3"))
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(2), dp(2), dp(8), dp(2))
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
            MotionEvent.ACTION_UP -> {
              onDragFinished()
              true
            }
            else -> false
          }
        }
      }
    headerRow.addView(dragTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

    sideChip =
      makePillChip("You: W", "#1A222C", "#38BDF8") { onCycleTrackedSide() }
    headerRow.addView(sideChip, marginStartParams(4))

    accuracyChip =
      makePillChip("100%", "#0B3B2E", "#10B981") {}
    headerRow.addView(accuracyChip, marginStartParams(4))

    antiLagChip =
      makePillChip("⚡", "#1A222C", "#10B981") {}
    headerRow.addView(antiLagChip, marginStartParams(4))

    collapseBtn =
      makePillChip("—", "#1A222C", "#E6EDF3") {
        collapsed = !collapsed
        bodyContainer.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapseBtn.text = if (collapsed) "+" else "—"
      }
    headerRow.addView(collapseBtn, marginStartParams(4))

    val closeBtn =
      makePillChip("✕", "#2D1619", "#EF4444") { onHideStats() }
    headerRow.addView(closeBtn, marginStartParams(4))

    addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    // Body Container
    bodyContainer =
      LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, dp(6), 0, 0)
      }

    lastMoveBanner =
      TextView(context).apply {
        text = "Play or sync moves to grade with Stockfish"
        setTextColor(Color.parseColor("#8B949E"))
        textSize = 11f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background =
          GradientDrawable().apply {
            setColor(Color.parseColor("#161B22"))
            cornerRadius = dp(8).toFloat()
          }
      }
    bodyContainer.addView(
      lastMoveBanner,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )

    // 3 Metric Boxes: Brilliant (!!), Excellent (!), Good (✓)
    val countersRow =
      LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(4))
      }

    val (bBox, bCount) = makeCounterTile("!! BRILLIANT", "#38BDF8", "#0D3248")
    brilliantCountText = bCount
    countersRow.addView(bBox, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

    val (eBox, eCount) = makeCounterTile("! EXCELLENT", "#10B981", "#0B3B2E")
    excellentCountText = eCount
    countersRow.addView(
      eBox,
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) },
    )

    val (gBox, gCount) = makeCounterTile("✓ GOOD", "#4ADE80", "#132E22")
    goodCountText = gCount
    countersRow.addView(
      gBox,
      LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) },
    )

    bodyContainer.addView(countersRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    footerSummaryText =
      TextView(context).apply {
        text = "Moves: 0 • ?! 0 • ?/?? 0 • Anti-Lag ON"
        setTextColor(Color.parseColor("#8B949E"))
        textSize = 10f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
      }
    bodyContainer.addView(
      footerSummaryText,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
    )

    addView(bodyContainer, LayoutParams(dp(296), LayoutParams.WRAP_CONTENT))
  }

  private fun marginStartParams(dpVal: Int): LayoutParams =
    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      marginStart = dp(dpVal)
    }

  private fun makePillChip(
    label: String,
    bgHex: String,
    fgHex: String,
    onClick: () -> Unit,
  ): TextView {
    return TextView(context).apply {
      text = label
      setTextColor(Color.parseColor(fgHex))
      textSize = 10f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      setPadding(dp(6), dp(3), dp(6), dp(3))
      background =
        GradientDrawable().apply {
          setColor(Color.parseColor(bgHex))
          cornerRadius = dp(8).toFloat()
        }
      setOnClickListener { onClick() }
    }
  }

  private fun makeCounterTile(
    title: String,
    accentHex: String,
    bgHex: String,
  ): Pair<LinearLayout, TextView> {
    val countTv =
      TextView(context).apply {
        text = "0"
        setTextColor(Color.parseColor(accentHex))
        textSize = 18f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
      }
    val labelTv =
      TextView(context).apply {
        text = title
        setTextColor(Color.parseColor("#E6EDF3"))
        textSize = 9f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
      }
    val box =
      LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background =
          GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.parseColor(accentHex))
          }
        addView(countTv)
        addView(labelTv)
      }
    return box to countTv
  }

  fun updateState(state: AssistantSharedState) {
    val stats = state.moveStats
    sideChip.text =
      when (state.trackedPlayerSide) {
        PieceColor.WHITE -> "You: W"
        PieceColor.BLACK -> "You: B"
        null -> "All"
      }
    accuracyChip.text = "${stats.accuracyPercent}%"
    antiLagChip.setTextColor(
      Color.parseColor(if (state.antiLagEnabled) "#10B981" else "#8B949E"),
    )

    brilliantCountText.text = stats.brilliantCount.toString()
    excellentCountText.text = stats.excellentCount.toString()
    goodCountText.text = stats.goodCount.toString()

    val lastQ = stats.lastMoveQuality
    val lastSan = stats.lastMoveSan
    if (lastQ != null && lastSan != null) {
      lastMoveBanner.text = "${lastQ.symbol} $lastSan • ${lastQ.label} (${stats.lastGradedBy})"
      val colorHex =
        when (lastQ) {
          MoveQuality.BRILLIANT -> "#38BDF8"
          MoveQuality.EXCELLENT -> "#10B981"
          MoveQuality.GOOD -> "#4ADE80"
          MoveQuality.INACCURACY -> "#F59E0B"
          MoveQuality.MISTAKE,
          MoveQuality.BLUNDER -> "#EF4444"
        }
      lastMoveBanner.setTextColor(Color.parseColor(colorHex))
    } else {
      lastMoveBanner.text = "Play or sync moves to grade with Stockfish"
      lastMoveBanner.setTextColor(Color.parseColor("#8B949E"))
    }

    val errCount = stats.mistakeCount + stats.blunderCount
    val lagText = if (state.antiLagEnabled) "Anti-Lag ON" else "Anti-Lag OFF"
    footerSummaryText.text =
      "Moves: ${stats.totalGradedMoves} • ?! ${stats.inaccuracyCount} • ?/?? $errCount • $lagText"
  }
}

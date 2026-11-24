package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.example.chess.ChessRules
import com.example.chess.Move
import com.example.chess.Square
import com.example.data.AssistantSharedState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@SuppressLint("ViewConstructor")
class OverlayBoardCanvasView(
  context: Context,
  private val onDragBy: (dx: Int, dy: Int) -> Unit,
  private val onResizeBy: (deltaSize: Int) -> Unit,
  private val onInteractionFinished: () -> Unit,
  private val onSquaresMoveSynced: (from: Square, to: Square) -> Unit,
) : View(context) {

  private var sharedState: AssistantSharedState = AssistantSharedState()
  private var selectedSquare: Square? = null
  private var legalDestinations: Set<Square> = emptySet()

  private var touchMode = TouchMode.NONE
  private var downRawX = 0f
  private var downRawY = 0f
  private var lastRawX = 0f
  private var lastRawY = 0f

  private enum class TouchMode {
    NONE,
    DRAG,
    RESIZE,
    TAP_SQUARE,
  }

  private val framePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 5f
      color = Color.parseColor("#CC10B981")
    }

  private val gridLinePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 1.5f
      color = Color.parseColor("#5510B981")
    }

  private val tintBgPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#180D1117")
    }

  private val handlePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#EE10B981")
    }

  private val handleIconPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = 3f
      color = Color.parseColor("#0D1117")
      strokeCap = Paint.Cap.ROUND
    }

  private val coordPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#CCFFFFFF")
      textSize = 22f
      typeface = Typeface.MONOSPACE
    }

  private val pieceGlyphPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      textAlign = Paint.Align.CENTER
      typeface = Typeface.DEFAULT_BOLD
    }

  private val selectHighlightPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#5510B981")
    }

  private val legalDotPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#AA38BDF8")
    }

  private val lastMovePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#33F59E0B")
    }

  private val badgeCirclePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

  private val badgeTextPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#0D1117")
      textAlign = Paint.Align.CENTER
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

  private val bannerRect = RectF()
  private val handleRect = RectF()
  private val arrowHeadPath = Path()
  private val threatDashEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)

  private val arrowGlowPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
      color = Color.argb(75, 13, 17, 23)
    }

  private val arrowShaftPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
    }

  private val arrowHeadPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
    }

  private val labelBannerPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#CC0D1117")
    }

  private val labelBannerTextPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#10B981")
      textSize = 26f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
    }

  fun updateState(newState: AssistantSharedState) {
    val boardChanged = sharedState.board != newState.board
    sharedState = newState
    if (boardChanged) {
      selectedSquare = null
      legalDestinations = emptySet()
    }
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val w = width.toFloat()
    val h = height.toFloat()
    val boardSize = minOf(w, h)
    if (boardSize <= 0f) return
    val sqSize = boardSize / 8f

    val isEditable = !sharedState.overlayPassThrough
    val showGrid = isEditable || sharedState.overlayTapSyncActive

    if (showGrid) {
      canvas.drawRect(0f, 0f, boardSize, boardSize, tintBgPaint)
    }

    // Highlight last move
    val lastMove = sharedState.moveHistory.lastOrNull()?.move
    if (lastMove != null) {
      drawSquareHighlight(canvas, lastMove.from, sqSize, lastMovePaint)
      drawSquareHighlight(canvas, lastMove.to, sqSize, lastMovePaint)
    }

    // Highlight selected square & legal targets during tap-sync
    val sel = selectedSquare
    if (sel != null) {
      drawSquareHighlight(canvas, sel, sqSize, selectHighlightPaint)
      for (dest in legalDestinations) {
        val cx = squareCenterX(dest, sqSize)
        val cy = squareCenterY(dest, sqSize)
        canvas.drawCircle(cx, cy, sqSize * 0.16f, legalDotPaint)
      }
    }

    // Optional grid & subtle corner piece glyphs when aligning or tap-syncing
    if (showGrid) {
      for (i in 1..7) {
        val pos = i * sqSize
        canvas.drawLine(pos, 0f, pos, boardSize, gridLinePaint)
        canvas.drawLine(0f, pos, boardSize, pos, gridLinePaint)
      }
      canvas.drawRect(2.5f, 2.5f, boardSize - 2.5f, boardSize - 2.5f, framePaint)

      // Coordinate hints along edges
      for (i in 0..7) {
        val fileIdx = if (sharedState.flippedBoard) 7 - i else i
        val rankIdx = if (sharedState.flippedBoard) i else 7 - i
        val fileChar = ('a' + fileIdx).toString()
        val rankChar = ('1' + rankIdx).toString()
        canvas.drawText(fileChar, i * sqSize + sqSize * 0.78f, boardSize - 8f, coordPaint)
        canvas.drawText(rankChar, 6f, i * sqSize + 24f, coordPaint)
      }

      if (sharedState.overlayTapSyncActive) {
        pieceGlyphPaint.textSize = sqSize * 0.36f
        for (idx in 0 until 64) {
          val piece = sharedState.board.squares[idx] ?: continue
          val sq = Square.fromIndex(idx)
          val left = squareLeft(sq, sqSize)
          val top = squareTop(sq, sqSize)
          pieceGlyphPaint.color =
            if (piece.color == com.example.chess.PieceColor.WHITE) {
              Color.parseColor("#CCFFFFFF")
            } else {
              Color.parseColor("#CC38BDF8")
            }
          canvas.drawText(
            piece.unicodeSymbol,
            left + sqSize * 0.76f,
            top + sqSize * 0.36f,
            pieceGlyphPaint,
          )
        }
      }
    } else {
      // Faint corner L-ticks when touch-through is active so board bounds stay clear without clutter
      val tick = sqSize * 0.28f
      val pad = 2f
      canvas.drawLine(pad, pad, pad + tick, pad, framePaint)
      canvas.drawLine(pad, pad, pad, pad + tick, framePaint)
      canvas.drawLine(boardSize - pad, pad, boardSize - pad - tick, pad, framePaint)
      canvas.drawLine(boardSize - pad, pad, boardSize - pad, pad + tick, framePaint)
      canvas.drawLine(pad, boardSize - pad, pad + tick, boardSize - pad, framePaint)
      canvas.drawLine(pad, boardSize - pad, pad, boardSize - pad - tick, framePaint)
      canvas.drawLine(boardSize - pad, boardSize - pad, boardSize - pad - tick, boardSize - pad, framePaint)
      canvas.drawLine(boardSize - pad, boardSize - pad, boardSize - pad, boardSize - pad - tick, framePaint)
    }

    // Draw tactical arrows (threat first, then #2, then #1 on top)
    val alphaInt = (sharedState.arrowOpacity * 255).toInt().coerceIn(70, 255)
    val analysis = sharedState.analysis

    if (sharedState.showThreatArrow && analysis.opponentThreat != null) {
      drawArrow(
        canvas = canvas,
        move = analysis.opponentThreat.move,
        sqSize = sqSize,
        color = Color.argb(alphaInt, 239, 68, 68),
        strokeWidth = sqSize * 0.11f,
        dashed = true,
        badgeLabel = "!",
      )
    }

    if (sharedState.showSecondaryArrows && analysis.topMoves.size > 1) {
      val second = analysis.topMoves[1]
      drawArrow(
        canvas = canvas,
        move = second.move,
        sqSize = sqSize,
        color = Color.argb((alphaInt * 0.85f).toInt(), 56, 189, 248),
        strokeWidth = sqSize * 0.12f,
        dashed = false,
        badgeLabel = "2",
      )
    }

    val best = analysis.topMoves.firstOrNull()
    if (best != null) {
      drawArrow(
        canvas = canvas,
        move = best.move,
        sqSize = sqSize,
        color = Color.argb(alphaInt, 16, 185, 129),
        strokeWidth = sqSize * 0.16f,
        dashed = false,
        badgeLabel = "1",
      )
    }

    // Calibration banner & resize handle when unlocked
    if (isEditable) {
      val modeLabel =
        if (sharedState.overlayTapSyncActive) "TAP TWO SQUARES TO SYNC MOVE"
        else "DRAG FRAME OVER BOARD • RESIZE ↘"
      val bannerW = minOf(boardSize * 0.86f, 520f)
      bannerRect.set(
        (boardSize - bannerW) / 2f,
        8f,
        (boardSize + bannerW) / 2f,
        50f,
      )
      canvas.drawRoundRect(bannerRect, 16f, 16f, labelBannerPaint)
      canvas.drawText(modeLabel, boardSize / 2f, 38f, labelBannerTextPaint)

      val handleSize = 54f
      handleRect.set(boardSize - handleSize, boardSize - handleSize, boardSize, boardSize)
      canvas.drawRoundRect(handleRect, 14f, 14f, handlePaint)
      canvas.drawLine(
        boardSize - 38f,
        boardSize - 16f,
        boardSize - 16f,
        boardSize - 38f,
        handleIconPaint,
      )
      canvas.drawLine(
        boardSize - 26f,
        boardSize - 12f,
        boardSize - 12f,
        boardSize - 26f,
        handleIconPaint,
      )
    }
  }

  private fun drawSquareHighlight(canvas: Canvas, sq: Square, sqSize: Float, paint: Paint) {
    val left = squareLeft(sq, sqSize)
    val top = squareTop(sq, sqSize)
    canvas.drawRect(left, top, left + sqSize, top + sqSize, paint)
  }

  private fun drawArrow(
    canvas: Canvas,
    move: Move,
    sqSize: Float,
    color: Int,
    strokeWidth: Float,
    dashed: Boolean,
    badgeLabel: String,
  ) {
    val startX = squareCenterX(move.from, sqSize)
    val startY = squareCenterY(move.from, sqSize)
    val endX = squareCenterX(move.to, sqSize)
    val endY = squareCenterY(move.to, sqSize)
    val dx = endX - startX
    val dy = endY - startY
    val dist = hypot(dx, dy)
    if (dist < 1f) return

    val angle = atan2(dy, dx)
    val headLen = sqSize * 0.36f
    val headAngle = Math.toRadians(30.0)

    val trimStart = sqSize * 0.14f
    val sx = startX + cos(angle) * trimStart
    val sy = startY + sin(angle) * trimStart
    val shaftEndX = endX - cos(angle) * (headLen * 0.68f)
    val shaftEndY = endY - sin(angle) * (headLen * 0.68f)

    arrowGlowPaint.strokeWidth = strokeWidth + 6f
    canvas.drawLine(sx, sy, shaftEndX, shaftEndY, arrowGlowPaint)

    arrowShaftPaint.strokeWidth = strokeWidth
    arrowShaftPaint.color = color
    arrowShaftPaint.pathEffect = if (dashed) threatDashEffect else null
    canvas.drawLine(sx, sy, shaftEndX, shaftEndY, arrowShaftPaint)

    arrowHeadPath.reset()
    arrowHeadPath.moveTo(endX, endY)
    arrowHeadPath.lineTo(
      (endX - headLen * cos(angle - headAngle)).toFloat(),
      (endY - headLen * sin(angle - headAngle)).toFloat(),
    )
    arrowHeadPath.lineTo(
      (endX - headLen * cos(angle + headAngle)).toFloat(),
      (endY - headLen * sin(angle + headAngle)).toFloat(),
    )
    arrowHeadPath.close()

    arrowHeadPaint.color = color
    canvas.drawPath(arrowHeadPath, arrowHeadPaint)

    val badgeR = sqSize * 0.17f
    badgeCirclePaint.color = color
    canvas.drawCircle(startX, startY, badgeR, badgeCirclePaint)
    badgeTextPaint.textSize = badgeR * 1.25f
    val textY = startY - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
    canvas.drawText(badgeLabel, startX, textY, badgeTextPaint)
  }

  private fun squareLeft(sq: Square, sqSize: Float): Float {
    val col = if (sharedState.flippedBoard) 7 - sq.file else sq.file
    return col * sqSize
  }

  private fun squareTop(sq: Square, sqSize: Float): Float {
    val row = if (sharedState.flippedBoard) sq.rank else 7 - sq.rank
    return row * sqSize
  }

  private fun squareCenterX(sq: Square, sqSize: Float): Float =
    squareLeft(sq, sqSize) + sqSize * 0.5f

  private fun squareCenterY(sq: Square, sqSize: Float): Float =
    squareTop(sq, sqSize) + sqSize * 0.5f

  private fun pointToSquare(x: Float, y: Float): Square? {
    val boardSize = minOf(width, height).toFloat()
    if (boardSize <= 0f || x < 0f || y < 0f || x >= boardSize || y >= boardSize) return null
    val sqSize = boardSize / 8f
    val col = (x / sqSize).toInt().coerceIn(0, 7)
    val row = (y / sqSize).toInt().coerceIn(0, 7)
    val file = if (sharedState.flippedBoard) 7 - col else col
    val rank = if (sharedState.flippedBoard) row else 7 - row
    return Square(file, rank)
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (sharedState.overlayPassThrough) return false

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downRawX = event.rawX
        downRawY = event.rawY
        lastRawX = event.rawX
        lastRawY = event.rawY

        val boardSize = minOf(width, height).toFloat()
        val inResizeCorner = event.x >= boardSize - 80f && event.y >= boardSize - 80f
        touchMode =
          when {
            inResizeCorner -> TouchMode.RESIZE
            sharedState.overlayTapSyncActive -> TouchMode.TAP_SQUARE
            else -> TouchMode.DRAG
          }
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        val dx = (event.rawX - lastRawX).toInt()
        val dy = (event.rawY - lastRawY).toInt()
        when (touchMode) {
          TouchMode.DRAG -> {
            if (dx != 0 || dy != 0) {
              onDragBy(dx, dy)
              lastRawX = event.rawX
              lastRawY = event.rawY
            }
          }
          TouchMode.RESIZE -> {
            val delta = maxOf(dx, dy).takeIf { kotlin.math.abs(dx) >= kotlin.math.abs(dy) } ?: dy
            if (delta != 0) {
              onResizeBy(delta)
              lastRawX = event.rawX
              lastRawY = event.rawY
            }
          }
          TouchMode.TAP_SQUARE -> {
            // If user drags substantially during tap sync, allow repositioning too
            if (hypot(event.rawX - downRawX, event.rawY - downRawY) > 24f) {
              touchMode = TouchMode.DRAG
            }
          }
          TouchMode.NONE -> Unit
        }
        return true
      }
      MotionEvent.ACTION_UP -> {
        if (touchMode == TouchMode.TAP_SQUARE) {
          val tapped = pointToSquare(event.x, event.y)
          if (tapped != null) handleSquareTap(tapped)
        } else if (touchMode == TouchMode.DRAG || touchMode == TouchMode.RESIZE) {
          onInteractionFinished()
        }
        touchMode = TouchMode.NONE
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        touchMode = TouchMode.NONE
        return true
      }
    }
    return super.onTouchEvent(event)
  }

  private fun handleSquareTap(tapped: Square) {
    val currentSel = selectedSquare
    if (currentSel == null) {
      val legal = ChessRules.generateLegalMoves(sharedState.board).filter { it.from == tapped }
      if (legal.isNotEmpty()) {
        selectedSquare = tapped
        legalDestinations = legal.map { it.to }.toSet()
        invalidate()
      }
    } else if (tapped == currentSel) {
      selectedSquare = null
      legalDestinations = emptySet()
      invalidate()
    } else if (tapped in legalDestinations) {
      selectedSquare = null
      legalDestinations = emptySet()
      onSquaresMoveSynced(currentSel, tapped)
    } else {
      val legal = ChessRules.generateLegalMoves(sharedState.board).filter { it.from == tapped }
      if (legal.isNotEmpty()) {
        selectedSquare = tapped
        legalDestinations = legal.map { it.to }.toSet()
      } else {
        selectedSquare = null
        legalDestinations = emptySet()
      }
      invalidate()
    }
  }
}

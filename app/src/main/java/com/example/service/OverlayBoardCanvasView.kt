package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.example.chess.Move
import com.example.chess.PieceColor
import com.example.chess.Square
import com.example.data.AssistantSharedState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pure pass-through tactical arrow overlay aligned automatically by [BoardFinder].
 * Renders:
 * - Red arrow for our best move on our turn.
 * - Optional cyan #2 alternative move arrow.
 * - Dark-charcoal (white-rimmed) arrow for opponent's best move on opponent's turn.
 * Draws nothing when waiting on non-board screens.
 */
@SuppressLint("ViewConstructor")
class OverlayBoardCanvasView(
  context: Context,
) : View(context) {

  private var sharedState: AssistantSharedState = AssistantSharedState()

  private val lastMovePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.parseColor("#22F59E0B")
    }

  private val arrowOutlinePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
    }

  private val arrowShaftPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
    }

  private val arrowHeadOutlinePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeJoin = Paint.Join.ROUND
    }

  private val arrowHeadPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
    }

  private val arrowHeadPath = Path()

  fun updateState(newState: AssistantSharedState) {
    sharedState = newState
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (!sharedState.overlayGridVisible || !sharedState.isBoardVisibleOnScreen) return

    val w = width.toFloat()
    val h = height.toFloat()
    val boardSize = minOf(w, h)
    if (boardSize <= 0f) return
    val sqSize = boardSize / 8f

    // Subtle last-move highlight
    val lastMove = sharedState.moveHistory.lastOrNull()?.move
    if (lastMove != null) {
      drawSquareHighlight(canvas, lastMove.from, sqSize, lastMovePaint)
      drawSquareHighlight(canvas, lastMove.to, sqSize, lastMovePaint)
    }

    val analysis = sharedState.analysis
    if (analysis.topMoves.isEmpty()) return

    val alphaInt = (sharedState.arrowOpacity * 255).toInt().coerceIn(85, 255)
    val myColor = if (!sharedState.flippedBoard) PieceColor.WHITE else PieceColor.BLACK
    val isMyTurn = sharedState.board.sideToMove == myColor

    if (isMyTurn) {
      // Optional #2 alternative move in cyan
      if (sharedState.showSecondaryArrows && analysis.topMoves.size > 1) {
        drawArrow(
          canvas = canvas,
          move = analysis.topMoves[1].move,
          sqSize = sqSize,
          fillColor = Color.argb((alphaInt * 0.78f).toInt(), 56, 189, 248),
          outlineColor = Color.argb((alphaInt * 0.55f).toInt(), 15, 23, 42),
          strokeWidth = sqSize * 0.11f,
        )
      }

      // Primary best move for our side in bold red (matching reference UX)
      drawArrow(
        canvas = canvas,
        move = analysis.topMoves[0].move,
        sqSize = sqSize,
        fillColor = Color.argb(alphaInt, 229, 57, 53),
        outlineColor = Color.argb((alphaInt * 0.70f).toInt(), 20, 12, 12),
        strokeWidth = sqSize * 0.15f,
      )
    } else if (sharedState.showThreatArrow) {
      // Opponent's best move when it is their turn (dark charcoal with crisp light rim)
      if (sharedState.showSecondaryArrows && analysis.topMoves.size > 1) {
        drawArrow(
          canvas = canvas,
          move = analysis.topMoves[1].move,
          sqSize = sqSize,
          fillColor = Color.argb((alphaInt * 0.55f).toInt(), 71, 85, 105),
          outlineColor = Color.argb((alphaInt * 0.45f).toInt(), 241, 245, 249),
          strokeWidth = sqSize * 0.10f,
        )
      }

      drawArrow(
        canvas = canvas,
        move = analysis.topMoves[0].move,
        sqSize = sqSize,
        fillColor = Color.argb(alphaInt, 38, 44, 54),
        outlineColor = Color.argb((alphaInt * 0.85f).toInt(), 241, 245, 249),
        strokeWidth = sqSize * 0.145f,
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
    fillColor: Int,
    outlineColor: Int,
    strokeWidth: Float,
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
    val headAngle = Math.toRadians(31.0)

    val trimStart = sqSize * 0.08f
    val sx = startX + cos(angle) * trimStart
    val sy = startY + sin(angle) * trimStart
    val shaftEndX = endX - cos(angle) * (headLen * 0.66f)
    val shaftEndY = endY - sin(angle) * (headLen * 0.66f)

    // Subtle crisp outline so arrow stays readable on both light and dark squares
    arrowOutlinePaint.strokeWidth = strokeWidth + 4.5f
    arrowOutlinePaint.color = outlineColor
    canvas.drawLine(sx, sy, shaftEndX, shaftEndY, arrowOutlinePaint)

    arrowShaftPaint.strokeWidth = strokeWidth
    arrowShaftPaint.color = fillColor
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

    arrowHeadOutlinePaint.strokeWidth = 3.5f
    arrowHeadOutlinePaint.color = outlineColor
    canvas.drawPath(arrowHeadPath, arrowHeadOutlinePaint)

    arrowHeadPaint.color = fillColor
    canvas.drawPath(arrowHeadPath, arrowHeadPaint)
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
}

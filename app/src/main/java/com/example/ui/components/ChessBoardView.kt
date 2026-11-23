package com.example.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chess.BoardState
import com.example.chess.ChessRules
import com.example.chess.EngineAnalysis
import com.example.chess.Move
import com.example.chess.PieceColor
import com.example.chess.PieceType
import com.example.chess.Square
import com.example.ui.theme.DarkSquareColor
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.LastMoveHighlight
import com.example.ui.theme.LightSquareColor
import com.example.ui.theme.SelectedSquareHighlight
import com.example.ui.theme.TacticalEmerald
import com.example.ui.theme.ThreatCrimson
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tanh

@Composable
fun EvalBar(
  evalCp: Int,
  mateIn: Int?,
  scoreDisplay: String,
  flipped: Boolean,
  modifier: Modifier = Modifier,
) {
  // Convert centipawns to [0.05..0.95] White advantage ratio
  val targetWhiteRatio =
    remember(evalCp, mateIn) {
      if (mateIn != null) {
        if (evalCp >= 0) 0.96f else 0.04f
      } else {
        (0.5f + 0.45f * tanh(evalCp / 420.0).toFloat()).coerceIn(0.05f, 0.95f)
      }
    }

  val animatedWhiteRatio by
    animateFloatAsState(
      targetValue = targetWhiteRatio,
      animationSpec = spring(dampingRatio = 0.8f, stiffness = 260f),
      label = "eval_bar_ratio",
    )

  val whiteColor = Color(0xFFF3F4F6)
  val blackColor = Color(0xFF1A2029)

  Column(
    modifier =
      modifier
        .width(28.dp)
        .clip(RoundedCornerShape(10.dp))
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
        .background(blackColor)
        .testTag("eval_bar"),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val whiteH = size.height * animatedWhiteRatio
        val blackH = size.height - whiteH
        if (!flipped) {
          drawRect(color = blackColor, topLeft = Offset.Zero, size = Size(size.width, blackH))
          drawRect(
            color = whiteColor,
            topLeft = Offset(0f, blackH),
            size = Size(size.width, whiteH),
          )
        } else {
          drawRect(color = whiteColor, topLeft = Offset.Zero, size = Size(size.width, whiteH))
          drawRect(
            color = blackColor,
            topLeft = Offset(0f, whiteH),
            size = Size(size.width, blackH),
          )
        }
      }
      Text(
        text = scoreDisplay,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
        color = if (evalCp >= 0) Color(0xFF0D1117) else Color(0xFFF0F6FC),
        modifier =
          Modifier.align(
              if ((evalCp >= 0 && !flipped) || (evalCp < 0 && flipped)) Alignment.BottomCenter
              else Alignment.TopCenter,
            )
            .padding(vertical = 4.dp),
      )
    }
  }
}

@Composable
fun ChessBoardView(
  board: BoardState,
  analysis: EngineAnalysis,
  lastMove: Move?,
  selectedSquare: Square?,
  legalDestinations: Set<Square>,
  flipped: Boolean,
  showSecondaryArrows: Boolean,
  showThreatArrow: Boolean,
  arrowOpacity: Float,
  onSquareTap: (Square) -> Unit,
  modifier: Modifier = Modifier,
) {
  val inCheckKingSquare =
    remember(board) {
      if (ChessRules.isInCheck(board, board.sideToMove)) {
        ChessRules.findKing(board, board.sideToMove)
      } else null
    }

  val whiteFillPaint = remember {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#FFFFFF")
      textAlign = Paint.Align.CENTER
      style = Paint.Style.FILL
      typeface = Typeface.DEFAULT_BOLD
    }
  }
  val whiteStrokePaint = remember {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#161B22")
      textAlign = Paint.Align.CENTER
      style = Paint.Style.STROKE
      strokeWidth = 3.2f
      typeface = Typeface.DEFAULT_BOLD
    }
  }
  val blackFillPaint = remember {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#11161C")
      textAlign = Paint.Align.CENTER
      style = Paint.Style.FILL
      typeface = Typeface.DEFAULT_BOLD
    }
  }
  val blackRimPaint = remember {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#CBD5E1")
      textAlign = Paint.Align.CENTER
      style = Paint.Style.STROKE
      strokeWidth = 1.6f
      typeface = Typeface.DEFAULT_BOLD
    }
  }
  val coordPaint = remember {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.MONOSPACE
      isFakeBoldText = true
    }
  }
  val badgeTextPaint = remember {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.parseColor("#0D1117")
      textAlign = Paint.Align.CENTER
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
  }

  val arrowHeadPath = remember { Path() }
  val threatDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(16f, 12f), 0f) }

  Box(
    modifier =
      modifier
        .aspectRatio(1f)
        .clip(RoundedCornerShape(14.dp))
        .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
        .testTag("chess_board_canvas"),
  ) {
    Canvas(
      modifier =
        Modifier.fillMaxSize().pointerInput(flipped) {
          detectTapGestures { offset ->
            val sqSize = size.width / 8f
            val col = (offset.x / sqSize).toInt().coerceIn(0, 7)
            val row = (offset.y / sqSize).toInt().coerceIn(0, 7)
            val file = if (flipped) 7 - col else col
            val rank = if (flipped) row else 7 - row
            onSquareTap(Square(file, rank))
          }
        },
    ) {
      val boardPx = size.minDimension
      val sqSize = boardPx / 8f

      // 1. Squares & coordinates
      for (rank in 0..7) {
        for (file in 0..7) {
          val sq = Square(file, rank)
          val tl = squareTopLeft(sq, sqSize, flipped)
          val baseColor = if (sq.isLight) LightSquareColor else DarkSquareColor
          drawRect(color = baseColor, topLeft = tl, size = Size(sqSize, sqSize))

          if (lastMove != null && (sq == lastMove.from || sq == lastMove.to)) {
            drawRect(color = LastMoveHighlight, topLeft = tl, size = Size(sqSize, sqSize))
          }
          if (sq == selectedSquare) {
            drawRect(color = SelectedSquareHighlight, topLeft = tl, size = Size(sqSize, sqSize))
          }
          if (sq == inCheckKingSquare) {
            drawCircle(
              brush =
                Brush.radialGradient(
                  colors = listOf(ThreatCrimson.copy(alpha = 0.85f), Color.Transparent),
                  center = Offset(tl.x + sqSize * 0.5f, tl.y + sqSize * 0.5f),
                  radius = sqSize * 0.55f,
                ),
              radius = sqSize * 0.55f,
              center = Offset(tl.x + sqSize * 0.5f, tl.y + sqSize * 0.5f),
            )
          }
        }
      }

      // Rank & file coordinate labels
      coordPaint.textSize = sqSize * 0.22f
      for (i in 0..7) {
        val fileIdx = if (flipped) 7 - i else i
        val rankIdx = if (flipped) i else 7 - i
        val fileSq = Square(fileIdx, if (flipped) 7 else 0)
        val rankSq = Square(if (flipped) 7 else 0, rankIdx)

        coordPaint.color =
          if (fileSq.isLight) DarkSquareColor.toArgb() else LightSquareColor.toArgb()
        drawContext.canvas.nativeCanvas.drawText(
          ('a' + fileIdx).toString(),
          (i + 0.84f) * sqSize,
          boardPx - sqSize * 0.1f,
          coordPaint,
        )

        coordPaint.color =
          if (rankSq.isLight) DarkSquareColor.toArgb() else LightSquareColor.toArgb()
        drawContext.canvas.nativeCanvas.drawText(
          ('1' + rankIdx).toString(),
          sqSize * 0.08f,
          i * sqSize + sqSize * 0.26f,
          coordPaint,
        )
      }

      // 2. Legal move indicators
      for (dest in legalDestinations) {
        val center = squareCenter(dest, sqSize, flipped)
        val hasEnemy = board.pieceAt(dest) != null
        if (hasEnemy) {
          drawCircle(
            color = TacticalEmerald.copy(alpha = 0.72f),
            radius = sqSize * 0.44f,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sqSize * 0.08f),
          )
        } else {
          drawCircle(
            color = Color(0x8810B981),
            radius = sqSize * 0.16f,
            center = center,
          )
        }
      }

      // 3. Chess pieces (using solid filled glyphs with crisp contrast rim)
      val glyphSize = sqSize * 0.76f
      whiteFillPaint.textSize = glyphSize
      whiteStrokePaint.textSize = glyphSize
      blackFillPaint.textSize = glyphSize
      blackRimPaint.textSize = glyphSize

      for (idx in 0 until 64) {
        val piece = board.squares[idx] ?: continue
        val sq = Square.fromIndex(idx)
        val center = squareCenter(sq, sqSize, flipped)
        val solidGlyph =
          when (piece.type) {
            PieceType.KING -> "♚"
            PieceType.QUEEN -> "♛"
            PieceType.ROOK -> "♜"
            PieceType.BISHOP -> "♝"
            PieceType.KNIGHT -> "♞"
            PieceType.PAWN -> "♟"
          }

        val yBaseline = center.y - (whiteFillPaint.descent() + whiteFillPaint.ascent()) / 2f
        val native = drawContext.canvas.nativeCanvas
        if (piece.color == PieceColor.WHITE) {
          native.drawText(solidGlyph, center.x, yBaseline, whiteStrokePaint)
          native.drawText(solidGlyph, center.x, yBaseline, whiteFillPaint)
        } else {
          native.drawText(solidGlyph, center.x, yBaseline, blackRimPaint)
          native.drawText(solidGlyph, center.x, yBaseline, blackFillPaint)
        }
      }

      // 4. Tactical Best-Move Arrows (Threat, #2, #1 on top)
      if (showThreatArrow && analysis.opponentThreat != null) {
        drawBoardArrow(
          move = analysis.opponentThreat.move,
          sqSize = sqSize,
          flipped = flipped,
          color = ThreatCrimson.copy(alpha = arrowOpacity * 0.92f),
          strokeWidth = sqSize * 0.11f,
          dashEffect = threatDashEffect,
          badge = "!",
          badgeTextPaint = badgeTextPaint,
          headPath = arrowHeadPath,
        )
      }

      if (showSecondaryArrows && analysis.topMoves.size > 1) {
        drawBoardArrow(
          move = analysis.topMoves[1].move,
          sqSize = sqSize,
          flipped = flipped,
          color = ElectricCyan.copy(alpha = arrowOpacity * 0.85f),
          strokeWidth = sqSize * 0.12f,
          dashEffect = null,
          badge = "2",
          badgeTextPaint = badgeTextPaint,
          headPath = arrowHeadPath,
        )
      }

      analysis.topMoves.firstOrNull()?.let { best ->
        drawBoardArrow(
          move = best.move,
          sqSize = sqSize,
          flipped = flipped,
          color = TacticalEmerald.copy(alpha = arrowOpacity),
          strokeWidth = sqSize * 0.16f,
          dashEffect = null,
          badge = "1",
          badgeTextPaint = badgeTextPaint,
          headPath = arrowHeadPath,
        )
      }
    }
  }
}

private fun squareTopLeft(sq: Square, sqSize: Float, flipped: Boolean): Offset {
  val col = if (flipped) 7 - sq.file else sq.file
  val row = if (flipped) sq.rank else 7 - sq.rank
  return Offset(col * sqSize, row * sqSize)
}

private fun squareCenter(sq: Square, sqSize: Float, flipped: Boolean): Offset {
  val tl = squareTopLeft(sq, sqSize, flipped)
  return Offset(tl.x + sqSize * 0.5f, tl.y + sqSize * 0.5f)
}

private fun DrawScope.drawBoardArrow(
  move: Move,
  sqSize: Float,
  flipped: Boolean,
  color: Color,
  strokeWidth: Float,
  dashEffect: PathEffect?,
  badge: String,
  badgeTextPaint: Paint,
  headPath: Path,
) {
  val start = squareCenter(move.from, sqSize, flipped)
  val end = squareCenter(move.to, sqSize, flipped)
  val dx = end.x - start.x
  val dy = end.y - start.y
  val dist = hypot(dx, dy)
  if (dist < 1f) return

  val angle = atan2(dy, dx)
  val headLen = sqSize * 0.36f
  val headAngle = Math.toRadians(30.0)
  val trimStart = sqSize * 0.14f

  val sx = start.x + cos(angle) * trimStart
  val sy = start.y + sin(angle) * trimStart
  val shaftEndX = end.x - cos(angle) * (headLen * 0.68f)
  val shaftEndY = end.y - sin(angle) * (headLen * 0.68f)

  // Contrast dark halo
  drawLine(
    color = Color(0x660D1117),
    start = Offset(sx, sy),
    end = Offset(shaftEndX, shaftEndY),
    strokeWidth = strokeWidth + 5f,
    cap = StrokeCap.Round,
  )

  drawLine(
    color = color,
    start = Offset(sx, sy),
    end = Offset(shaftEndX, shaftEndY),
    strokeWidth = strokeWidth,
    cap = StrokeCap.Round,
    pathEffect = dashEffect,
  )

  headPath.reset()
  headPath.moveTo(end.x, end.y)
  headPath.lineTo(
    (end.x - headLen * cos(angle - headAngle)).toFloat(),
    (end.y - headLen * sin(angle - headAngle)).toFloat(),
  )
  headPath.lineTo(
    (end.x - headLen * cos(angle + headAngle)).toFloat(),
    (end.y - headLen * sin(angle + headAngle)).toFloat(),
  )
  headPath.close()
  drawPath(path = headPath, color = color)

  val badgeRadius = sqSize * 0.17f
  drawCircle(color = color, radius = badgeRadius, center = start)
  badgeTextPaint.textSize = badgeRadius * 1.25f
  val textY = start.y - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
  drawContext.canvas.nativeCanvas.drawText(badge, start.x, textY, badgeTextPaint)
}

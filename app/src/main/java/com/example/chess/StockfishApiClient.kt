package com.example.chess

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

data class StockfishOnlineResponse(
  val success: Boolean = false,
  val evaluation: Double? = null,
  val mate: Int? = null,
  val bestmove: String? = null,
  val continuation: String? = null,
  val data: String? = null,
)

interface StockfishOnlineService {
  @GET("api/s/v2.php")
  suspend fun evaluatePosition(
    @Query("fen") fen: String,
    @Query("depth") depth: Int,
  ): StockfishOnlineResponse
}

data class StockfishQueryResult(
  val bestMove: Move,
  val san: String,
  val evalCp: Int,
  val mateIn: Int?,
  val scoreDisplay: String,
  val pvLine: List<String>,
  val depth: Int,
  val tacticalTag: String,
)

object StockfishApiClient {

  private const val BASE_URL = "https://stockfish.online/"

  private val moshi: Moshi by lazy {
    Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
  }

  private val okHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(4, TimeUnit.SECONDS)
      .readTimeout(6, TimeUnit.SECONDS)
      .writeTimeout(4, TimeUnit.SECONDS)
      .build()
  }

  private val service: StockfishOnlineService by lazy {
    Retrofit.Builder()
      .baseUrl(BASE_URL)
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(StockfishOnlineService::class.java)
  }

  suspend fun fetchBestMove(board: BoardState, depth: Int): StockfishQueryResult? {
    val clampedDepth = depth.coerceIn(1, 15)
    val fen = board.toFen()
    val response =
      runCatching { service.evaluatePosition(fen = fen, depth = clampedDepth) }.getOrNull()
        ?: return null

    if (!response.success) return null

    val rawBest = response.bestmove ?: return null
    val uciToken = parseUciFromBestMove(rawBest) ?: return null
    val legal = ChessRules.generateLegalMoves(board)
    val matchedMove = legal.find { it.uci.equals(uciToken, ignoreCase = true) } ?: return null
    val san = ChessRules.toSan(board, matchedMove, legal)

    val mateVal = response.mate
    val evalCp =
      when {
        mateVal != null && mateVal > 0 -> 50_000 - mateVal * 10
        mateVal != null && mateVal < 0 -> -50_000 - mateVal * 10
        response.evaluation != null -> (response.evaluation * 100.0).roundToInt()
        else -> 0
      }

    val scoreDisplay =
      when {
        mateVal != null && mateVal > 0 -> "#M$mateVal"
        mateVal != null && mateVal < 0 -> "-#M${-mateVal}"
        mateVal == 0 -> if (board.sideToMove == PieceColor.WHITE) "0-1" else "1-0"
        else -> {
          val pawns = evalCp / 100.0
          if (pawns >= 0) String.format("+%.1f", pawns) else String.format("%.1f", pawns)
        }
      }

    val pvSan = convertContinuationToSan(board, response.continuation, san)
    val tag = classifyStockfishMove(board, matchedMove, san, mateVal)

    return StockfishQueryResult(
      bestMove = matchedMove,
      san = san,
      evalCp = evalCp,
      mateIn = mateVal?.takeIf { it != 0 }?.let { kotlin.math.abs(it) },
      scoreDisplay = scoreDisplay,
      pvLine = pvSan,
      depth = clampedDepth,
      tacticalTag = tag,
    )
  }

  internal fun parseUciFromBestMove(bestmoveField: String): String? {
    // Expected format: "bestmove b7b6 ponder f3e5" or "bestmove e2e4"
    val tokens = bestmoveField.trim().split(Regex("\\s+"))
    val idx = tokens.indexOf("bestmove")
    val candidate =
      if (idx >= 0 && idx + 1 < tokens.size) tokens[idx + 1]
      else tokens.firstOrNull()
    return candidate?.takeIf { it != "(none)" && it.length >= 4 }
  }

  private fun convertContinuationToSan(
    startBoard: BoardState,
    continuation: String?,
    fallbackFirstSan: String,
  ): List<String> {
    if (continuation.isNullOrBlank()) return listOf(fallbackFirstSan)
    val ucis = continuation.trim().split(Regex("\\s+")).take(5)
    var curr = startBoard
    val sanList = mutableListOf<String>()
    for (uci in ucis) {
      val legal = ChessRules.generateLegalMoves(curr)
      val m = legal.find { it.uci.equals(uci, ignoreCase = true) } ?: break
      sanList.add(ChessRules.toSan(curr, m, legal))
      curr = ChessRules.applyMove(curr, m)
    }
    return if (sanList.isEmpty()) listOf(fallbackFirstSan) else sanList
  }

  private fun classifyStockfishMove(
    board: BoardState,
    move: Move,
    san: String,
    mateVal: Int?,
  ): String {
    if (san.endsWith('#') || (mateVal != null && mateVal != 0)) return "Stockfish Mate"
    if (san.endsWith('+')) return "Stockfish Check"
    if (move.isCastle) return "King Safety"
    if (move.promotion != null) return "Promotion"
    val captured = move.captured
    val moving = board.pieceAt(move.from)
    if (captured != null && moving != null) {
      return if (captured.type.valueCp > moving.type.valueCp) "Wins Material" else "Tactical Capture"
    }
    return "Stockfish Best"
  }
}

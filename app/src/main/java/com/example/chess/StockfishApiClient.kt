package com.example.chess

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlin.math.abs
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

data class LichessPvItem(
  val moves: String = "",
  val cp: Int? = null,
  val mate: Int? = null,
)

data class LichessCloudEvalResponse(
  val fen: String? = null,
  val depth: Int = 0,
  val pvs: List<LichessPvItem> = emptyList(),
)

interface StockfishOnlineService {
  @GET("api/s/v2.php")
  suspend fun evaluatePosition(
    @Query("fen") fen: String,
    @Query("depth") depth: Int,
  ): StockfishOnlineResponse
}

interface LichessCloudService {
  @GET("api/cloud-eval")
  suspend fun cloudEval(
    @Query("fen") fen: String,
    @Query("multiPv") multiPv: Int = 3,
  ): LichessCloudEvalResponse
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
  val sourceLabel: String = "Stockfish",
  val extraTopMoves: List<SuggestedMove> = emptyList(),
)

object StockfishApiClient {

  private const val STOCKFISH_ONLINE_URL = "https://stockfish.online/"
  private const val LICHESS_URL = "https://lichess.org/"

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

  private val stockfishOnlineService: StockfishOnlineService by lazy {
    Retrofit.Builder()
      .baseUrl(STOCKFISH_ONLINE_URL)
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(StockfishOnlineService::class.java)
  }

  private val lichessCloudService: LichessCloudService by lazy {
    Retrofit.Builder()
      .baseUrl(LICHESS_URL)
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(LichessCloudService::class.java)
  }

  suspend fun fetchBestMove(board: BoardState, depth: Int): StockfishQueryResult? {
    val clampedDepth = depth.coerceIn(1, 15)
    val fen = board.toFen()
    val legal = ChessRules.generateLegalMoves(board)
    if (legal.isEmpty()) return null

    // 1. Primary: Stockfish Online REST API at requested Elo-mapped depth (1..15)
    val sfOnline =
      runCatching { stockfishOnlineService.evaluatePosition(fen = fen, depth = clampedDepth) }
        .getOrNull()

    if (sfOnline != null && sfOnline.success && !sfOnline.bestmove.isNullOrBlank()) {
      val parsed = parseStockfishOnline(board, legal, sfOnline, clampedDepth)
      if (parsed != null) return parsed
    }

    // 2. Secondary: Lichess Stockfish Cloud Eval (MultiPV 3)
    val lichessResp =
      runCatching { lichessCloudService.cloudEval(fen = fen, multiPv = 3) }.getOrNull()
    if (lichessResp != null && lichessResp.pvs.isNotEmpty()) {
      val parsedLichess = parseLichessCloud(board, legal, lichessResp)
      if (parsedLichess != null) return parsedLichess
    }

    return null
  }

  private fun parseStockfishOnline(
    board: BoardState,
    legal: List<Move>,
    response: StockfishOnlineResponse,
    depth: Int,
  ): StockfishQueryResult? {
    val rawBest = response.bestmove ?: return null
    val uciToken = parseUciFromBestMove(rawBest) ?: return null
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

    val scoreDisplay = formatCloudScore(board, evalCp, mateVal)
    val pvSan = convertContinuationToSan(board, response.continuation, san)
    val tag = classifyStockfishMove(board, matchedMove, san, mateVal)

    return StockfishQueryResult(
      bestMove = matchedMove,
      san = san,
      evalCp = evalCp,
      mateIn = mateVal?.takeIf { it != 0 }?.let { abs(it) },
      scoreDisplay = scoreDisplay,
      pvLine = pvSan,
      depth = depth,
      tacticalTag = tag,
      sourceLabel = "Stockfish D$depth",
    )
  }

  private fun parseLichessCloud(
    board: BoardState,
    legal: List<Move>,
    response: LichessCloudEvalResponse,
  ): StockfishQueryResult? {
    val parsedPvs =
      response.pvs.mapIndexedNotNull { idx, pv ->
        val tokens = pv.moves.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val firstUci = tokens.firstOrNull() ?: return@mapIndexedNotNull null
        val matched = legal.find { it.uci.equals(firstUci, ignoreCase = true) }
          ?: return@mapIndexedNotNull null
        val san = ChessRules.toSan(board, matched, legal)
        val mateVal = pv.mate
        val evalCp =
          when {
            mateVal != null && mateVal > 0 -> 50_000 - mateVal * 10
            mateVal != null && mateVal < 0 -> -50_000 - mateVal * 10
            pv.cp != null -> pv.cp
            else -> 0
          }
        val scoreDisplay = formatCloudScore(board, evalCp, mateVal)
        val pvSan = convertContinuationToSan(board, pv.moves, san)
        val tag = classifyStockfishMove(board, matched, san, mateVal)
        SuggestedMove(
          rank = idx + 1,
          move = matched,
          san = san,
          scoreCp = evalCp,
          mateIn = mateVal?.takeIf { it != 0 }?.let { abs(it) },
          scoreDisplay = scoreDisplay,
          pvLine = pvSan,
          tacticalTag = tag,
        )
      }

    val first = parsedPvs.firstOrNull() ?: return null
    val depth = response.depth.coerceAtLeast(15)
    return StockfishQueryResult(
      bestMove = first.move,
      san = first.san,
      evalCp = first.scoreCp,
      mateIn = first.mateIn,
      scoreDisplay = first.scoreDisplay,
      pvLine = first.pvLine,
      depth = depth,
      tacticalTag = first.tacticalTag,
      sourceLabel = "Stockfish Cloud D$depth",
      extraTopMoves = parsedPvs.drop(1),
    )
  }

  private fun formatCloudScore(board: BoardState, evalCp: Int, mateVal: Int?): String =
    when {
      mateVal != null && mateVal > 0 -> "#M$mateVal"
      mateVal != null && mateVal < 0 -> "-#M${-mateVal}"
      mateVal == 0 -> if (board.sideToMove == PieceColor.WHITE) "0-1" else "1-0"
      else -> {
        val pawns = evalCp / 100.0
        if (pawns >= 0) String.format("+%.1f", pawns) else String.format("%.1f", pawns)
      }
    }

  internal fun parseUciFromBestMove(bestmoveField: String): String? {
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

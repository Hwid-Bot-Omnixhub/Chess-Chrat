package com.example.chessreviewer

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** cp / mate are from the point of view of the side to move (UCI convention). */
class EngineResult(val cp: Int?, val mate: Int?, val best: String)

/**
 * Stockfish strength, shared by the overlay panel and the manual analyzer in MainActivity.
 * Stored in the same "chess" preferences the rest of the app uses. A null Elo means full
 * strength; a number means Stockfish aims for that Elo (via UCI_Elo down to its floor,
 * then via the universal Skill Level option below it).
 */
object EngineSettings {
    // Above UCI_FLOOR the engine gets its own UCI_LimitStrength / UCI_Elo pair (SF 16 and
    // newer: 1320 .. 3190). Below that floor it gets the universal Skill Level option,
    // which every Stockfish build understands, so the range reaches beginner territory.
    const val MIN_ELO = 600
    const val MAX_ELO = 3190
    const val STEP = 10
    const val UCI_FLOOR = 1320

    fun elo(context: Context): Int? =
        context.getSharedPreferences("chess", Context.MODE_PRIVATE)
            .getString("elo", "")?.toIntOrNull()?.coerceIn(MIN_ELO, MAX_ELO)

    fun save(context: Context, value: Int?) {
        context.getSharedPreferences("chess", Context.MODE_PRIVATE)
            .edit().putString("elo", value?.toString() ?: "").apply()
    }

    fun label(value: Int?): String =
        if (value == null) "Full strength"
        else if (value < UCI_FLOOR) "Beginner (about $value Elo)"
        else "About $value Elo"

    /** The Elo a slider position means, or null when full strength is on. */
    fun targetElo(fullStrength: Boolean, progress: Int): Int? {
        if (fullStrength) return null
        val maxStep = (MAX_ELO - MIN_ELO) / STEP
        return MIN_ELO + progress.coerceIn(0, maxStep) * STEP
    }
}

class StockfishEngine(private val context: Context) {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    /** the Elo value the running engine process was last told about (null = full strength) */
    private var appliedElo: Int? = null

    // What this particular Stockfish binary supports, learned from its "uci" answer.
    private var optEloSupported = false
    private var optEloMin = EngineSettings.UCI_FLOOR
    private var optEloMax = EngineSettings.MAX_ELO
    private var optLimitSupported = false
    private var optSkillSupported = false
    private var optSkillMin = 0
    private var optSkillMax = 20

    private fun binary() = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")

    fun available(): Boolean = binary().exists()

    @Synchronized
    fun start(): Boolean {
        stop()
        val bin = binary()
        if (!bin.exists()) return false
        return try {
            val p = ProcessBuilder(bin.absolutePath).redirectErrorStream(true).start()
            process = p
            reader = BufferedReader(InputStreamReader(p.inputStream))
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            send("uci")
            if (!readOptions()) return false
            send("setoption name Threads value 2")
            send("setoption name Hash value 64")
            applyEloOptions(EngineSettings.elo(context))
            send("isready")
            waitFor("readyok")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Changes the engine's target strength. Persists the choice so both the overlay and the
     * manual analyzer pick it up, and applies it to a running engine straight away.
     */
    @Synchronized
    fun setElo(value: Int?) {
        val v = value?.coerceIn(EngineSettings.MIN_ELO, EngineSettings.MAX_ELO)
        EngineSettings.save(context, v)
        applyEloOptions(v)
    }

    fun elo(): Int? = EngineSettings.elo(context)

    /** Sends the strength options; only touches the engine when the value changed. */
    private fun applyEloOptions(value: Int?) {
        if (writer == null) {
            // no engine process to talk to; the next start() applies the persisted value
            appliedElo = null
            return
        }
        if (appliedElo == value) return
        if (value == null) {
            if (optLimitSupported) send("setoption name UCI_LimitStrength value false")
            if (optSkillSupported) send("setoption name Skill Level value $optSkillMax")
        } else if (optEloSupported && value >= eloFloor()) {
            // Stockfish's own handicap, accurate where its UCI_Elo reaches
            if (optSkillSupported) send("setoption name Skill Level value $optSkillMax")
            if (optLimitSupported) send("setoption name UCI_LimitStrength value true")
            send("setoption name UCI_Elo value ${value.coerceIn(optEloMin, optEloMax)}")
        } else if (optSkillSupported) {
            // below UCI_Elo's floor, or a build without UCI_Elo: Skill Level covers it
            if (optLimitSupported) send("setoption name UCI_LimitStrength value false")
            send("setoption name Skill Level value ${skillFor(value)}")
        }
        appliedElo = value
    }

    /** The lowest Elo this engine's UCI_Elo option can aim for. */
    private fun eloFloor(): Int =
        if (optEloSupported) optEloMin else EngineSettings.UCI_FLOOR

    /** Maps an Elo below UCI_Elo's floor onto the Skill Level scale (600 -> weakest, 1320 -> full). */
    private fun skillFor(elo: Int): Int {
        val span = EngineSettings.UCI_FLOOR - EngineSettings.MIN_ELO
        return (((elo - EngineSettings.MIN_ELO).toDouble() / span) * (optSkillMax - optSkillMin))
            .toInt().coerceIn(optSkillMin, optSkillMax)
    }

    /** Think time for weaker play. Any build with a strength option handles the weakening
     *  itself; an ancient binary with neither UCI_Elo nor Skill Level can only be weakened
     *  by thinking for less time. */
    private fun effectiveMovetime(baseMs: Int, value: Int?): Int {
        if (value == null || (optEloSupported && value >= eloFloor()) || optSkillSupported) return baseMs
        val frac = (value - EngineSettings.MIN_ELO).toFloat() /
            (EngineSettings.UCI_FLOOR - EngineSettings.MIN_ELO)
        return (baseMs * (0.3f + 0.7f * frac)).toInt().coerceAtLeast(50)
    }

    /** Reads the "option name ..." lines up to uciok and records what the binary supports. */
    private fun readOptions(): Boolean {
        optEloSupported = false
        optLimitSupported = false
        optSkillSupported = false
        optEloMin = EngineSettings.UCI_FLOOR
        optEloMax = EngineSettings.MAX_ELO
        optSkillMin = 0
        optSkillMax = 20
        while (true) {
            val line = reader?.readLine() ?: return false
            if (line.startsWith("uciok")) return true
            if (!line.startsWith("option name ")) continue
            val body = line.removePrefix("option name ")
            val t = body.indexOf(" type ")
            if (t <= 0) continue
            val name = body.substring(0, t)
            val rest = body.substring(t + 1)
            val min = Regex("min (-?\\d+)").find(rest)?.groupValues?.get(1)?.toIntOrNull()
            val max = Regex("max (-?\\d+)").find(rest)?.groupValues?.get(1)?.toIntOrNull()
            when (name) {
                "UCI_Elo" -> {
                    optEloSupported = true
                    min?.let { optEloMin = it }
                    max?.let { optEloMax = it }
                }
                "UCI_LimitStrength" -> optLimitSupported = true
                "Skill Level" -> {
                    optSkillSupported = true
                    min?.let { optSkillMin = it }
                    max?.let { optSkillMax = it }
                }
            }
        }
    }

    private fun send(cmd: String) {
        try {
            writer?.write(cmd + "\n")
            writer?.flush()
        } catch (e: Exception) {
        }
    }

    private fun waitFor(token: String): Boolean {
        var line = reader?.readLine()
        while (line != null) {
            if (line.startsWith(token)) return true
            line = reader?.readLine()
        }
        return false
    }

    fun analyze(fen: String, moveTimeMs: Int = 400): EngineResult? =
        analyzeRaw("position fen $fen", moveTimeMs)

    /** positionCommand is a full UCI line, e.g. "position fen ..." or "position startpos moves e2e4 e7e5" */
    @Synchronized
    fun analyzeRaw(positionCommand: String, moveTimeMs: Int = 400): EngineResult? {
        if (process?.isAlive != true && !start()) return null
        send(positionCommand)
        send("go movetime ${effectiveMovetime(moveTimeMs, appliedElo ?: EngineSettings.elo(context))}")

        var cp: Int? = null
        var mate: Int? = null
        var line = reader?.readLine()
        while (line != null) {
            if (line.contains(" score cp ")) {
                cp = line.substringAfter(" score cp ").substringBefore(" ").toIntOrNull()
                mate = null
            } else if (line.contains(" score mate ")) {
                mate = line.substringAfter(" score mate ").substringBefore(" ").toIntOrNull()
                cp = null
            }
            if (line.startsWith("bestmove")) {
                val parts = line.split(" ")
                return EngineResult(cp, mate, parts.getOrElse(1) { "" })
            }
            line = reader?.readLine()
        }
        stop() // engine died (e.g. illegal position); it restarts on the next call
        return null
    }

    @Synchronized
    fun stop() {
        send("quit")
        try { process?.destroy() } catch (e: Exception) { }
        process = null
        reader = null
        writer = null
        appliedElo = null // a restarted process knows no options until they are sent again
    }
}

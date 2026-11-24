package com.example.chessreviewer

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** cp / mate are from the point of view of the side to move (UCI convention). */
class EngineResult(val cp: Int?, val mate: Int?, val best: String)

object EngineSettings {
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

    private var appliedElo: Int? = null

    private var optEloSupported = false
    private var optEloMin = EngineSettings.UCI_FLOOR
    private var optEloMax = EngineSettings.MAX_ELO
    private var optLimitSupported = false
    private var optSkillSupported = false
    private var optSkillMin = 0
    private var optSkillMax = 20

    private fun binary() = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")

    fun available(): Boolean = binary().let { it.exists() && it.canExecute() }

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
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun setElo(value: Int?) {
        val v = value?.coerceIn(EngineSettings.MIN_ELO, EngineSettings.MAX_ELO)
        EngineSettings.save(context, v)
        applyEloOptions(v)
    }

    fun elo(): Int? = EngineSettings.elo(context)

    private fun applyEloOptions(value: Int?) {
        if (writer == null) {
            appliedElo = null
            return
        }
        if (appliedElo == value) return
        if (value == null) {
            if (optLimitSupported) send("setoption name UCI_LimitStrength value false")
            if (optSkillSupported) send("setoption name Skill Level value $optSkillMax")
        } else if (optEloSupported && value >= eloFloor()) {
            if (optSkillSupported) send("setoption name Skill Level value $optSkillMax")
            if (optLimitSupported) send("setoption name UCI_LimitStrength value true")
            send("setoption name UCI_Elo value ${value.coerceIn(optEloMin, optEloMax)}")
        } else if (optSkillSupported) {
            if (optLimitSupported) send("setoption name UCI_LimitStrength value false")
            send("setoption name Skill Level value ${skillFor(value)}")
        }
        appliedElo = value
    }

    private fun eloFloor(): Int =
        if (optEloSupported) optEloMin else EngineSettings.UCI_FLOOR

    private fun skillFor(elo: Int): Int {
        val span = EngineSettings.UCI_FLOOR - EngineSettings.MIN_ELO
        return (((elo - EngineSettings.MIN_ELO).toDouble() / span) * (optSkillMax - optSkillMin))
            .toInt().coerceIn(optSkillMin, optSkillMax)
    }

    private fun effectiveMovetime(baseMs: Int, value: Int?): Int {
        if (value == null || (optEloSupported && value >= eloFloor()) || optSkillSupported) return baseMs
        val frac = (value - EngineSettings.MIN_ELO).toFloat() /
            (EngineSettings.UCI_FLOOR - EngineSettings.MIN_ELO)
        return (baseMs * (0.3f + 0.7f * frac)).toInt().coerceAtLeast(50)
    }

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
        } catch (_: Exception) {
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
        stop()
        return null
    }

    @Synchronized
    fun stop() {
        send("quit")
        try { process?.destroy() } catch (_: Exception) { }
        process = null
        reader = null
        writer = null
        appliedElo = null
    }
}

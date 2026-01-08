/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities

/**
 * Helper class to track timing benchmarks during call connection.
 * Used to identify performance bottlenecks in the call setup process.
 * All benchmarks are collected and logged together when the call connects.
 *
 * This class is thread-safe and uses synchronized access to internal state.
 */
object CallTimingBenchmark {
    private const val MILESTONE_PAD_LENGTH = 35
    private const val TIME_PAD_LENGTH = 6
    private const val DELTA_PAD_LENGTH = 10

    private var startTime: Long = 0L
    private val milestones = mutableMapOf<String, Long>()
    private var isFirstCandidate = true
    private var isOutbound = false
    private var isRunning = false
    private var hasEnded = false

    private val lock = Any()

    /**
     * Starts the benchmark timer.
     * @param isOutbound indicates if this is an outbound call (true) or inbound (false).
     */
    fun start(isOutbound: Boolean = false) {
        synchronized(lock) {
            startTime = System.currentTimeMillis()
            milestones.clear()
            isFirstCandidate = true
            this.isOutbound = isOutbound
            isRunning = true
            hasEnded = false
            Logger.d(
                tag = "CallTimingBenchmark",
                message = "Benchmark started for ${if (isOutbound) "OUTBOUND" else "INBOUND"} call"
            )
        }
    }

    /**
     * Records a milestone with the current elapsed time.
     * @param milestone the name of the milestone to record
     */
    fun mark(milestone: String) {
        synchronized(lock) {
            if (!isRunning) return
            val elapsed = System.currentTimeMillis() - startTime
            milestones[milestone] = elapsed
            Logger.d(
                tag = "CallTimingBenchmark",
                message = "Milestone marked: $milestone at ${elapsed}ms"
            )
        }
    }

    /**
     * Records the first ICE candidate (only once per call).
     */
    fun markFirstCandidate() {
        synchronized(lock) {
            if (!isRunning) return
            if (isFirstCandidate) {
                isFirstCandidate = false
                mark("first_ice_candidate")
            }
        }
    }

    /**
     * Ends the benchmark and logs a formatted summary of all milestones.
     * This method is idempotent - it will only execute once per call.
     */
    fun end() {
        synchronized(lock) {
            if (!isRunning || hasEnded) return
            hasEnded = true
            isRunning = false

            val total = System.currentTimeMillis() - startTime
            val direction = if (isOutbound) "OUTBOUND" else "INBOUND"

            val buffer = StringBuilder()
            buffer.appendLine()
            buffer.appendLine("╔══════════════════════════════════════════════════════════╗")
            buffer.appendLine("║       $direction CALL CONNECTION BENCHMARK RESULTS       ║")
            buffer.appendLine("╠══════════════════════════════════════════════════════════╣")

            // Sort milestones by time for chronological display
            val sortedEntries = milestones.entries.sortedBy { it.value }

            var previousTime: Long? = null
            for (entry in sortedEntries) {
                val delta = if (previousTime != null) entry.value - previousTime else entry.value
                val deltaStr = if (previousTime != null) "(+${delta}ms)" else ""
                val milestonePadded = entry.key.padEnd(MILESTONE_PAD_LENGTH)
                val timePadded = "${entry.value}ms".padStart(TIME_PAD_LENGTH)
                val deltaPadded = deltaStr.padStart(DELTA_PAD_LENGTH)
                buffer.appendLine("║  $milestonePadded $timePadded $deltaPadded ║")
                previousTime = entry.value
            }

            buffer.appendLine("╠══════════════════════════════════════════════════════════╣")
            buffer.appendLine("║  TOTAL CONNECTION TIME:              ${total.toString().padStart(TIME_PAD_LENGTH)}ms            ║")
            buffer.appendLine("╚══════════════════════════════════════════════════════════╝")

            Logger.i(tag = "CallTimingBenchmark", message = buffer.toString())
        }
    }

    /**
     * Checks if the benchmark is currently running.
     * @return true if the benchmark is running, false otherwise
     */
    fun isRunning(): Boolean {
        synchronized(lock) {
            return isRunning
        }
    }

    /**
     * Resets the benchmark state without logging.
     * Useful for cleanup when a call fails before connecting.
     */
    fun reset() {
        synchronized(lock) {
            startTime = 0L
            milestones.clear()
            isFirstCandidate = true
            isOutbound = false
            isRunning = false
            hasEnded = false
        }
    }
}

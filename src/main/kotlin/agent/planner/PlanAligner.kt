package agent.planner

import agent.ActionPlan
import agent.PlanStep
import agent.StepType
import kotlin.math.max

object PlanAligner {
    private data class Cell(val score: Int, val prevI: Int, val prevJ: Int, val op: Op)
    private enum class Op { MATCH, INSERT_U, DELETE_G }

    fun align(graph: ActionPlan?, user: ActionPlan): ActionPlan {
        val g = graph?.steps.orEmpty()
        val u = user.steps
        if (g.isEmpty()) return re(user.title, ensureCriticalInputs(u, u))

        val m = g.size
        val n = u.size
        if (n == 0) return re(graph?.title ?: user.title, g)

        val dp = Array(m + 1) { Array(n + 1) { Cell(0, 0, 0, Op.MATCH) } }

        val matchScoreExact = 2
        val matchScoreType = 1
        val mismatch = -1
        val gap = -2

        fun s(i: Int, j: Int): Int {
            val gs = g[i - 1]
            val us = u[j - 1]
            val gh = norm(gs.targetHint)
            val uh = norm(us.targetHint)
            // bias toward aligning user INPUT with nearby graph steps even if hints differ
            return when {
                us.type == StepType.INPUT_TEXT && gs.type == StepType.TAP -> matchScoreType
                gs.type == us.type && gh == uh -> matchScoreExact
                gs.type == us.type -> matchScoreType
                else -> mismatch
            }
        }

        for (i in 1..m) dp[i][0] = Cell(i * gap, i - 1, 0, Op.DELETE_G)
        for (j in 1..n) dp[0][j] = Cell(j * gap, 0, j - 1, Op.INSERT_U)

        for (i in 1..m) {
            for (j in 1..n) {
                val diag = dp[i - 1][j - 1].score + s(i, j)
                val up = dp[i - 1][j].score + gap
                val left = dp[i][j - 1].score + gap
                val best = max(diag, max(up, left))
                dp[i][j] = when (best) {
                    diag -> Cell(best, i - 1, j - 1, Op.MATCH)
                    up -> Cell(best, i - 1, j, Op.DELETE_G)
                    else -> Cell(best, i, j - 1, Op.INSERT_U)
                }
            }
        }

        val out = ArrayList<PlanStep>()
        val seen = LinkedHashSet<String>()
        fun key(t: PlanStep) = t.type.name + "|" + norm(t.targetHint)

        fun add(step: PlanStep) {
            val k = key(step)
            if (!seen.contains(k)) {
                out += step
                seen += k
            }
        }

        var i = m
        var j = n
        while (i > 0 || j > 0) {
            val c = dp[i][j]
            when (c.op) {
                Op.MATCH -> {
                    val gs = if (i > 0) g[i - 1] else null
                    val us = if (j > 0) u[j - 1] else null
                    val merged = merge(gs, us)
                    add(merged)
                    i = c.prevI; j = c.prevJ
                }

                Op.DELETE_G -> {
                    val gs = g[i - 1]
                    if (isHarmlessNav(gs)) add(gs)
                    i = c.prevI; j = c.prevJ
                }

                Op.INSERT_U -> {
                    val us = u[j - 1]
                    add(us)
                    i = c.prevI; j = c.prevJ
                }
            }
        }
        out.reverse()

        val ensured = ensureCriticalInputs(out, u)
        return re(user.title.ifBlank { graph?.title ?: "AutoRun" }, ensured)
    }

    private fun merge(g: PlanStep?, u: PlanStep?): PlanStep {
        if (g == null && u != null) return u
        if (u == null && g != null) return g
        g!!
        u!!
        return when {
            u.type == StepType.INPUT_TEXT -> u
            u.type == StepType.WAIT_TEXT && g.type != StepType.WAIT_TEXT -> u
            u.type == StepType.ASSERT_TEXT && g.type == StepType.WAIT_TEXT -> u
            else -> {
                val gh = norm(g.targetHint)
                val uh = norm(u.targetHint)
                if (uh.isNotEmpty() && uh != gh) g.copy(targetHint = u.targetHint) else g
            }
        }
    }

    private fun isHarmlessNav(s: PlanStep): Boolean =
        s.type == StepType.TAP || s.type.name == "SCROLL" || s.type.name == "NAV"

    private fun ensureCriticalInputs(out0: List<PlanStep>, user: List<PlanStep>): List<PlanStep> {
        val out = out0.toMutableList()
        val have = out.associateBy { it.type.name + "|" + norm(it.targetHint) }.toMutableMap()

        fun present(p: PlanStep) = have.containsKey(p.type.name + "|" + norm(p.targetHint))

        val criticalInputs = user.filter { it.type == StepType.INPUT_TEXT }
        if (criticalInputs.isEmpty()) return out.mapIndexed { i, p -> p.copy(index = i + 1) }

        val idxLoginTap =
            out.indexOfFirst { it.type == StepType.TAP && norm(it.targetHint) in setOf("login", "sign-in", "signin") }
        val idxFirstTap = out.indexOfFirst { it.type == StepType.TAP }
        val insertAt = when {
            idxLoginTap >= 0 -> idxLoginTap
            idxFirstTap >= 0 -> idxFirstTap
            else -> 0
        }

        for (u in criticalInputs) {
            if (!present(u)) {
                out.add(insertAt.coerceAtMost(out.size), u)
                // refresh keys so multiple inserts do not duplicate
                have[u.type.name + "|" + norm(u.targetHint)] = u
            }
        }
        return out.mapIndexed { i, p -> p.copy(index = i + 1) }
    }

    private fun norm(s: String?): String =
        (s ?: "").lowercase().trim().replace(Regex("\\s+"), "-")

    private fun re(title: String, steps: List<PlanStep>): ActionPlan =
        ActionPlan(title = title, steps = steps.mapIndexed { i, p -> p.copy(index = i + 1) })
}

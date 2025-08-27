package agent.llm

import agent.Locator
import agent.Strategy
import agent.candidates.UICandidate
import io.appium.java_client.AppiumBy
import io.appium.java_client.android.AndroidDriver

data class Selection(val candidateId: String?, val scope: String?, val op: String)

class MultiAgentSelector(
    private val driver: AndroidDriver,
    private val disambiguator: LlmDisambiguator?
) {
    fun select(instruction: String, candidates: List<UICandidate>): Selection {
        val scoped = candidates.map { it to scopeFor(it) }
        val enriched = scoped.map { (c, s) ->
            LlmUiCandidate(
                id = c.id,
                label = listOfNotNull(s?.uppercase(), c.label).joinToString("::"),
                role = c.role,
                clickable = c.clickable,
                containerId = c.containerId
            )
        }
        val scope = inferScope(instruction)
        val op = inferOp(instruction)
        val pick = disambiguator?.decide(instruction, enriched, LlmScreenContext(screenTitle = null, activity = runCatching { driver.currentActivity() }.getOrNull()))
        val id = if (!pick.isNullOrBlank()) pick else {
            val pool = if (scope != null) scoped.filter { it.second.equals(scope, true) }.map { it.first } else candidates
            pool.firstOrNull()?.id
        }
        return Selection(candidateId = id, scope = scope, op = op)
    }

    private fun inferScope(text: String): String? {
        val t = text.lowercase()
        return when {
            Regex("\\bfor\\s+from\\b|\\bin\\s+from\\b|\\bfrom\\b").containsMatchIn(t) -> "from"
            Regex("\\bfor\\s+to\\b|\\bin\\s+to\\b|\\bto\\b").containsMatchIn(t) -> "to"
            else -> null
        }
    }

    private fun inferOp(text: String): String {
        val t = text.lowercase()
        return when {
            Regex("\\btoggle\\s+on\\b|\\bturn\\s+on\\b|\\benable\\b").containsMatchIn(t) -> "toggle_on"
            Regex("\\btoggle\\s+off\\b|\\bturn\\s+off\\b|\\bdisable\\b").containsMatchIn(t) -> "toggle_off"
            else -> "tap"
        }
    }

    private fun scopeFor(cand: UICandidate): String? {
        val el = runCatching { driver.findElement(AppiumBy.xpath(cand.xpath)) }.getOrNull() ?: return null
        val y = el.rect.y
        val fromY = headerY("from")
        val toY = headerY("to")
        return when {
            fromY != null && toY != null -> if (y < toY) "from" else "to"
            fromY != null -> if (y >= fromY) "from" else null
            toY != null -> if (y >= toY) "to" else null
            else -> null
        }
    }

    private fun headerY(label: String): Int? {
        val xp = "//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')=${xpathLiteral(label)}]"
        return runCatching { driver.findElements(AppiumBy.xpath(xp)).firstOrNull()?.rect?.y }.getOrNull()
    }

    private fun xpathLiteral(s: String): String = when {
        '\'' !in s -> "'$s'"
        '"' !in s -> "\"$s\""
        else -> "concat('${s.replace("'", "',\"'\",'")}')"
    }

    fun resolveLocator(id: String?, all: List<UICandidate>): Locator? {
        val c = all.firstOrNull { it.id == id } ?: return null
        return Locator(Strategy.XPATH, c.xpath)
    }
}

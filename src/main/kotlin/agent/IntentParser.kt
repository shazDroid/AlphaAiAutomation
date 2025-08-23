package agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ui.OllamaClient

class IntentParser {
    private val mapper = jacksonObjectMapper().registerKotlinModule()

    private val systemPrompt = """
        Return ONLY a single minified JSON object for ActionPlan:
        {"title": string, "steps":[{"index":int,"type":"LAUNCH_APP|TAP|INPUT_TEXT|SCROLL_TO|WAIT_TEXT|ASSERT_TEXT|BACK|SLEEP|WAIT_OTP|SLIDE","targetHint":string?,"value":string?}]}
        No prose. No code fences. No extra keys.
    """.trimIndent()


    private val TAP_VERBS = Regex("(?i)\\b(click|tap|press|open|go to|select|choose)\\b")
    private val CLICK_TARGET_HINT = Regex("(?i)\\b(button|tab|icon|item|menu|link|option)\\b")
    private val SCROLL_VERB = Regex("(?i)\\bscroll(ing)?\\b")
    private val SLIDE_VERBS = Regex("(?i)\\b(slide|swipe|drag)\\b")

    // --------------------------- Public API ---------------------------

    fun parse(nlTask: String): ActionPlan {
        val first = OllamaClient.completeJsonBlocking(systemPrompt, nlTask).trim()
        val firstJson = extractJson(first)
        runCatching {
            val p = mapper.readValue<ActionPlan>(firstJson)
            return sanitizePlan(nlTask, p)
        }

        val secondUser = """
            Task: $nlTask
            Output: ONLY ActionPlan JSON (minified). Start with { and end with }.
        """.trimIndent()
        val second = OllamaClient.completeJsonBlocking(systemPrompt, secondUser).trim()
        val secondJson = extractJson(second)
        runCatching {
            val p = mapper.readValue<ActionPlan>(secondJson)
            return sanitizePlan(nlTask, p)
        }

        val fallback = ActionPlan(
            title = "Parsed Task",
            steps = listOf(
                PlanStep(index = 1, type = StepType.LAUNCH_APP, targetHint = "com.example.app")
            )
        )
        return fallback
    }

    // --------------------------- Sanitization ---------------------------

    /** Apply all normalizers and heuristics to make execution robust. */
    private fun sanitizePlan(nlTask: String, plan: ActionPlan): ActionPlan {
        var p = plan
        p = normalizeAllHints(p)
        p = normalizePlanHints(p)
        p = coerceTypesWithClassifier(p)
        p = retagScrollToIfNeeded(nlTask, p)
        val fixedSteps = p.steps.mapIndexed { idx, s -> s.copy(index = idx + 1) }
        return p.copy(steps = fixedSteps)
    }


    /** Normalizes *all* step.targetHint strings (TAP/WAIT/INPUT/etc.) */
    private fun normalizeAllHints(plan: ActionPlan): ActionPlan {
        val fixed = plan.steps.map { s ->
            if (s.type == StepType.LAUNCH_APP) s
            else s.copy(targetHint = s.targetHint?.let { normalizeHint(it) })
        }
        return plan.copy(steps = fixed)
    }


    /** Convert SCROLL_TO -> TAP unless the NL task truly asked to scroll (and not click). */
    private fun retagScrollToIfNeeded(nlTask: String, plan: ActionPlan): ActionPlan {
        val saysScroll = SCROLL_VERB.containsMatchIn(nlTask)
        val saysClick = TAP_VERBS.containsMatchIn(nlTask) || CLICK_TARGET_HINT.containsMatchIn(nlTask)

        val fixed = plan.steps.map { s ->
            if (s.type == StepType.SCROLL_TO) {
                val keepScroll = saysScroll && !saysClick
                if (!keepScroll) s.copy(type = StepType.TAP) else s
            } else s
        }
        return plan.copy(steps = fixed)
    }

    private fun normalizeHint(raw: String): String {
        var h = raw
            .replace(TAP_VERBS, "")
            .replace(CLICK_TARGET_HINT, "")
            .replace(Regex("[\\p{Punct}]"), " ")
            .trim()
        h = h.replace(Regex("\\s+"), " ")
        return h
    }

    // --------------------------- Existing helpers ---------------------------

    private fun extractJson(s: String): String {
        val fenced = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(s)?.groupValues?.get(1)
        if (fenced != null) return fenced.trim()
        val i = s.indexOf('{'); val j = s.lastIndexOf('}')
        if (i != -1 && j > i) return s.substring(i, j + 1).trim()
        return s
    }


    private fun coerceTypesWithClassifier(plan: ActionPlan): ActionPlan {
        val fixed = plan.steps.map { s ->
            val phrase = buildString {
                append(s.targetHint ?: "")
                if (!s.value.isNullOrBlank()) append(" ").append(s.value)
            }.trim()
            val strong = classifyStrongOrNull(phrase)
            if (strong != null && strong != s.type) s.copy(type = strong) else s
        }
        return plan.copy(steps = fixed)
    }



    private fun normalizePlanHints(plan: ActionPlan): ActionPlan {
        fun cleanWaitText(s: String): String {
            return s
                .replace(Regex("(?i)\\b(home\\s*page|page|screen)\\b"), "")
                .replace(Regex("(?i)\\btext\\b"), "")
                .trim()
                .trim('"', '\'')
                .ifBlank { s }
        }
        fun alias(s: String): String = when (s.trim().lowercase()) {
            "dashboard" -> "Home"
            "home page" -> "Home"
            else -> s
        }
        val fixed = plan.steps.map { step ->
            if (step.type == StepType.WAIT_TEXT && step.targetHint != null) {
                val cleaned = alias(cleanWaitText(step.targetHint!!))
                step.copy(targetHint = cleaned)
            } else step
        }
        return plan.copy(steps = fixed)
    }

    @Suppress("unused")
    private fun classify(segment: String): StepType {
        val s = segment.trim()

        if (s.matches(Regex("(?i).*\\bwait\\b.*"))) return StepType.WAIT_TEXT

        if (s.matches(Regex("(?i).*\\bscroll\\b.*"))) return StepType.SCROLL_TO

        if (SLIDE_VERBS.containsMatchIn(s)) return StepType.SLIDE

        if (TAP_VERBS.containsMatchIn(s) || CLICK_TARGET_HINT.containsMatchIn(s)) {
            return StepType.TAP
        }

        if (s.matches(Regex("(?i).*\\b(user|username|login|email)\\b.*\\b(=|to|as)\\b.*"))
            || s.matches(Regex("(?i).*\\bpassword\\b.*\\b(=|to|as)\\b.*"))
        ) return StepType.INPUT_TEXT

        return StepType.TAP
    }

    private fun classifyStrongOrNull(segment: String): StepType? {
        val s = segment.trim()

        if (Regex("(?i)\\b(wait|loading|processing|ready)\\b").containsMatchIn(s)) return StepType.WAIT_TEXT

        if (Regex("(?i)\\b(otp|one[- ]?time|verification\\s*code)\\b").containsMatchIn(s)) return StepType.WAIT_OTP

        if (SCROLL_VERB.containsMatchIn(s)) return StepType.SCROLL_TO

        if (SLIDE_VERBS.containsMatchIn(s)) return StepType.SLIDE

        val hasInputVerb = Regex("(?i)\\b(enter|type|input|fill|set|provide|write|paste)\\b").containsMatchIn(s)
        val hasFieldKey  = Regex("(?i)\\b(user(name)?|login|email|pass(word)?|pwd|otp|code|mobile|phone)\\b").containsMatchIn(s)
        if (hasInputVerb && hasFieldKey) return StepType.INPUT_TEXT

        if (s.matches(Regex("(?i)^(user(name)?|login|email|pass(word)?|pwd|otp|code|mobile|phone)(\\s+field)?$"))) {
            return StepType.INPUT_TEXT
        }

        if (Regex("(?i)\\b(home|dashboard|landing|welcome)\\b").containsMatchIn(s) &&
            !Regex("(?i)\\b(button|tab|icon|item|menu|link|option)\\b").containsMatchIn(s)) {
            return StepType.WAIT_TEXT
        }

        return null
    }

}

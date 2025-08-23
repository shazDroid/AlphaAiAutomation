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
    
    Rules (must follow):
    - INPUT_TEXT: only when user explicitly provides a value to enter. "value" MUST be non-empty. Never use INPUT_TEXT on buttons or tabs.
    - TAP: only for press/click/select/open/go to. If unsure between TAP vs INPUT_TEXT, choose TAP.
    - WAIT_TEXT: only if the task says "wait", "should see", "verify", or implies waiting for UI text/state; targetHint MUST be non-empty.
    - SCROLL_TO: only if the task explicitly says "scroll".
    - SLIDE: only if the task says "slide"/"swipe"/"drag" to confirm.
    - CHECK: check/uncheck/tick/untick/toggle a checkbox/switch/item; use targetHint for the label; put ordinals like "first" into wording (the caller will extract nth).
    - WAIT_OTP: only if the user says to wait for an OTP / verification code.
    - LAUNCH_APP: at most once (if a package is given).
    - Do not invent extra steps. Do not duplicate equivalent consecutive steps.
    
    Examples:
    
    Input:
      Launch com.example, input username "alice", input password "Secret1!", tap "LOGIN", wait "Home"
    Output:
      {"title":"Login Flow","steps":[
        {"index":1,"type":"LAUNCH_APP","targetHint":"com.example"},
        {"index":2,"type":"INPUT_TEXT","targetHint":"username","value":"alice"},
        {"index":3,"type":"INPUT_TEXT","targetHint":"password","value":"Secret1!"},
        {"index":4,"type":"TAP","targetHint":"LOGIN"},
        {"index":5,"type":"WAIT_TEXT","targetHint":"Home"}
      ]}
    
    Input:
      Tap Services, then Apply for new services, then Profile, then Change Password, slide to confirm, wait for OTP
    Output:
      {"title":"Navigate & Change","steps":[
        {"index":1,"type":"TAP","targetHint":"Services"},
        {"index":2,"type":"TAP","targetHint":"Apply for new services"},
        {"index":3,"type":"TAP","targetHint":"Profile"},
        {"index":4,"type":"TAP","targetHint":"Change Password"},
        {"index":5,"type":"SLIDE","targetHint":"Slide to confirm"},
        {"index":6,"type":"WAIT_OTP","targetHint":"OTP"}
      ]}
    """.trimIndent()



    private val TAP_VERBS = Regex("(?i)\\b(click|tap|press|open|go to|select|choose)\\b")
    private val CLICK_TARGET_HINT = Regex("(?i)\\b(button|tab|icon|item|menu|link|option)\\b")
    private val SCROLL_VERB = Regex("(?i)\\bscroll(ing)?\\b")
    private val SLIDE_VERBS = Regex("(?i)\\b(slide|swipe|drag)\\b")
    private val CHECK_VERBS = Regex("(?i)\\b(check|uncheck|tick|untick|toggle|select|deselect)\\b")
    private val CHECK_DISAMBIGUATE_NEG = Regex("(?i)\\b(check for|check that)\\b")
    private val ORDINAL_RX = Regex("(?i)\\b((?:1st|2nd|3rd|\\d+th)|first|second|third|fourth|fifth)\\b")

    // --------------------------- Public API ---------------------------

    fun parse(nlTask: String, packageName: String): ActionPlan {
        splitIfVisibleSentence(nlTask)?.let { cond ->
            val prefixSteps = parseStepsOnly(cond.prefix)
            val thenSteps   = parseStepsOnly(cond.thenPart)
            val elseSteps   = parseStepsOnly(cond.elsePart ?: "")

            val thenLabel = "COND_TRUE"
            val elseLabel = "COND_FALSE"
            val endLabel  = "COND_END"

            val combined = mutableListOf<PlanStep>()

            val hasLaunch = prefixSteps.any { it.type == StepType.LAUNCH_APP }
            if (!hasLaunch) combined += PlanStep(index = 1, type = StepType.LAUNCH_APP, targetHint = packageName)

            combined += prefixSteps.mapIndexed { i, s -> s.copy(index = i + 1) }

            combined += listOf(
                PlanStep(index = 0, type = StepType.IF_VISIBLE, targetHint = cond.conditionQuery,
                    value = null, meta = mapOf("then" to thenLabel, "else" to elseLabel)),
                PlanStep(index = 0, type = StepType.LABEL, targetHint = thenLabel),
            )
            combined += thenSteps
            combined += listOf(
                PlanStep(index = 0, type = StepType.GOTO, targetHint = endLabel, meta = mapOf("label" to endLabel)),
                PlanStep(index = 0, type = StepType.LABEL, targetHint = elseLabel),
            )
            combined += elseSteps
            combined += PlanStep(index = 0, type = StepType.LABEL, targetHint = endLabel)

            val final = combined.mapIndexed { i, s -> s.copy(index = i + 1) }
            return ActionPlan(title = "KSA Emirates UAT (conditional)", steps = final)
        }

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

        return ActionPlan(
            title = "Parsed Task",
            steps = listOf( PlanStep(index = 1, type = StepType.LAUNCH_APP, targetHint = packageName) )
        )
    }



    // --------------------------- Sanitization ---------------------------

    /** Apply all normalizers and heuristics to make execution robust. */
    private fun sanitizePlan(nlTask: String, plan: ActionPlan): ActionPlan {
        var p = plan
        p = normalizeAllHints(p)
        p = normalizePlanHints(p)
        p = coerceTypesWithClassifier(p)
        p = retagScrollToIfNeeded(nlTask, p)
        p = dedupeConsecutive(p)
        p = dropEmptyOrPackageTaps(p)
        p = postValidate(p)
        p = enrichOrdinals(p)
        val fixedSteps = p.steps.mapIndexed { idx, s -> s.copy(index = idx + 1) }
        return p.copy(steps = fixedSteps)
    }



    /** Normalizes *all* step.targetHint strings (TAP/WAIT/INPUT/etc.) */
    private fun normalizeAllHints(plan: ActionPlan): ActionPlan {
        val fixed = plan.steps.map { s ->
            if (s.type == StepType.LAUNCH_APP) s
            else s.copy(targetHint = s.targetHint?.let { normalizeHint(stripScreenWords(it)) })
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

        if (CHECK_VERBS.containsMatchIn(s) && !CHECK_DISAMBIGUATE_NEG.containsMatchIn(s))
            return StepType.CHECK

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

    private fun extractNth(s: String): Int? {
        val m = ORDINAL_RX.find(s) ?: return null
        val w = m.value.lowercase()
        return when (w) {
            "first","1st" -> 1
            "second","2nd" -> 2
            "third","3rd" -> 3
            "fourth","4th" -> 4
            "fifth","5th" -> 5
            else -> w.removeSuffix("th").toIntOrNull()
        }
    }

    private fun enrichOrdinals(plan: ActionPlan): ActionPlan {
        val fixed = plan.steps.map { s ->
            if (s.type == StepType.CHECK) {
                val blob = "${s.targetHint ?: ""} ${s.value ?: ""}"
                val nth = extractNth(blob)
                if (nth != null) s.copy(meta = s.meta + ("nth" to nth.toString())) else s
            } else s
        }
        return plan.copy(steps = fixed)
    }

    private data class CondSplit(
        val prefix: String,
        val conditionQuery: String,
        val thenPart: String,
        val elsePart: String?
    )

    private fun splitIfVisibleSentence(nl: String): CondSplit? {
        val lower = nl.lowercase()

        val ifIdx = lower.indexOf(" if visible")
        if (ifIdx < 0) return null

        val beforeIf = nl.substring(0, ifIdx)
        val m = Regex("(?i)check\\s+for\\s+(.+?)\\s+(?:should\\s+be\\s+)?visible")
            .find(beforeIf)
            ?: return null
        val cond = m.groupValues[1].trim()

        val prefix = beforeIf.substring(0, m.range.first).trim()

        val afterIf = nl.substring(ifIdx + " if visible".length).trim()
        val elseIdx = afterIf.lowercase().indexOf(" else ")
        val thenPart = if (elseIdx >= 0) afterIf.substring(0, elseIdx).trim() else afterIf
        val elsePart = if (elseIdx >= 0) afterIf.substring(elseIdx + 6).trim() else null

        return CondSplit(prefix = prefix, conditionQuery = cond, thenPart = thenPart, elsePart = elsePart)
    }

    private fun parseStepsOnly(nl: String): List<PlanStep> {
        if (nl.isBlank()) return emptyList()
        val raw = OllamaClient.completeJsonBlocking(systemPrompt, nl).trim()
        val json = extractJson(raw)
        val plan = runCatching { mapper.readValue<ActionPlan>(json) }.getOrNull()
            ?: return emptyList()
        val sanitized = sanitizePlan(nl, plan)
        return sanitized.steps.filter { it.type != StepType.LAUNCH_APP }
    }

    private fun stripScreenWords(s: String): String =
        s.replace(Regex("(?i)\\b(home\\s*page|page|screen|text)\\b"), "")
            .trim()
            .ifBlank { s }

    private fun dedupeConsecutive(plan: ActionPlan): ActionPlan {
        val out = mutableListOf<PlanStep>()
        for (s in plan.steps) {
            val prev = out.lastOrNull()
            val same = prev != null &&
                    prev.type == s.type &&
                    (prev.targetHint?.trim()?.lowercase() ?: "") ==
                    (s.targetHint?.trim()?.lowercase() ?: "")
            if (!same) out += s
        }
        return plan.copy(steps = out.mapIndexed { i, st -> st.copy(index = i + 1) })
    }

    private fun dropEmptyOrPackageTaps(plan: ActionPlan): ActionPlan {
        val pkgLike = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+\$")
        val out = plan.steps.filterNot { st ->
            (st.type == StepType.TAP && (st.targetHint.isNullOrBlank() || pkgLike.matches(st.targetHint!!)))
                    || (st.type == StepType.WAIT_TEXT && st.targetHint.isNullOrBlank() && st.value.isNullOrBlank())
        }
        return plan.copy(steps = out.mapIndexed { i, st -> st.copy(index = i + 1) })
    }

    private val PACKAGE_RX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+\$")

    private fun looksLikePackage(s: String?): Boolean =
        s?.let { PACKAGE_RX.matches(it.trim()) } == true


    private fun postValidate(plan: ActionPlan): ActionPlan {
        val firstLaunch = plan.steps.indexOfFirst { it.type == StepType.LAUNCH_APP }
        var steps = plan.steps
            .filterIndexed { i, s -> s.type != StepType.LAUNCH_APP || i == firstLaunch }
            .toMutableList()

        steps = steps.map { s ->
            if (s.type == StepType.INPUT_TEXT && s.value.isNullOrBlank())
                s.copy(type = StepType.TAP)
            else s
        }.toMutableList()

        steps = steps.filterNot { it.type == StepType.WAIT_TEXT && (it.targetHint.isNullOrBlank() && it.value.isNullOrBlank()) }
            .toMutableList()

        steps = steps.filterNot { it.type == StepType.TAP && looksLikePackage(it.targetHint) }
            .toMutableList()

        val deduped = mutableListOf<PlanStep>()
        for (s in steps) {
            val last = deduped.lastOrNull()
            if (last != null &&
                last.type == s.type &&
                (last.targetHint ?: "") == (s.targetHint ?: "") &&
                (last.value ?: "") == (s.value ?: "")) {
                continue
            }
            deduped += s
        }

        val reindexed = deduped.mapIndexed { idx, s -> s.copy(index = idx + 1) }
        return plan.copy(steps = reindexed)
    }


}

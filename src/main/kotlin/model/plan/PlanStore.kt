package model.plan

import agent.StepType
import androidx.compose.runtime.snapshots.Snapshot
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

private data class PersistPlanStep(
    val index: Int,
    val type: String,
    val details: Map<String, String>?,
    val screenshotPath: String?,
    val screen: String?
)

private data class PersistPlan(
    val id: String,
    val name: String,
    val status: String,
    val createdAtEpoch: Long,
    val steps: List<PersistPlanStep>
)

object PlanStore {
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private fun plansPath(): Path =
        Path.of(System.getProperty("user.home"), ".alpha-ui-automation", "plans.json")

    fun loadIntoRegistry() {
        val p = plansPath()
        if (!Files.exists(p)) return
        val list: List<PersistPlan> =
            mapper.readValue(Files.readAllBytes(p), object : TypeReference<List<PersistPlan>>() {})
        val restored = list.map { pp ->
            Plan(
                id = PlanId(pp.id),
                name = pp.name,
                status = PlanStatus.valueOf(pp.status),
                createdAt = Instant.ofEpochMilli(pp.createdAtEpoch),
                steps = pp.steps.map { s ->
                    PlanStep(
                        index = s.index,
                        type = StepType.valueOf(s.type),
                        details = s.details ?: emptyMap(),
                        screenshotPath = s.screenshotPath,
                        screen = s.screen
                    )
                }
            )
        }
        Snapshot.withMutableSnapshot {
            PlanRegistry.plans.clear()
            PlanRegistry.plans.addAll(restored)
        }
    }

    fun saveRegistry() {
        val p = plansPath()
        Files.createDirectories(p.parent)
        val tmp = p.resolveSibling("plans.json.tmp")
        val payload = PlanRegistry.plans.map { pl ->
            PersistPlan(
                id = pl.id.value,
                name = pl.name,
                status = pl.status.name,
                createdAtEpoch = pl.createdAt.toEpochMilli(),
                steps = pl.steps.map { st ->
                    PersistPlanStep(
                        index = st.index,
                        type = st.type.name,
                        details = if (st.details.isEmpty()) null else st.details,
                        screenshotPath = st.screenshotPath,
                        screen = st.screen
                    )
                }
            )
        }
        val bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload)
        Files.write(tmp, bytes)
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

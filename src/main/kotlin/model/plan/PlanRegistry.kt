package model.plan

import androidx.compose.runtime.mutableStateListOf
import java.time.LocalDate
import java.time.ZoneId

object PlanRegistry {
    val plans = mutableStateListOf<Plan>()
}

fun provideSuccessfulPlansUpTo(date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Plan> =
    PlanRegistry.plans
        .filter { it.status == PlanStatus.SUCCESS && it.createdAt.atZone(zone).toLocalDate() <= date }
        .sortedByDescending { it.createdAt }

package app.betterhabits.ui.allocation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.betterhabits.R
import app.betterhabits.domain.allocation.HouseholdWorkload
import app.betterhabits.domain.allocation.MemberWorkload
import app.betterhabits.domain.model.Effort
import app.betterhabits.ui.AppViewModelFactory
import app.betterhabits.ui.chores.HouseholdContext
import app.betterhabits.ui.chores.effortText
import app.betterhabits.ui.chores.resources
import app.betterhabits.ui.components.ErrorState
import app.betterhabits.ui.components.LoadingState
import app.betterhabits.ui.components.OnScreenResume
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceScreen(onBack: () -> Unit, onSuggest: () -> Unit, viewModel: BalanceViewModel = viewModel(factory = AppViewModelFactory.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnScreenResume(viewModel::load) // after accepting suggestions
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.balance_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
                },
            )
        },
    ) { padding ->
        val workload = state.workload
        val context = state.context
        when {
            state.loading && workload == null -> LoadingState(Modifier.padding(padding))
            workload == null || context == null -> ErrorState(state.loadError ?: return@Scaffold, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BalancePeriod.entries.forEachIndexed { index, period ->
                        SegmentedButton(
                            selected = state.period == period,
                            onClick = { viewModel.setPeriod(period) },
                            shape = SegmentedButtonDefaults.itemShape(index, BalancePeriod.entries.size),
                            label = { Text(stringResource(if (period == BalancePeriod.THIS_WEEK) R.string.balance_this_week else R.string.balance_four_weeks)) },
                        )
                    }
                }
                SummaryCard(workload, context)
                workload.members.forEach { MemberCard(it, context) }
                if (workload.unassignedMinutes > 0) {
                    Text(
                        stringResource(R.string.balance_unassigned, effortText(resources(), Effort(workload.unassignedMinutes))),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(stringResource(R.string.balance_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.canSuggest) {
                    Button(onClick = onSuggest, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.AutoFixHigh, contentDescription = null)
                        Text(stringResource(R.string.action_suggest_assignments), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(workload: HouseholdWorkload, context: HouseholdContext) {
    val res = resources()
    val verdict = when {
        workload.totalMinutes == 0 -> R.string.balance_nothing
        workload.balanceScore >= 0.9 -> R.string.balance_good
        workload.balanceScore >= 0.75 -> R.string.balance_slightly_uneven
        else -> R.string.balance_uneven
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(verdict), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            workload.members.forEach { member ->
                val name = context.member(member.memberId)?.displayName.orEmpty()
                val actual = workload.actualShare(member.memberId)
                val fair = workload.fairShares.getValue(member.memberId)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.balance_member_share, effortText(res, Effort(member.estimatedMinutes)), percent(actual)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    // The bar shows the share; the text above says it, so colour isn't the only signal.
                    LinearProgressIndicator(progress = { actual.toFloat() }, modifier = Modifier.fillMaxWidth())
                    if (workload.members.size > 1) {
                        Text(
                            stringResource(R.string.balance_fair_share, percent(fair)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberCard(member: MemberWorkload, context: HouseholdContext) {
    val res = resources()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(context.member(member.memberId)?.displayName.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Text(pluralStringResource(R.plurals.balance_estimated, member.choreCount, effortText(res, Effort(member.estimatedMinutes)), member.choreCount))
            member.completionRate?.let { Text(stringResource(R.string.balance_completion, percent(it), member.completed, member.completed + member.missed)) }
            if (member.completedMinutes > 0) Text(stringResource(R.string.balance_done, effortText(res, Effort(member.completedMinutes))))
            if (member.undesirableCount > 0) Text(pluralStringResource(R.plurals.balance_disliked, member.undesirableCount, member.undesirableCount))
            member.averageDifficulty?.let { Text(stringResource(R.string.balance_difficulty, it)) }
            if (member.points > 0) Text(pluralStringResource(R.plurals.balance_points, member.points, member.points))
        }
    }
}

private fun percent(fraction: Double): String = "${(fraction * 1000).roundToInt() / 10.0}%"

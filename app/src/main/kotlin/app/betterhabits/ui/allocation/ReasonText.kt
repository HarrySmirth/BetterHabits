package app.betterhabits.ui.allocation

import android.content.res.Resources
import app.betterhabits.R
import app.betterhabits.domain.allocation.AllocationReason
import app.betterhabits.domain.model.Effort
import app.betterhabits.ui.chores.effortText

/** One plain sentence per reason, e.g. "Sarah prefers this chore", "35 min less work this week than Harry". */
fun reasonText(res: Resources, reason: AllocationReason, assigneeName: String, nameOf: (String) -> String): String = when (reason) {
    AllocationReason.Locked -> res.getString(R.string.reason_locked)
    AllocationReason.NoOneEligible -> res.getString(R.string.reason_no_one)
    AllocationReason.OnlyOneEligible -> res.getString(R.string.reason_only_one, assigneeName)
    is AllocationReason.Prefers -> res.getString(
        if (reason.level == app.betterhabits.domain.allocation.PreferenceLevel.LOVE) R.string.reason_loves else R.string.reason_likes,
        assigneeName,
    )
    is AllocationReason.OthersPreferLess -> res.getString(R.string.reason_others_prefer_less, nameOf(reason.runnerUpId))
    is AllocationReason.LessWork -> res.getString(R.string.reason_less_work, assigneeName, effortText(res, Effort(reason.minutesPerWeek)), nameOf(reason.runnerUpId))
    is AllocationReason.TakesTurn -> res.getString(R.string.reason_takes_turn, nameOf(reason.lastDoneBy))
    is AllocationReason.AvailableWhenOthersNot -> res.getString(R.string.reason_available, assigneeName, nameOf(reason.runnerUpId))
    AllocationReason.SuitsTheirTime -> res.getString(R.string.reason_suits_time, assigneeName)
    AllocationReason.SharesUndesirableWork -> res.getString(R.string.reason_shares_undesirable)
    AllocationReason.KeepsWorkloadBalanced -> res.getString(R.string.reason_balanced)
}

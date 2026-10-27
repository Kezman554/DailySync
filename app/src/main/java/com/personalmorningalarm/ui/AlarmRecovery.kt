package com.personalmorningalarm.ui

import android.content.Context
import android.content.Intent
import com.personalmorningalarm.service.NuclearAlarmService

/**
 * Getting back to a live alarm whose screen was lost. The nuclear alarm outranks
 * the other stages: once it is sounding, that is the screen to return to.
 */
object AlarmRecovery {

    /** An alarm is sounding or counting down, with no guarantee a screen shows it. */
    fun hasLiveAlarm(): Boolean =
        NuclearAlarmService.isRunning || AlarmDismissalActivity.hasResumableSession()

    fun resumeIntent(context: Context): Intent =
        if (NuclearAlarmService.isRunning) NuclearDismissalActivity.resumeIntent(context)
        else AlarmDismissalActivity.resumeIntent(context)
}

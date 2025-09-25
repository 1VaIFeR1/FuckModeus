package com.fuck.modeus.ui

import com.fuck.modeus.data.ScheduleItem

interface ScheduleInteractionListener {
    fun onScheduleItemLongClicked(item: ScheduleItem)
}
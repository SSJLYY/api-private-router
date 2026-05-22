<template>
  <AppLayout>
    <div class="mx-auto max-w-7xl">
      <UserCheckInPanel
        :status="status"
        :loading="loading"
        :action-loading="actionLoading"
        :calendar-loading="calendarLoading"
        :history-loading="historyLoading"
        :display-year="displayYear"
        :display-month="displayMonth"
        :calendar-days="calendarDays"
        :history="history"
        @checkin="handleCheckIn"
        @prev-month="goToPreviousMonth"
        @next-month="goToNextMonth"
      />
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppLayout from '@/components/layout/AppLayout.vue'
import UserCheckInPanel from '@/components/user/dashboard/UserCheckInPanel.vue'
import { checkinAPI, type CheckInCalendarDay, type CheckInHistoryItem, type CheckInStatus } from '@/api/checkin'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const { t } = useI18n()

const now = new Date()
const displayYear = ref(now.getFullYear())
const displayMonth = ref(now.getMonth() + 1)

const status = ref<CheckInStatus | null>(null)
const calendarDays = ref<CheckInCalendarDay[]>([])
const history = ref<CheckInHistoryItem[]>([])

const loading = ref(false)
const actionLoading = ref(false)
const calendarLoading = ref(false)
const historyLoading = ref(false)

async function loadStatus() {
  loading.value = true
  try {
    status.value = await checkinAPI.getStatus()
    if (status.value?.checkin_date) {
      const [year, month] = status.value.checkin_date.split('-').map(Number)
      if (year > 0 && month > 0) {
        displayYear.value = year
        displayMonth.value = month
      }
    }
  } catch (error) {
    console.error('Failed to load check-in status:', error)
    appStore.showError(t('checkin.loadStatusFailed'))
  } finally {
    loading.value = false
  }
}

async function loadCalendar() {
  calendarLoading.value = true
  try {
    const response = await checkinAPI.getCalendar(displayYear.value, displayMonth.value)
    calendarDays.value = response.days ?? []
  } catch (error) {
    console.error('Failed to load check-in calendar:', error)
    calendarDays.value = []
    appStore.showError(t('checkin.loadCalendarFailed'))
  } finally {
    calendarLoading.value = false
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const response = await checkinAPI.getHistory(1, 20)
    history.value = response.items ?? []
  } catch (error) {
    console.error('Failed to load check-in history:', error)
    history.value = []
    appStore.showError(t('checkin.loadHistoryFailed'))
  } finally {
    historyLoading.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadStatus(), loadCalendar(), loadHistory()])
}

async function handleCheckIn(stakeAmount: number) {
  if (actionLoading.value || status.value?.checked_in) {
    return
  }

  actionLoading.value = true
  try {
    const result = await checkinAPI.performCheckIn(stakeAmount)
    appStore.showSuccess(t('checkin.checkInSuccess', { amount: result.reward_amount }))
    await Promise.all([loadStatus(), loadCalendar(), loadHistory()])
  } catch (error: any) {
    console.error('Failed to check in:', error)
    appStore.showError(error?.message || t('checkin.checkInFailed'))
  } finally {
    actionLoading.value = false
  }
}

function goToPreviousMonth() {
  if (displayMonth.value === 1) {
    displayMonth.value = 12
    displayYear.value -= 1
  } else {
    displayMonth.value -= 1
  }
  loadCalendar()
}

function goToNextMonth() {
  if (displayMonth.value === 12) {
    displayMonth.value = 1
    displayYear.value += 1
  } else {
    displayMonth.value += 1
  }
  loadCalendar()
}

onMounted(() => {
  refreshAll()
})
</script>

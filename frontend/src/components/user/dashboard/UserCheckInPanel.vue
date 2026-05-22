<template>
  <div class="space-y-6">
    <div class="card overflow-hidden">
      <div class="bg-gradient-to-br from-primary-500 via-primary-600 to-emerald-500 p-6 text-white">
        <div class="flex flex-col gap-6 lg:flex-row lg:items-center lg:justify-between">
          <div class="space-y-3">
            <div class="inline-flex h-12 w-12 items-center justify-center rounded-2xl bg-white/15 backdrop-blur-sm">
              <Icon name="badge" size="xl" class="text-white" />
            </div>
            <div>
              <p class="text-sm font-medium text-primary-100">{{ t('checkin.todayStatus') }}</p>
              <h2 class="mt-1 text-2xl font-bold">{{ checkedInToday ? t('checkin.checkedInToday') : t('checkin.notCheckedInToday') }}</h2>
            </div>
            <p class="max-w-2xl text-sm text-primary-100">
              {{ t('checkin.heroDescription') }}
            </p>
          </div>

          <div class="w-full max-w-sm rounded-2xl bg-white/10 p-4 backdrop-blur-sm">
            <label class="text-sm font-medium text-primary-50">{{ t('checkin.stakeAmount') }}</label>
            <input
              v-model.number="stakeAmount"
              type="number"
              min="0.01"
              step="0.01"
              class="mt-2 w-full rounded-xl border border-white/20 bg-white/90 px-4 py-3 text-gray-900 outline-none ring-0"
              :placeholder="t('checkin.stakePlaceholder')"
              :disabled="checkedInToday || actionLoading"
            />
            <div class="mt-3 flex items-center justify-between text-xs text-primary-100">
              <span>{{ t('checkin.balanceLabel') }}: {{ formatMoney(status?.balance) }}</span>
              <span>{{ t('checkin.multiplierRange') }}</span>
            </div>
            <button
              type="button"
              :disabled="loading || actionLoading || checkedInToday || !canSubmit"
              class="btn mt-4 w-full border-white/20 bg-white text-primary-700 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-60"
              @click="$emit('checkin', Number(stakeAmount || 0))"
            >
              <svg v-if="actionLoading" class="mr-2 h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
              </svg>
              <Icon v-else :name="checkedInToday ? 'check' : 'sparkles'" size="sm" class="mr-2" />
              {{ checkedInToday ? t('checkin.alreadyCheckedIn') : t('checkin.checkInNow') }}
            </button>
          </div>
        </div>
      </div>

      <div class="grid grid-cols-1 gap-4 p-4 md:grid-cols-4">
        <div class="rounded-2xl bg-gray-50 p-4 dark:bg-dark-800/70">
          <p class="text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-dark-400">{{ t('checkin.balanceLabel') }}</p>
          <p class="mt-2 text-2xl font-bold text-gray-900 dark:text-white">{{ formatMoney(status?.balance) }}</p>
        </div>
        <div class="rounded-2xl bg-gray-50 p-4 dark:bg-dark-800/70">
          <p class="text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-dark-400">{{ t('checkin.latestMultiplier') }}</p>
          <p class="mt-2 text-2xl font-bold text-gray-900 dark:text-white">{{ status?.latest_multiplier?.toFixed(2) ?? '--' }}x</p>
        </div>
        <div class="rounded-2xl bg-gray-50 p-4 dark:bg-dark-800/70">
          <p class="text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-dark-400">{{ t('checkin.latestReward') }}</p>
          <p class="mt-2 text-2xl font-bold text-emerald-600 dark:text-emerald-400">{{ formatSignedMoney(status?.latest_reward_amount, false) }}</p>
        </div>
        <div class="rounded-2xl bg-gray-50 p-4 dark:bg-dark-800/70">
          <p class="text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-dark-400">{{ t('checkin.latestNetChange') }}</p>
          <p class="mt-2 text-2xl font-bold" :class="(status?.latest_net_change ?? 0) >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-rose-600 dark:text-rose-400'">{{ formatSignedMoney(status?.latest_net_change) }}</p>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,2fr)_minmax(320px,1fr)]">
      <div class="card p-6">
        <div class="mb-4 flex items-center justify-between gap-4">
          <div>
            <h3 class="text-lg font-semibold text-gray-900 dark:text-white">{{ t('checkin.calendar') }}</h3>
            <p class="text-sm text-gray-500 dark:text-dark-400">{{ monthTitle }}</p>
          </div>
          <div class="flex items-center gap-2">
            <button type="button" class="btn btn-secondary !px-3 !py-2" :disabled="calendarLoading" @click="$emit('prev-month')">
              <Icon name="chevronLeft" size="sm" />
            </button>
            <button type="button" class="btn btn-secondary !px-3 !py-2" :disabled="calendarLoading" @click="$emit('next-month')">
              <Icon name="chevronRight" size="sm" />
            </button>
          </div>
        </div>

        <div class="grid grid-cols-7 gap-2 text-center text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-dark-400">
          <div v-for="weekday in weekdays" :key="weekday">{{ weekday }}</div>
        </div>

        <div v-if="calendarLoading" class="flex items-center justify-center py-16">
          <LoadingSpinner />
        </div>
        <div v-else class="mt-3 grid grid-cols-7 gap-2">
          <div
            v-for="day in calendarCells"
            :key="day.key"
            class="min-h-[88px] rounded-2xl border p-3 transition-colors"
            :class="day.classes"
          >
            <div class="flex items-start justify-between gap-2">
              <span class="text-sm font-semibold">{{ day.label }}</span>
              <Icon v-if="day.checkedIn" name="check" size="sm" class="text-emerald-500" />
            </div>
            <p v-if="day.rewardText" class="mt-3 text-xs font-medium" :class="day.rewardClass">{{ day.rewardText }}</p>
          </div>
        </div>
      </div>

      <div class="space-y-6">
        <div class="card p-6">
          <h3 class="text-lg font-semibold text-gray-900 dark:text-white">{{ t('checkin.historyTitle') }}</h3>
          <div v-if="historyLoading" class="flex items-center justify-center py-10">
            <LoadingSpinner />
          </div>
          <div v-else-if="history.length > 0" class="mt-4 space-y-3">
            <div
              v-for="item in history"
              :key="String(item.id)"
              class="rounded-2xl border border-gray-100 p-4 dark:border-dark-700"
            >
              <div class="flex items-start justify-between gap-4">
                <div>
                  <p class="font-medium text-gray-900 dark:text-white">{{ formatDateTime(item.checked_in_at) }}</p>
                  <p class="mt-1 text-sm text-gray-500 dark:text-dark-400">
                    {{ t('checkin.historyStakeReward', { stake: formatMoney(item.stake_amount), reward: formatMoney(item.reward_amount), multiplier: item.multiplier.toFixed(2) }) }}
                  </p>
                  <p class="mt-1 text-xs text-gray-500 dark:text-dark-400">
                    {{ t('checkin.historyBalanceChange', { before: formatMoney(item.balance_before), after: formatMoney(item.balance_after) }) }}
                  </p>
                </div>
                <div class="text-right">
                  <p class="text-sm font-semibold" :class="item.net_change >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-rose-600 dark:text-rose-400'">{{ formatSignedMoney(item.net_change) }}</p>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="mt-4 rounded-2xl border border-dashed border-gray-200 p-6 text-center text-sm text-gray-500 dark:border-dark-700 dark:text-dark-400">
            {{ t('checkin.noHistory') }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Icon from '@/components/icons/Icon.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import type { CheckInCalendarDay, CheckInHistoryItem, CheckInStatus } from '@/api/checkin'
import { formatDateTime } from '@/utils/format'

const props = defineProps<{
  status: CheckInStatus | null
  loading: boolean
  actionLoading: boolean
  calendarLoading: boolean
  historyLoading: boolean
  displayYear: number
  displayMonth: number
  calendarDays: CheckInCalendarDay[]
  history: CheckInHistoryItem[]
}>()

defineEmits<{
  (e: 'checkin', stakeAmount: number): void
  (e: 'prev-month'): void
  (e: 'next-month'): void
}>()

const { t, d } = useI18n()
const stakeAmount = ref<number | null>(10)

const weekdays = computed(() => [
  t('checkin.weekdays.sun'),
  t('checkin.weekdays.mon'),
  t('checkin.weekdays.tue'),
  t('checkin.weekdays.wed'),
  t('checkin.weekdays.thu'),
  t('checkin.weekdays.fri'),
  t('checkin.weekdays.sat')
])

const checkedInToday = computed(() => props.status?.checked_in ?? false)
const canSubmit = computed(() => !!stakeAmount.value && stakeAmount.value >= 0.01 && (props.status?.balance == null || stakeAmount.value <= props.status.balance))
const monthTitle = computed(() => d(new Date(props.displayYear, props.displayMonth - 1, 1), 'monthYear'))

const calendarMap = computed(() => {
  const map = new Map<string, CheckInCalendarDay>()
  for (const day of props.calendarDays) {
    map.set(day.checkin_date, day)
  }
  return map
})

const calendarCells = computed(() => {
  const firstDay = new Date(props.displayYear, props.displayMonth - 1, 1)
  const daysInMonth = new Date(props.displayYear, props.displayMonth, 0).getDate()
  const leading = firstDay.getDay()
  const cells: Array<{ key: string; label: string; checkedIn: boolean; rewardText: string; rewardClass: string; classes: string }> = []

  for (let index = 0; index < leading; index += 1) {
    cells.push({ key: `blank-${index}`, label: '', checkedIn: false, rewardText: '', rewardClass: '', classes: 'border-transparent bg-transparent' })
  }

  const todayKey = props.status?.checkin_date || ''

  for (let dayNumber = 1; dayNumber <= daysInMonth; dayNumber += 1) {
    const key = `${props.displayYear}-${String(props.displayMonth).padStart(2, '0')}-${String(dayNumber).padStart(2, '0')}`
    const record = calendarMap.value.get(key)
    const isToday = key === todayKey
    const checkedIn = record?.checked_in === true
    const netChange = record?.net_change ?? null

    cells.push({
      key,
      label: String(dayNumber),
      checkedIn,
      rewardText: checkedIn && netChange != null ? formatSignedMoney(netChange) : '',
      rewardClass: netChange != null && netChange >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-rose-600 dark:text-rose-400',
      classes: [
        'border-gray-100 bg-white dark:border-dark-700 dark:bg-dark-900/60',
        checkedIn ? 'border-emerald-200 bg-emerald-50 dark:border-emerald-700/40 dark:bg-emerald-900/10' : '',
        isToday ? 'ring-2 ring-primary-200 dark:ring-primary-700/40' : ''
      ].join(' ')
    })
  }

  return cells
})

function formatMoney(amount?: number | null): string {
  const value = amount ?? 0
  return `$${value.toFixed(value >= 1 ? 2 : 4)}`
}

function formatSignedMoney(amount?: number | null, withDollar = true): string {
  const value = amount ?? 0
  const sign = value >= 0 ? '+' : '-'
  const absValue = Math.abs(value)
  const abs = absValue.toFixed(absValue >= 1 ? 2 : 4)
  return withDollar ? `${sign}$${abs}` : `${sign}${abs}`
}
</script>

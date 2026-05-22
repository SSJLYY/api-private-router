<template>
  <AppLayout>
    <div class="space-y-6">
      <div v-if="loading" class="flex items-center justify-center py-12"><LoadingSpinner /></div>
      <template v-else-if="stats">
        <UserDashboardStats :stats="stats" :balance="user?.balance || 0" :is-simple="authStore.isSimpleMode" />
        <UserDashboardCharts v-model:startDate="startDate" v-model:endDate="endDate" v-model:granularity="granularity" :loading="loadingCharts" :trend="trendData" :models="modelStats" @dateRangeChange="loadCharts" @granularityChange="loadCharts" @refresh="refreshAll" />
        <div class="grid grid-cols-1 gap-6 xl:grid-cols-3">
          <div class="xl:col-span-2"><UserDashboardRecentUsage :data="recentUsage" :loading="loadingUsage" /></div>
          <div class="space-y-6 xl:col-span-1">
            <UserDashboardQuickActions />
            <div class="card overflow-hidden">
              <div class="border-b border-gray-100 px-6 py-4 dark:border-dark-700">
                <div class="flex items-center justify-between gap-3">
                  <div>
                    <h2 class="text-lg font-semibold text-gray-900 dark:text-white">{{ t('checkin.title') }}</h2>
                    <p class="mt-1 text-sm text-gray-500 dark:text-dark-400">{{ t('checkin.dashboardCardDescription') }}</p>
                  </div>
                  <button class="btn btn-primary" @click="router.push('/checkin')">{{ t('checkin.openPage') }}</button>
                </div>
              </div>
              <div class="space-y-4 p-6">
                <div class="flex items-center justify-between rounded-2xl bg-gray-50 p-4 dark:bg-dark-800/50">
                  <div>
                    <p class="text-sm font-medium text-gray-500 dark:text-dark-400">{{ t('checkin.balanceLabel') }}</p>
                    <p class="mt-1 text-2xl font-bold text-gray-900 dark:text-white">${{ Number(checkInStatus?.balance ?? user?.balance ?? 0).toFixed(2) }}</p>
                  </div>
                  <div class="rounded-2xl bg-primary-100 p-3 dark:bg-primary-900/30">
                    <Icon name="badge" size="lg" class="text-primary-600 dark:text-primary-400" />
                  </div>
                </div>
                <div class="grid grid-cols-2 gap-4">
                  <div class="rounded-2xl bg-gray-50 p-4 dark:bg-dark-800/50">
                    <p class="text-sm font-medium text-gray-500 dark:text-dark-400">{{ t('checkin.todayStatus') }}</p>
                    <p class="mt-1 font-semibold text-gray-900 dark:text-white">{{ checkInStatus?.checked_in ? t('checkin.checkedInToday') : t('checkin.notCheckedInToday') }}</p>
                  </div>
                  <div class="rounded-2xl bg-gray-50 p-4 dark:bg-dark-800/50">
                    <p class="text-sm font-medium text-gray-500 dark:text-dark-400">{{ t('checkin.latestNetChange') }}</p>
                    <p class="mt-1 font-semibold" :class="(checkInStatus?.latest_net_change ?? 0) >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-rose-600 dark:text-rose-400'">{{ checkInStatus?.latest_net_change != null ? `${checkInStatus.latest_net_change >= 0 ? '+' : ''}$${Number(checkInStatus.latest_net_change).toFixed(2)}` : '--' }}</p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { usageAPI, type UserDashboardStats as UserStatsType } from '@/api/usage'
import { checkinAPI, type CheckInStatus } from '@/api/checkin'
import AppLayout from '@/components/layout/AppLayout.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import UserDashboardStats from '@/components/user/dashboard/UserDashboardStats.vue'
import UserDashboardCharts from '@/components/user/dashboard/UserDashboardCharts.vue'
import UserDashboardRecentUsage from '@/components/user/dashboard/UserDashboardRecentUsage.vue'
import UserDashboardQuickActions from '@/components/user/dashboard/UserDashboardQuickActions.vue'
import Icon from '@/components/icons/Icon.vue'
import type { UsageLog, TrendDataPoint, ModelStat } from '@/types'

const authStore = useAuthStore()
const router = useRouter()
const { t } = useI18n()
const user = computed(() => authStore.user)

const stats = ref<UserStatsType | null>(null)
const checkInStatus = ref<CheckInStatus | null>(null)
const loading = ref(false)
const loadingUsage = ref(false)
const loadingCharts = ref(false)
const trendData = ref<TrendDataPoint[]>([])
const modelStats = ref<ModelStat[]>([])
const recentUsage = ref<UsageLog[]>([])

const formatLD = (date: Date) => date.toISOString().split('T')[0]
const startDate = ref(formatLD(new Date(Date.now() - 6 * 86400000)))
const endDate = ref(formatLD(new Date()))
const granularity = ref('day')

const loadStats = async () => {
  loading.value = true
  try {
    await authStore.refreshUser()
    stats.value = await usageAPI.getDashboardStats()
  } catch (error) {
    console.error('Failed to load dashboard stats:', error)
  } finally {
    loading.value = false
  }
}

const loadCharts = async () => {
  loadingCharts.value = true
  try {
    const [trend, models] = await Promise.all([
      usageAPI.getDashboardTrend({
        start_date: startDate.value,
        end_date: endDate.value,
        granularity: granularity.value as 'day' | 'hour'
      }),
      usageAPI.getDashboardModels({
        start_date: startDate.value,
        end_date: endDate.value
      })
    ])
    trendData.value = trend.trend || []
    modelStats.value = models.models || []
  } catch (error) {
    console.error('Failed to load charts:', error)
  } finally {
    loadingCharts.value = false
  }
}

const loadRecent = async () => {
  loadingUsage.value = true
  try {
    const response = await usageAPI.getByDateRange(startDate.value, endDate.value)
    recentUsage.value = response.items.slice(0, 5)
  } catch (error) {
    console.error('Failed to load recent usage:', error)
  } finally {
    loadingUsage.value = false
  }
}

const loadCheckInStatus = async () => {
  try {
    checkInStatus.value = await checkinAPI.getStatus()
  } catch (error) {
    console.error('Failed to load check-in status:', error)
  }
}

const refreshAll = () => {
  loadStats()
  loadCharts()
  loadRecent()
  loadCheckInStatus()
}

onMounted(() => {
  refreshAll()
})
</script>

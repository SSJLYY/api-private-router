<template>
  <div class="min-h-screen bg-gradient-to-br from-gray-50 via-primary-50/30 to-gray-100 dark:from-dark-950 dark:via-dark-900 dark:to-dark-950">
    <LeaderboardBody />
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, ref, type PropType } from 'vue'
import { useI18n } from 'vue-i18n'
import Select from '@/components/common/Select.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import { getPublicConsumptionLeaderboard } from '@/api/public/leaderboard'
import type {
  AccountConsumptionRankingResponse,
  ConsumptionLeaderboardResponse,
  UserSpendingRankingResponse,
} from '@/types'

const { t } = useI18n()

const limit = ref(12)
const loading = ref(false)
const error = ref(false)
const leaderboard = ref<ConsumptionLeaderboardResponse | null>(null)

const limitOptions = computed(() => [
  { value: 8, label: t('leaderboard.top8') },
  { value: 12, label: t('leaderboard.top12') },
  { value: 20, label: t('leaderboard.top20') },
])

const backLink = '/home'
const backText = computed(() => t('leaderboard.backHome'))

const formatCost = (value: number) => {
  if (value >= 1000) return `${(value / 1000).toFixed(2)}K`
  if (value >= 1) return value.toFixed(2)
  if (value >= 0.01) return value.toFixed(3)
  return value.toFixed(4)
}

const formatNumber = (value: number) => value.toLocaleString()

const loadData = async () => {
  loading.value = true
  error.value = false
  try {
    leaderboard.value = await getPublicConsumptionLeaderboard(limit.value)
  } catch (err) {
    console.error('Failed to load public leaderboard:', err)
    error.value = true
    leaderboard.value = null
  } finally {
    loading.value = false
  }
}

const RankingCard = defineComponent({
  name: 'RankingCard',
  props: {
    title: { type: String, required: true },
    metricLabel: { type: String, required: true },
    metricField: { type: String as PropType<'actual_cost' | 'account_cost'>, required: true },
    data: {
      type: Object as PropType<UserSpendingRankingResponse | AccountConsumptionRankingResponse>,
      required: true,
    },
    kind: { type: String as PropType<'user' | 'account'>, required: true },
    highlightFirst: { type: Boolean, default: false },
  },
  setup(props) {
    const items = computed(() => props.data?.ranking ?? [])
    const total = computed(() => props.metricField === 'account_cost'
      ? (props.data as AccountConsumptionRankingResponse).total_account_cost
      : (props.data as UserSpendingRankingResponse).total_actual_cost)

    return () => h('div', { class: 'card overflow-hidden' }, [
      h('div', { class: 'border-b border-gray-100 px-5 py-4 dark:border-dark-700' }, [
        h('div', { class: 'flex items-center justify-between gap-3' }, [
          h('div', [
            h('h3', { class: 'text-base font-semibold text-gray-900 dark:text-white' }, props.title),
            h('p', { class: 'mt-1 text-xs text-gray-500 dark:text-dark-400' }, `${props.data?.start_date ?? ""} ~ ${props.data?.end_date ?? ""}`),
          ]),
          h('div', { class: 'text-right' }, [
            h('p', { class: 'text-xs text-gray-500 dark:text-dark-400' }, t('leaderboard.totalPrefix', { metric: props.metricLabel })),
            h('p', { class: 'text-lg font-bold text-primary-600 dark:text-primary-400' }, formatCost(total.value || 0)),
          ]),
        ])
      ]),
      h('div', { class: 'divide-y divide-gray-100 dark:divide-dark-700' }, items.value.length
        ? items.value.map((item: any, index: number) => h('div', {
            key: item.user_id || item.account_id || index,
            class: ['flex items-center justify-between gap-4 px-5 py-4', props.highlightFirst && index === 0 ? 'bg-amber-50 dark:bg-amber-900/10' : '']
          }, [
            h('div', { class: 'min-w-0 flex items-center gap-3' }, [
              h('div', {
                class: ['flex h-9 w-9 items-center justify-center rounded-full text-sm font-bold', index === 0 ? 'bg-amber-500 text-white' : index === 1 ? 'bg-slate-400 text-white' : index === 2 ? 'bg-orange-400 text-white' : 'bg-gray-100 text-gray-700 dark:bg-dark-700 dark:text-dark-100']
              }, `#${index + 1}`),
              h('div', { class: 'min-w-0' }, [
                h('p', { class: 'truncate text-sm font-medium text-gray-900 dark:text-white' }, props.kind === 'user'
                  ? (item.email || t('leaderboard.userFallback', { id: item.user_id }))
                  : (item.account_name || t('leaderboard.accountFallback', { id: item.account_id }))),
                h('p', { class: 'mt-1 text-xs text-gray-500 dark:text-dark-400' }, props.kind === 'user'
                  ? t('leaderboard.requestsTokens', { requests: formatNumber(item.requests), tokens: formatNumber(item.tokens) })
                  : t('leaderboard.accountRequestsTokens', {
                      platform: item.platform || t('leaderboard.unknown'),
                      requests: formatNumber(item.requests),
                      tokens: formatNumber(item.tokens),
                    })),
              ])
            ]),
            h('div', { class: 'text-right' }, [
              h('p', { class: 'text-sm font-semibold text-primary-600 dark:text-primary-400' }, formatCost(item[props.metricField] || 0)),
              props.kind === 'account'
                ? h('p', { class: 'mt-1 text-xs text-gray-500 dark:text-dark-400' }, t('leaderboard.actualCost', { cost: formatCost(item.actual_cost || 0) }))
                : null,
            ])
          ]))
        : [h('div', { class: 'px-5 py-10 text-center text-sm text-gray-500 dark:text-dark-400' }, t('leaderboard.empty'))]),
    ])
  }
})

const LeaderboardBody = defineComponent({
  name: 'LeaderboardBody',
  setup() {
    return () => h('div', { class: 'mx-auto max-w-7xl px-4 py-8 md:px-6 lg:px-8' }, [
      h('div', { class: 'mb-8 flex flex-col gap-4 md:flex-row md:items-center md:justify-between' }, [
        h('div', [
          h('h1', { class: 'text-3xl font-bold text-gray-900 dark:text-white' }, t('leaderboard.title')),
          h('p', { class: 'mt-2 text-sm text-gray-600 dark:text-dark-300' }, t('leaderboard.subtitle')),
        ]),
        h('div', { class: 'flex items-center gap-3' }, [
          h('div', { class: 'w-28' }, [
            h(Select, {
              modelValue: limit.value,
              'onUpdate:modelValue': (value: string | number | boolean | null) => {
                limit.value = typeof value === 'number' ? value : Number(value || 12)
              },
              options: limitOptions.value,
              onChange: loadData,
            })
          ]),
          h('router-link', {
            to: backLink,
            class: 'rounded-lg border border-gray-200 bg-white px-4 py-2 text-sm text-gray-700 hover:bg-gray-50 dark:border-dark-600 dark:bg-dark-800 dark:text-dark-100 dark:hover:bg-dark-700'
          }, () => backText.value)
        ])
      ]),

      loading.value
        ? h('div', { class: 'flex items-center justify-center py-16' }, [h(LoadingSpinner)])
        : error.value
          ? h('div', { class: 'card p-6 text-center text-red-500' }, t('leaderboard.loadError'))
          : leaderboard.value
            ? h('div', { class: 'space-y-8' }, [
                h('section', { class: 'space-y-4' }, [
                  h('div', { class: 'flex items-center justify-between' }, [
                    h('h2', { class: 'text-xl font-semibold text-gray-900 dark:text-white' }, t('leaderboard.userSectionTitle')),
                    h('span', { class: 'text-xs text-gray-500 dark:text-dark-400' }, t('leaderboard.userSectionHint')),
                  ]),
                  h('div', { class: 'grid grid-cols-1 gap-6 xl:grid-cols-2' }, [
                    h(RankingCard, { title: t('leaderboard.daily'), metricLabel: t('leaderboard.consumption'), metricField: 'actual_cost', data: leaderboard.value.daily, kind: 'user' }),
                    h(RankingCard, { title: t('leaderboard.weekly'), metricLabel: t('leaderboard.consumption'), metricField: 'actual_cost', data: leaderboard.value.weekly, kind: 'user' }),
                    h(RankingCard, { title: t('leaderboard.monthly'), metricLabel: t('leaderboard.consumption'), metricField: 'actual_cost', data: leaderboard.value.monthly, kind: 'user' }),
                    h(RankingCard, { title: t('leaderboard.yearlyBestEmployee'), metricLabel: t('leaderboard.consumption'), metricField: 'actual_cost', data: leaderboard.value.yearly_best_employee, kind: 'user', highlightFirst: true }),
                  ])
                ]),
                h('section', { class: 'space-y-4' }, [
                  h('div', { class: 'flex items-center justify-between' }, [
                    h('h2', { class: 'text-xl font-semibold text-gray-900 dark:text-white' }, t('leaderboard.accountSectionTitle')),
                    h('span', { class: 'text-xs text-gray-500 dark:text-dark-400' }, t('leaderboard.accountSectionHint')),
                  ]),
                  h('div', { class: 'grid grid-cols-1 gap-6 xl:grid-cols-2' }, [
                    h(RankingCard, { title: t('leaderboard.daily'), metricLabel: t('leaderboard.accountConsumption'), metricField: 'account_cost', data: leaderboard.value.account_daily, kind: 'account' }),
                    h(RankingCard, { title: t('leaderboard.weekly'), metricLabel: t('leaderboard.accountConsumption'), metricField: 'account_cost', data: leaderboard.value.account_weekly, kind: 'account' }),
                    h(RankingCard, { title: t('leaderboard.monthly'), metricLabel: t('leaderboard.accountConsumption'), metricField: 'account_cost', data: leaderboard.value.account_monthly, kind: 'account' }),
                    h(RankingCard, { title: t('leaderboard.yearlyBestEmployee'), metricLabel: t('leaderboard.accountConsumption'), metricField: 'account_cost', data: leaderboard.value.account_yearly_best_employee, kind: 'account', highlightFirst: true }),
                  ])
                ]),
              ])
            : null
    ])
  }
})

onMounted(() => {
  loadData()
})
</script>

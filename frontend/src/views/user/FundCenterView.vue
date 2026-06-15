<template>
  <AppLayout>
    <div class="mx-auto max-w-5xl space-y-6">
      <div class="relative overflow-hidden rounded-2xl bg-gradient-to-r from-indigo-600 via-purple-600 to-pink-500 px-6 py-7 shadow-lg">
        <h1 class="text-2xl font-bold tracking-tight text-white">{{ t("fund.title") }}</h1>
        <p class="mt-1 text-sm text-white/80">{{ t("fund.subtitle") }}</p>
      </div>
      <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div class="card p-4" v-for="card in statCards" :key="card.label">
          <p class="text-xs text-slate-500 dark:text-dark-400">{{ card.label }}</p>
          <p :class="card.cls" class="mt-1 text-xl font-bold">${{ card.value }}</p>
        </div>
      </div>
      <div class="flex gap-1 overflow-x-auto rounded-xl bg-gray-100 p-1 dark:bg-dark-800">
        <button v-for="tab in tabs" :key="tab.key" type="button" :class="tabClass(tab.key)" @click="activeTab = tab.key">{{ tab.label }}</button>
      </div>
      <div class="card">
        <div v-if="activeTab === "transactions"" class="divide-y divide-gray-100 dark:divide-dark-700">
          <div v-for="tx in transactions" :key="tx.id" class="flex items-center justify-between px-4 py-3">
            <div><p class="text-sm font-medium text-slate-900 dark:text-white">{{ tx.description || tx.tx_type }}</p><p class="text-xs text-slate-500">{{ tx.created_at }}</p></div>
            <p class="text-sm font-semibold">{{ tx.direction }} ${{ tx.amount.toFixed(2) }}</p>
          </div>
          <div v-if="transactions.length === 0" class="py-12 text-center text-sm text-slate-400">{{ t("fund.noData") }}</div>
        </div>
        <div v-if="activeTab === "freezes"" class="divide-y divide-gray-100 dark:divide-dark-700">
          <div v-for="f in freezes" :key="f.id" class="flex items-center justify-between px-4 py-3">
            <div><p class="text-sm font-medium">{{ f.reason }}</p><p class="text-xs text-slate-500">{{ f.frozen_at }}</p></div>
            <div class="flex items-center gap-2"><span class="text-sm font-semibold">${{ f.amount.toFixed(2) }}</span><button v-if="f.status === "frozen"" type="button" class="btn btn-secondary text-xs" @click="handleUnfreeze(f.id)">{{ t("fund.unfreezeFunds") }}</button></div>
          </div>
          <div v-if="freezes.length === 0" class="py-12 text-center text-sm text-slate-400">{{ t("fund.noData") }}</div>
        </div>
        <div v-if="activeTab === "credit"" class="p-4">
          <div v-if="creditInfo" class="space-y-3">
            <div class="flex justify-between"><span class="text-sm text-slate-500">{{ t("fund.creditLimit") }}</span><span class="text-sm font-semibold">${{ creditInfo.credit_limit.toFixed(2) }}</span></div>
            <div class="flex justify-between"><span class="text-sm text-slate-500">{{ t("fund.creditUsed") }}</span><span class="text-sm font-semibold">${{ creditInfo.credit_used.toFixed(2) }}</span></div>
            <div class="flex justify-between"><span class="text-sm text-slate-500">{{ t("fund.availableCredit") }}</span><span class="text-sm font-semibold text-emerald-600">${{ creditInfo.available_credit.toFixed(2) }}</span></div>
          </div>
          <div v-else class="py-12 text-center text-sm text-slate-400">{{ t("fund.noData") }}</div>
        </div>
        <div v-if="activeTab === "loans"" class="divide-y divide-gray-100 dark:divide-dark-700">
          <div v-for="loan in loans" :key="loan.id" class="flex items-center justify-between px-4 py-3">
            <div><p class="text-sm font-medium">${{ loan.amount.toFixed(2) }}</p><p class="text-xs text-slate-500">{{ loan.created_at }}</p></div>
            <span class="text-xs font-medium">{{ loan.status }} | ${{ loan.repaid_amount.toFixed(2) }} repaid</span>
          </div>
          <div v-if="loans.length === 0" class="py-12 text-center text-sm text-slate-400">{{ t("fund.noData") }}</div>
        </div>
        <div v-if="activeTab === "lending"" class="divide-y divide-gray-100 dark:divide-dark-700">
          <div v-for="o in lendingOffers" :key="o.id" class="flex items-center justify-between px-4 py-3">
            <div><p class="text-sm font-medium">${{ o.amount.toFixed(2) }} @ {{ (o.interest_rate * 100).toFixed(1) }}%</p><p class="text-xs text-slate-500">{{ o.duration_days }} days</p></div>
            <span class="text-xs font-medium">{{ o.status }}</span>
          </div>
          <div v-if="lendingOffers.length === 0" class="py-12 text-center text-sm text-slate-400">{{ t("fund.noData") }}</div>
        </div>
        <div v-if="activeTab === "audit"" class="divide-y divide-gray-100 dark:divide-dark-700">
          <div v-for="log in auditLogs" :key="log.id" class="flex items-center justify-between px-4 py-3">
            <div><p class="text-sm font-medium">{{ log.action }}</p><p class="text-xs text-slate-500">{{ log.description }}</p></div>
            <div class="text-right"><p v-if="log.amount" class="text-sm font-semibold">${{ log.amount.toFixed(2) }}</p><p class="text-xs text-slate-400">{{ log.created_at }}</p></div>
          </div>
          <div v-if="auditLogs.length === 0" class="py-12 text-center text-sm text-slate-400">{{ t("fund.noData") }}</div>
        </div>
      </div>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from "vue"
import { useI18n } from "vue-i18n"
import AppLayout from "@/components/layout/AppLayout.vue"
import { fundAPI, type FundStats, type FundTransaction, type FreezeRecord, type CreditInfo, type LoanRecord, type LendingOffer, type AuditLog } from "@/api/fund"
import { useAppStore } from "@/stores/app"
const { t } = useI18n()
const appStore = useAppStore()
const stats = ref<FundStats | null>(null)
const transactions = ref<FundTransaction[]>([])
const freezes = ref<FreezeRecord[]>([])
const creditInfo = ref<CreditInfo | null>(null)
const loans = ref<LoanRecord[]>([])
const lendingOffers = ref<LendingOffer[]>([])
const auditLogs = ref<AuditLog[]>([])
const activeTab = ref("transactions")
const statCards = computed(() => [
  { label: t("fund.balance"), value: (stats.value?.total_balance ?? 0).toFixed(2), cls: "text-slate-900 dark:text-white" },
  { label: t("fund.frozen"), value: (stats.value?.total_frozen ?? 0).toFixed(2), cls: "text-amber-600" },
  { label: t("fund.creditLimit"), value: (stats.value?.total_credit_limit ?? 0).toFixed(2), cls: "text-emerald-600" },
  { label: t("fund.loans"), value: (stats.value?.total_unrepaid ?? 0).toFixed(2), cls: "text-rose-600" },
])
const tabs = computed(() => [
  { key: "transactions", label: t("fund.transactions") },
  { key: "freezes", label: t("fund.freezes") },
  { key: "credit", label: t("fund.credit") },
  { key: "loans", label: t("fund.loans") },
  { key: "lending", label: t("fund.lending") },
  { key: "audit", label: t("fund.audit") },
])
function tabClass(key: string): string {
  const base = "rounded-lg px-4 py-2 text-sm font-medium transition-all"
  if (activeTab.value === key) return base + " bg-white shadow-sm dark:bg-dark-700 text-slate-900 dark:text-white"
  return base + " text-slate-500 dark:text-dark-400 hover:text-slate-700"
}
async function loadAll() {
  try {
    const [s, tx, fr, cr, ln, lo, al] = await Promise.all([
      fundAPI.getStats(),
      fundAPI.getTransactions(1, 50),
      fundAPI.getFreezes(undefined, 1, 50),
      fundAPI.getCredit().catch(() => null),
      fundAPI.getLoans(undefined, 1, 50),
      fundAPI.getLendingOffers(undefined, 1, 50),
      fundAPI.getAuditLogs(1, 50).catch(() => ({ items: [], total: 0, page: 1, page_size: 20 } as any)),
    ])
    stats.value = s
    transactions.value = tx.items ?? []
    freezes.value = fr.items ?? []
    creditInfo.value = cr
    loans.value = ln.items ?? []
    lendingOffers.value = lo.items ?? []
    auditLogs.value = al.items ?? []
  } catch (e) { console.error(e) }
}
async function handleUnfreeze(id: number) {
  try {
    await fundAPI.unfreezeFund(id)
    appStore.showSuccess("Unfrozen")
    await loadAll()
  } catch (e: any) { appStore.showError(e?.message || "Failed") }
}
onMounted(() => { loadAll() })
</script>

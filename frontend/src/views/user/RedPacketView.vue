<template>
  <AppLayout>
    <div class="mx-auto max-w-2xl space-y-5">
      <div class="relative overflow-hidden rounded-2xl bg-gradient-to-r from-[#ff355c] via-[#ff5b46] to-[#ff8c30] px-6 py-7 shadow-lg">
        <div class="relative flex items-center gap-5">
          <div class="min-w-0 flex-1">
            <h1 class="text-2xl font-bold tracking-tight text-white">{{ t("redpacket.title") }}</h1>
            <p class="mt-1 text-sm text-white/80">{{ t("redpacket.subtitle") }}</p>
          </div>
          <div class="text-right">
            <p class="text-xs text-white/70">{{ t("redpacket.currentBalance") }}</p>
            <p class="mt-1 text-2xl font-bold text-white">${{ userBalance.toFixed(2) }}</p>
          </div>
        </div>
      </div>

      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div class="card overflow-hidden">
          <div class="border-b border-gray-100 bg-rose-50/80 px-5 py-3.5 dark:border-dark-700 dark:bg-dark-800/80">
            <span class="text-sm font-semibold text-slate-900 dark:text-white">{{ t("redpacket.create") }}</span>
          </div>
          <form class="space-y-4 p-5" @submit.prevent="handleCreate">
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="input-label">{{ t("redpacket.type") }}</label>
                <div class="mt-1 grid grid-cols-2 gap-2">
                  <button type="button" :class="typeBtnClass("random")" @click="createForm.type = "random"">{{ t("redpacket.random") }}</button>
                  <button type="button" :class="typeBtnClass("equal")" @click="createForm.type = "equal"">{{ t("redpacket.equal") }}</button>
                </div>
              </div>
              <div>
                <label class="input-label">{{ t("redpacket.count") }}</label>
                <input v-model.number="createForm.count" type="number" min="1" class="input mt-1" :disabled="createLoading" placeholder="10" />
              </div>
            </div>
            <div>
              <label class="input-label">{{ t("redpacket.totalAmount") }}</label>
              <input v-model.number="createForm.total_amount" type="number" step="0.01" min="0.01" class="input mt-1" :disabled="createLoading" placeholder="100.00" />
            </div>
            <div>
              <label class="input-label">{{ t("redpacket.memo") }}</label>
              <input v-model="createForm.memo" type="text" class="input mt-1" :disabled="createLoading" :placeholder="t("redpacket.memoPlaceholder")" maxlength="255" />
            </div>
            <p v-if="createError" class="rounded-xl border border-rose-100 bg-rose-50 px-3 py-2 text-sm text-rose-600 dark:border-rose-900/30 dark:bg-rose-900/20 dark:text-rose-300">{{ createError }}</p>
            <button type="submit" :disabled="createLoading" class="btn btn-primary w-full">{{ t("redpacket.create") }}</button>
            <div v-if="createResult" class="rounded-xl border border-emerald-100 bg-emerald-50/80 p-4 dark:border-emerald-900/30 dark:bg-emerald-900/10">
              <p class="text-sm font-semibold text-emerald-700 dark:text-emerald-300">{{ t("redpacket.createdSuccess") }}</p>
              <div class="mt-2 flex items-center gap-2 rounded-xl bg-white px-3 py-2.5 shadow-sm dark:bg-dark-800">
                <span class="min-w-0 flex-1 truncate font-mono text-sm font-semibold text-slate-900 dark:text-white">{{ createResult.code }}</span>
                <button type="button" class="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition-colors hover:text-primary-500" @click="copyCode(createResult.code)">Copy</button>
              </div>
            </div>
          </form>
        </div>
        <div class="card overflow-hidden">
          <div class="border-b border-gray-100 bg-amber-50/80 px-5 py-3.5 dark:border-dark-700 dark:bg-dark-800/80">
            <span class="text-sm font-semibold text-slate-900 dark:text-white">{{ t("redpacket.claim") }}</span>
          </div>
          <div class="space-y-4 p-5">
            <div>
              <label class="input-label">{{ t("redpacket.code") }}</label>
              <input v-model="claimCode" type="text" class="input mt-1" :disabled="claimLoading" :placeholder="t("redpacket.codePlaceholder")" />
            </div>
            <p v-if="claimError" class="rounded-xl border border-rose-100 bg-rose-50 px-3 py-2 text-sm text-rose-600 dark:border-rose-900/30 dark:bg-rose-900/20 dark:text-rose-300">{{ claimError }}</p>
            <button type="button" :disabled="claimLoading || !claimCode.trim()" class="btn btn-primary w-full" @click="handleClaim">{{ t("redpacket.claim") }}</button>
            <div v-if="claimResult" class="rounded-xl border border-emerald-100 bg-gradient-to-br from-emerald-50 to-white p-5 text-center dark:border-emerald-900/30 dark:from-emerald-900/10 dark:to-dark-900">
              <p class="text-xs uppercase tracking-wider text-emerald-500">{{ t("redpacket.congrats") }}</p>
              <p class="mt-2 text-3xl font-bold text-emerald-600 dark:text-emerald-400">+${{ claimResult.amount.toFixed(2) }}</p>
              <p v-if="claimResult.is_best_luck" class="mt-2 text-sm font-semibold text-amber-500">{{ t("redpacket.bestLuck") }}</p>
            </div>
          </div>
        </div>
      </div>

      <section class="card overflow-hidden">
        <div class="flex items-center justify-between border-b border-gray-100 px-5 py-4 dark:border-dark-700">
          <h2 class="text-base font-semibold text-slate-900 dark:text-white">{{ t("redpacket.myPackets") }}</h2>
        </div>
        <div class="p-4">
          <div v-if="myLoading" class="flex items-center justify-center py-12">Loading...</div>
          <div v-else-if="myPackets.length > 0" class="space-y-3">
            <article v-for="pkt in myPackets" :key="pkt.id" class="overflow-hidden rounded-xl border border-gray-100 bg-white transition-all hover:border-gray-200 dark:border-dark-700 dark:bg-dark-800">
              <div class="flex items-center gap-3.5 px-4 py-3.5">
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-center gap-1.5">
                    <span class="text-sm font-semibold text-slate-900 dark:text-white">{{ pkt.redpacket_type === "random" ? t("redpacket.randomBadge") : t("redpacket.equalBadge") }}</span>
                    <span :class="statusBadgeClass(pkt.status)">{{ statusLabel(pkt.status) }}</span>
                  </div>
                  <p class="mt-1 text-xs text-slate-500 dark:text-dark-400">{{ pkt.total_count }}{{ t("redpacket.remainingAmount") }} ${{ pkt.remaining_amount.toFixed(2) }}</p>
                </div>
                <div class="text-right">
                  <p class="text-base font-bold text-slate-900 dark:text-white">${{ pkt.total_amount.toFixed(2) }}</p>
                </div>
              </div>
              <div class="border-t border-gray-100 px-4 py-3 dark:border-dark-700">
                <div class="flex items-center gap-2">
                  <code class="rounded bg-gray-100 px-2 py-0.5 font-mono text-xs text-slate-700 dark:bg-dark-700">{{ pkt.code }}</code>
                  <button type="button" class="flex h-6 w-6 items-center justify-center rounded text-slate-400 transition-colors hover:text-primary-500" @click="copyCode(pkt.code)">Copy</button>
                </div>
                <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-gray-100 dark:bg-dark-700">
                  <div class="h-full rounded-full bg-gradient-to-r from-rose-400 to-orange-400 transition-all duration-300" :style="{width: claimPercent(pkt) + "%"}"></div>
                </div>
              </div>
            </article>
          </div>
          <div v-else class="flex flex-col items-center justify-center py-12">
            <p class="mt-3 text-sm text-slate-400 dark:text-dark-500">{{ t("redpacket.noPackets") }}</p>
          </div>
        </div>
      </section>
    </div>
  </AppLayout>
</template>
<script setup lang="ts">
import { onMounted, ref } from "vue"
import { useI18n } from "vue-i18n"
import AppLayout from "@/components/layout/AppLayout.vue"
import { redpacketAPI, type CreateRedpacketResponse, type ClaimRedpacketResponse, type RedpacketSummary } from "@/api/redpacket"
import { useAppStore } from "@/stores/app"
import { useAuthStore } from "@/stores/auth"

const { t } = useI18n()
const appStore = useAppStore()
const authStore = useAuthStore()
const userBalance = ref(authStore.user?.balance ?? 0)
const createForm = ref({ type: "random" as "random" | "equal", total_amount: null as number | null, count: null as number | null, memo: "" })
const createLoading = ref(false)
const createError = ref("")
const createResult = ref<CreateRedpacketResponse | null>(null)
const claimCode = ref("")
const claimLoading = ref(false)
const claimError = ref("")
const claimResult = ref<ClaimRedpacketResponse | null>(null)
const myPackets = ref<RedpacketSummary[]>([])
const myLoading = ref(false)
const myTotal = ref(0)

async function handleCreate() {
  createError.value = ""
  createResult.value = null
  if (!createForm.value.total_amount || !createForm.value.count) { createError.value = t("redpacket.createFailed")); return }
  createLoading.value = true
  try {
    const result = await redpacketAPI.createRedpacket({ redpacket_type: createForm.value.type, total_amount: createForm.value.total_amount, count: createForm.value.count, memo: createForm.value.memo || undefined })
    createResult.value = result
    userBalance.value = result.balance_after
    appStore.showSuccess(t("redpacket.createdSuccess"))
    createForm.value = { type: "random", total_amount: null, count: null, memo: "" }
    await loadMyPackets()
  } catch (error: any) {
    createError.value = error?.message || t("redpacket.createFailed"))
  } finally { createLoading.value = false }
}

async function handleClaim() {
  claimError.value = ""
  claimResult.value = null
  if (!claimCode.value.trim()) return
  claimLoading.value = true
  try {
    const result = await redpacketAPI.claimRedpacket(claimCode.value.trim())
    claimResult.value = result
    userBalance.value = result.balance_after
    appStore.showSuccess(t("redpacket.claimSuccess"))
    claimCode.value = ""
    await loadMyPackets()
  } catch (error: any) {
    claimError.value = error?.message || t("redpacket.claimFailed"))
  } finally { claimLoading.value = false }
}

async function loadMyPackets() {
  myLoading.value = true
  try {
    const resp = await redpacketAPI.getMyRedpackets(1, 20)
    myPackets.value = resp.items ?? []
    myTotal.value = resp.total
  } catch (e) { myPackets.value = [] }
  finally { myLoading.value = false }
}

function copyCode(code: string) { navigator.clipboard.writeText(code) }

function statusLabel(status: string): string {
  const m: Record<string, string> = { active: t("redpacket.statusActive"), exhausted: t("redpacket.statusExhausted"), expired: t("redpacket.statusExpired") }
  return m[status] || status
}

function statusBadgeClass(status: string): string {
  const b = "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium"
  if (status === "active") return b + " bg-emerald-50 text-emerald-600 dark:bg-emerald-900/20 dark:text-emerald-400"
  if (status === "exhausted") return b + " bg-amber-50 text-amber-600 dark:bg-amber-900/20 dark:text-amber-400"
  return b + " bg-gray-100 text-gray-500 dark:bg-dark-700 dark:text-dark-400"
}

function typeBtnClass(type: string): string {
  const b = "rounded-lg border px-3 py-1.5 text-xs font-medium transition-all"
  if (createForm.value.type === type) {
    if (type === "random") return b + " border-rose-300 bg-rose-50 text-rose-600"
    return b + " border-blue-300 bg-blue-50 text-blue-600"
  }
  return b + " border-gray-200 text-gray-500 hover:border-gray-300"
}

function claimPercent(pkt: RedpacketSummary): number {
  if (pkt.total_count <= 0) return 0
  return Math.round(((pkt.total_count - pkt.remaining_count) / pkt.total_count) * 100)
}

onMounted(() => { loadMyPackets() })
</script>

import { apiClient } from './client'
import type { BasePaginationResponse } from '@/types'

export interface FundAccount {
  id: number
  account_type: string
  balance: number
  frozen_amount: number
  credit_limit: number
  credit_used: number
  available_balance: number
  status: string
  created_at: string
}

export interface FundStats {
  total_balance: number
  total_frozen: number
  total_credit_limit: number
  total_credit_used: number
  available_credit: number
  total_loan_amount: number
  total_unrepaid: number
}

export interface FundTransaction {
  id: number
  tx_type: string
  direction: string
  amount: number
  balance_before: number
  balance_after: number
  ref_type: string | null
  ref_id: number | null
  description: string
  created_at: string
}

export interface FreezeRecord {
  id: number
  amount: number
  reason: string
  status: string
  ref_type: string | null
  ref_id: number | null
  frozen_at: string
  unfrozen_at: string | null
}

export interface CreditInfo {
  id: number
  credit_limit: number
  credit_used: number
  available_credit: number
  interest_rate: number
  status: string
  approved_at: string | null
}

export interface LoanRecord {
  id: number
  amount: number
  interest_rate: number
  interest_amount: number
  repaid_amount: number
  remaining_amount: number
  status: string
  due_date: string | null
  repaid_at: string | null
  created_at: string
}

export interface LendingOffer {
  id: number
  amount: number
  interest_rate: number
  duration_days: number
  status: string
  funded_at: string | null
  created_at: string
}

export type FundTransactionList = BasePaginationResponse<FundTransaction>
export type FreezeList = BasePaginationResponse<FreezeRecord>
export type LoanList = BasePaginationResponse<LoanRecord>
export type LendingList = BasePaginationResponse<LendingOffer>

export async function getAccount(): Promise<FundAccount> {
  const { data } = await apiClient.get<FundAccount>('/fund/account')
  return data
}

export async function getStats(): Promise<FundStats> {
  const { data } = await apiClient.get<FundStats>('/fund/stats')
  return data
}

export async function getTransactions(page = 1, pageSize = 20, txType?: string): Promise<FundTransactionList> {
  const params: any = { page, page_size: pageSize }
  if (txType) params.tx_type = txType
  const { data } = await apiClient.get<FundTransactionList>('/fund/transactions', { params })
  return data
}

export async function freezeFund(amount: number, reason: string): Promise<FreezeRecord> {
  const { data } = await apiClient.post<FreezeRecord>('/fund/freeze', { amount, reason })
  return data
}

export async function unfreezeFund(freezeId: number): Promise<FreezeRecord> {
  const { data } = await apiClient.post<FreezeRecord>('/fund/unfreeze', null, { params: { freeze_id: freezeId } })
  return data
}

export async function getFreezes(status?: string, page = 1, pageSize = 20): Promise<FreezeList> {
  const params: any = { page, page_size: pageSize }
  if (status) params.status = status
  const { data } = await apiClient.get<FreezeList>('/fund/freezes', { params })
  return data
}

export async function getCredit(): Promise<CreditInfo> {
  const { data } = await apiClient.get<CreditInfo>('/fund/credit')
  return data
}

export async function takeLoan(amount: number, durationDays: number): Promise<LoanRecord> {
  const { data } = await apiClient.post<LoanRecord>('/fund/loan', { amount, duration_days: durationDays })
  return data
}

export async function repayLoan(loanId: number, amount: number): Promise<LoanRecord> {
  const { data } = await apiClient.post<LoanRecord>('/fund/repay', { loan_id: loanId, amount })
  return data
}

export async function getLoans(status?: string, page = 1, pageSize = 20): Promise<LoanList> {
  const params: any = { page, page_size: pageSize }
  if (status) params.status = status
  const { data } = await apiClient.get<LoanList>('/fund/loans', { params })
  return data
}

export async function createLendingOffer(amount: number, interestRate: number, durationDays: number): Promise<LendingOffer> {
  const { data } = await apiClient.post<LendingOffer>('/fund/lending', { amount, interest_rate: interestRate, duration_days: durationDays })
  return data
}

export async function getLendingOffers(status?: string, page = 1, pageSize = 20): Promise<LendingList> {
  const params: any = { page, page_size: pageSize }
  if (status) params.status = status
  const { data } = await apiClient.get<LendingList>('/fund/lending', { params })
  return data
}

export interface AuditLog {
  id: number
  user_id: number | null
  action: string
  target_type: string | null
  target_id: number | null
  amount: number | null
  before_value: number | null
  after_value: number | null
  description: string
  created_at: string
}

export type AuditLogList = BasePaginationResponse<AuditLog>

export async function getAuditLogs(page = 1, pageSize = 20, action?: string): Promise<AuditLogList> {
  const params: any = { page, page_size: pageSize }
  if (action) params.action = action
  const { data } = await apiClient.get<AuditLogList>('/fund/audit', { params })
  return data
}

export const fundAPI = {
  getAccount, getStats, getTransactions,
  freezeFund, unfreezeFund, getFreezes,
  getCredit, takeLoan, repayLoan, getLoans,
  createLendingOffer, getLendingOffers,
  getAuditLogs
}

export default fundAPI

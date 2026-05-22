import { apiClient } from './client'
import type { BasePaginationResponse } from '@/types'

function getUserTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

export interface CheckInStatus {
  checkin_date: string
  timezone: string
  checked_in: boolean
  latest_multiplier?: number | null
  latest_reward_amount?: number | null
  latest_net_change?: number | null
  balance?: number | null
  checked_in_at?: string | null
}

export interface CheckInCalendarDay {
  checkin_date: string
  checked_in: boolean
  stake_amount?: number | null
  reward_amount?: number | null
  multiplier?: number | null
  net_change?: number | null
  checked_in_at?: string | null
}

export interface CheckInCalendarResponse {
  year: number
  month: number
  timezone: string
  days: CheckInCalendarDay[]
}

export interface CheckInHistoryItem {
  id: number
  checkin_date: string
  timezone: string
  stake_amount: number
  reward_amount: number
  multiplier: number
  net_change: number
  balance_before: number
  balance_after: number
  checked_in_at: string
}

export type CheckInHistoryResponse = BasePaginationResponse<CheckInHistoryItem>

export interface CheckInActionResponse {
  checkin_date: string
  timezone: string
  stake_amount: number
  reward_amount: number
  multiplier: number
  net_change: number
  balance_before: number
  balance_after: number
  checked_in_at: string
}

export async function getStatus(): Promise<CheckInStatus> {
  const { data } = await apiClient.get<CheckInStatus>('/checkin/status')
  return data
}

export async function performCheckIn(stakeAmount: number): Promise<CheckInActionResponse> {
  const { data } = await apiClient.post<CheckInActionResponse>('/checkin', {
    stake_amount: stakeAmount,
    timezone: getUserTimezone(),
  })
  return data
}

export async function getCalendar(year: number, month: number): Promise<CheckInCalendarResponse> {
  const { data } = await apiClient.get<CheckInCalendarResponse>('/checkin/calendar', {
    params: { year, month }
  })
  return data
}

export async function getHistory(page = 1, pageSize = 20): Promise<CheckInHistoryResponse> {
  const { data } = await apiClient.get<CheckInHistoryResponse>('/checkin/history', {
    params: {
      page,
      page_size: pageSize
    }
  })
  return data
}

export const checkinAPI = {
  getStatus,
  performCheckIn,
  getCalendar,
  getHistory
}

export default checkinAPI

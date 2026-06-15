import { apiClient } from './client'
import type { BasePaginationResponse } from '@/types'

export interface CreateRedpacketRequest {
  redpacket_type: 'random' | 'equal'
  total_amount: number
  count: number
  memo?: string
  expire_at?: string
}

export interface CreateRedpacketResponse {
  id: number
  code: string
  redpacket_type: string
  total_amount: number
  total_count: number
  memo: string
  expire_at: string | null
  balance_after: number
  created_at: string
}

export interface ClaimRedpacketResponse {
  redpacket_id: number
  code: string
  amount: number
  is_best_luck: boolean
  balance_before: number
  balance_after: number
  claimed_at: string
}

export interface ClaimItem {
  id: number
  user_id: number
  user_email: string
  amount: number
  is_best_luck: boolean
  created_at: string
}

export interface RedpacketDetail {
  id: number
  creator_id: number
  code: string
  redpacket_type: string
  total_amount: number
  remaining_amount: number
  total_count: number
  remaining_count: number
  memo: string
  expire_at: string | null
  status: 'active' | 'exhausted' | 'expired'
  created_at: string
  claims: ClaimItem[]
}

export interface RedpacketSummary {
  id: number
  creator_id: number
  code: string
  redpacket_type: string
  total_amount: number
  remaining_amount: number
  total_count: number
  remaining_count: number
  memo: string
  expire_at: string | null
  status: string
  created_at: string
}

export type MyRedpacketsResponse = BasePaginationResponse<RedpacketSummary>

export async function createRedpacket(req: CreateRedpacketRequest): Promise<CreateRedpacketResponse> {
  const { data } = await apiClient.post<CreateRedpacketResponse>('/redpacket', req)
  return data
}

export async function claimRedpacket(code: string): Promise<ClaimRedpacketResponse> {
  const { data } = await apiClient.post<ClaimRedpacketResponse>('/redpacket/claim', { code })
  return data
}

export async function getRedpacketDetail(id: number): Promise<RedpacketDetail> {
  const { data } = await apiClient.get<RedpacketDetail>(`/redpacket/${id}`)
  return data
}

export async function getMyRedpackets(page = 1, pageSize = 20): Promise<MyRedpacketsResponse> {
  const { data } = await apiClient.get<MyRedpacketsResponse>('/redpacket/my', {
    params: { page, page_size: pageSize }
  })
  return data
}

export const redpacketAPI = {
  createRedpacket,
  claimRedpacket,
  getRedpacketDetail,
  getMyRedpackets
}

export default redpacketAPI

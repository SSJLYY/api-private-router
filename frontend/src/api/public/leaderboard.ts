import { apiClient } from '../client'
import type {
  AccountConsumptionRankingResponse,
  ConsumptionLeaderboardResponse,
  UserSpendingRankingResponse,
} from '@/types'

export interface LeaderboardRangeParams {
  start_date?: string
  end_date?: string
  limit?: number
}

export async function getPublicConsumptionLeaderboard(
  limit?: number
): Promise<ConsumptionLeaderboardResponse> {
  const { data } = await apiClient.get<ConsumptionLeaderboardResponse>('/public/leaderboard', {
    params: { limit }
  })
  return data
}

export async function getPublicUserSpendingRanking(
  params?: LeaderboardRangeParams
): Promise<UserSpendingRankingResponse> {
  const { data } = await apiClient.get<UserSpendingRankingResponse>('/public/leaderboard/users-ranking', {
    params
  })
  return data
}

export async function getPublicAccountConsumptionRanking(
  params?: LeaderboardRangeParams
): Promise<AccountConsumptionRankingResponse> {
  const { data } = await apiClient.get<AccountConsumptionRankingResponse>('/public/leaderboard/accounts-ranking', {
    params
  })
  return data
}

export const publicLeaderboardAPI = {
  getPublicConsumptionLeaderboard,
  getPublicUserSpendingRanking,
  getPublicAccountConsumptionRanking,
}

export default publicLeaderboardAPI

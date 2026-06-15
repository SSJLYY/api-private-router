import { useAppStore } from '@/stores/app'
import type { PublicSettings } from '@/types'

export type FeatureFlagMode = 'opt-in' | 'opt-out'

export interface FeatureFlagDefinition {
  /** Public-settings key used for lookup. */
  readonly key: keyof PublicSettings
  /** Resolution mode when the key is missing/undefined. */
  readonly mode: FeatureFlagMode
  /** Short human label for logs and debug tooling. */
  readonly label: string
}

function defineFlag<K extends keyof PublicSettings>(
  def: { key: K; mode: FeatureFlagMode; label: string },
): FeatureFlagDefinition {
  return def
}

/**
 * Registered feature flags. Add a new entry here when introducing a new
 * public-settings-driven switch; see the "Adding a new flag" checklist above.
 */
export const FeatureFlags = {
  channelMonitor: defineFlag({
    key: 'channel_monitor_enabled',
    mode: 'opt-out',
    label: 'Channel Monitor',
  }),
  availableChannels: defineFlag({
    key: 'available_channels_enabled',
    mode: 'opt-in',
    label: 'Available Channels',
  }),
  payment: defineFlag({
    key: 'payment_enabled',
    mode: 'opt-out',
    label: 'Payment',
  }),
  riskControl: defineFlag({
    key: 'risk_control_enabled',
    mode: 'opt-in',
    label: 'Risk Control',
  }),
  affiliate: defineFlag({
    key: 'affiliate_enabled',
    mode: 'opt-in',
    label: 'Affiliate',
  }),
  redpacket: defineFlag({
    key: 'redpacket_enabled',
    mode: 'opt-in',
    label: 'Red Packet',
  }),
  gameHall: defineFlag({
    key: 'game_hall_enabled',
    mode: 'opt-in',
    label: 'Game Hall',
  }),
  transfer: defineFlag({
    key: 'transfer_enabled',
    mode: 'opt-in',
    label: 'Transfer',
  }),
  fundCenter: defineFlag({
    key: 'fund_center_enabled',
    mode: 'opt-in',
    label: 'Fund Center',
  }),
} as const

export type RegisteredFeatureFlag = keyof typeof FeatureFlags

/**
 * Read the current value of a flag, honoring the mode's fallback.
 * 	rue  → the feature is enabled (menu/route should render).
 * alse → the feature is disabled (menu/route should hide).
 */
export function isFeatureFlagEnabled(flag: FeatureFlagDefinition): boolean {
  const appStore = useAppStore()
  const raw = appStore.cachedPublicSettings?.[flag.key] as
    | boolean
    | undefined
  if (typeof raw === 'boolean') return raw
  // Settings not yet loaded → fall back to the flag's declared mode:
  //   opt-out → visible by default, opt-in → hidden by default.
  return flag.mode === 'opt-out'
}

/**
 * Sidebar NavItem.featureFlag accepts a getter that returns
 * alse to hide. Keeping the same contract lets callers swap in
 * registry-backed flags without changing AppSidebar's filter logic.
 */
export function makeSidebarFlag(flag: FeatureFlagDefinition): () => boolean {
  return () => isFeatureFlagEnabled(flag)
}
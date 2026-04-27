import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { StrategyCode, PositionMode } from '@/utils/constants'
import { DEFAULT_STRATEGY_CODE, normalizeStrategyCode } from '@/utils/constants'

interface StrategyState {
  selectedStrategy: StrategyCode
  positionMode: PositionMode
  selectedDays: number

  setSelectedStrategy: (code: StrategyCode) => void
  setPositionMode: (mode: PositionMode) => void
  setSelectedDays: (days: number) => void
}

export const useStrategyStore = create<StrategyState>()(
  persist(
    (set) => ({
      selectedStrategy: DEFAULT_STRATEGY_CODE,
      positionMode: 'PAPER',
      selectedDays: 30,

      setSelectedStrategy: (code) => set({ selectedStrategy: normalizeStrategyCode(code) }),
      setPositionMode: (mode) => set({ positionMode: mode }),
      setSelectedDays: (days) => set({ selectedDays: days }),
    }),
    {
      name: 'strategy-storage',
      merge: (persisted, current) => {
        const persistedState = persisted as Partial<StrategyState> | undefined
        return {
          ...current,
          ...persistedState,
          selectedStrategy: normalizeStrategyCode(persistedState?.selectedStrategy),
        }
      },
    }
  )
)

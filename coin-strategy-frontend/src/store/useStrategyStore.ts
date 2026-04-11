import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { StrategyCode, PositionMode } from '@/utils/constants'

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
      selectedStrategy: 'POLYMARKET_COPY',
      positionMode: 'PAPER',
      selectedDays: 30,

      setSelectedStrategy: (code) => set({ selectedStrategy: code }),
      setPositionMode: (mode) => set({ positionMode: mode }),
      setSelectedDays: (days) => set({ selectedDays: days }),
    }),
    {
      name: 'strategy-storage',
    }
  )
)

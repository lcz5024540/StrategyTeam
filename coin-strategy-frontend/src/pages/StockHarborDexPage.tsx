import { useCallback, useEffect, useMemo, useState } from 'react'
import { BrowserProvider, Contract, JsonRpcProvider, formatUnits, parseUnits } from 'ethers'
import '../styles/stockHarbor.css'

type WalletKind = 'metamask' | 'okx'
type TradeSide = 'buy' | 'sell'

interface InjectedProvider {
  isMetaMask?: boolean
  isOkxWallet?: boolean
  providers?: InjectedProvider[]
  request: (args: { method: string; params?: unknown[] | Record<string, unknown> }) => Promise<unknown>
  on?: (event: string, handler: (...args: unknown[]) => void) => void
  removeListener?: (event: string, handler: (...args: unknown[]) => void) => void
}

declare global {
  interface Window {
    ethereum?: InjectedProvider
    okxwallet?: InjectedProvider
  }
}

const CHAIN_ID = 46630
const CHAIN_ID_HEX = `0x${CHAIN_ID.toString(16)}`
const RPC_URL = 'https://rpc.testnet.chain.robinhood.com/rpc'
const EXPLORER_URL = 'https://explorer.testnet.chain.robinhood.com'

const CONTRACTS = {
  usdc: '0x41e82dfC92215068Eb096cb8B488DaABDf69B26E',
  stockToken: '0x192370105e87e2b372031442B12f4e875203EAa9',
  governor: '0x4900Ba7e8108E28DC0ad67f92a5047f29aE836F6',
  meme: '0xF27474e078e237970a80d0be37d10b23658eC1cd',
  curve: '0xE511c51fF50CAe2710522C5c30917e7113bD64aE',
  vault: '0x2d6F4d5625DBb1C0D2CcB9BC8F5483b7e6ddb612',
} as const

const ERC20_ABI = [
  'function name() view returns (string)',
  'function symbol() view returns (string)',
  'function decimals() view returns (uint8)',
  'function balanceOf(address) view returns (uint256)',
  'function allowance(address,address) view returns (uint256)',
  'function approve(address,uint256) returns (bool)',
  'function mint(address,uint256)',
]

const CURVE_ABI = [
  'function buy(uint256,uint256,address) returns (uint256)',
  'function sell(uint256,uint256,address) returns (uint256)',
  'function buyRemaining(uint256,address) returns (uint256)',
  'function graduate() returns (address)',
  'function quoteBuy(uint256) view returns (uint256)',
  'function quoteSell(uint256) view returns (uint256)',
  'function quoteToFinish() view returns (uint256)',
  'function sold() view returns (uint256)',
  'function graduated() view returns (bool)',
  'function pool() view returns (address)',
  'function SALE_ALLOCATION() view returns (uint256)',
  'function virtualMemeReserve() view returns (uint256)',
  'function virtualQuoteReserve() view returns (uint256)',
]

const VAULT_ABI = ['function availableBalance() view returns (uint256)']
const GOVERNOR_ABI = ['function createProposal(address) returns (uint256)']

const readProvider = new JsonRpcProvider(RPC_URL)

function shortAddress(address?: string) {
  if (!address) return '未连接'
  return `${address.slice(0, 6)}...${address.slice(-4)}`
}

function findProvider(kind: WalletKind): InjectedProvider | undefined {
  if (kind === 'okx') {
    if (window.okxwallet) return window.okxwallet
    return window.ethereum?.providers?.find((provider) => provider.isOkxWallet)
  }
  return window.ethereum?.providers?.find((provider) => provider.isMetaMask && !provider.isOkxWallet)
    || (window.ethereum?.isMetaMask ? window.ethereum : undefined)
}

function formatToken(value: bigint, decimals: number, precision = 4) {
  const formatted = formatUnits(value, decimals)
  const [whole, fraction = ''] = formatted.split('.')
  const trimmed = fraction.slice(0, precision).replace(/0+$/, '')
  return trimmed ? `${whole}.${trimmed}` : whole
}

function formatUsd(value: bigint, decimals = 6) {
  return `$${Number(formatUnits(value, decimals)).toLocaleString(undefined, {
    maximumFractionDigits: 2,
  })}`
}

function parseSafeAmount(value: string, decimals: number) {
  const normalized = value.trim().replace(/,/g, '')
  if (!normalized || Number(normalized) <= 0) return 0n
  return parseUnits(normalized, decimals)
}

export function StockHarborDexPage() {
  const [walletProvider, setWalletProvider] = useState<InjectedProvider | null>(null)
  const [walletKind, setWalletKind] = useState<WalletKind | null>(null)
  const [account, setAccount] = useState('')
  const [chainId, setChainId] = useState<number | null>(null)
  const [side, setSide] = useState<TradeSide>('buy')
  const [amount, setAmount] = useState('100')
  const [quoteOut, setQuoteOut] = useState<bigint>(0n)
  const [sold, setSold] = useState<bigint>(0n)
  const [saleAllocation, setSaleAllocation] = useState<bigint>(800_000_000n * 10n ** 18n)
  const [quoteToFinish, setQuoteToFinish] = useState<bigint>(0n)
  const [vaultBalance, setVaultBalance] = useState<bigint>(0n)
  const [graduated, setGraduated] = useState(false)
  const [poolAddress, setPoolAddress] = useState('')
  const [usdcBalance, setUsdcBalance] = useState<bigint>(0n)
  const [memeBalance, setMemeBalance] = useState<bigint>(0n)
  const [stockBalance, setStockBalance] = useState<bigint>(0n)
  const [status, setStatus] = useState('连接 MetaMask 或 OKX 钱包后可在 Robinhood Testnet 上交易。')
  const [pending, setPending] = useState(false)

  const signerProvider = useMemo(() => walletProvider ? new BrowserProvider(walletProvider, 'any') : null, [walletProvider])
  const activeProvider = signerProvider || readProvider
  const networkReady = chainId === CHAIN_ID
  const amountIn = useMemo(() => parseSafeAmount(amount, side === 'buy' ? 6 : 18), [amount, side])
  const progress = saleAllocation === 0n ? 0 : Number((sold * 10000n) / saleAllocation) / 100
  const marketCapReady = progress > 0
  const vaultReady = vaultBalance >= 10_000n * 10n ** 6n
  const canCreateProposal = marketCapReady && vaultReady

  const refresh = useCallback(async () => {
    const curve = new Contract(CONTRACTS.curve, CURVE_ABI, activeProvider)
    const usdc = new Contract(CONTRACTS.usdc, ERC20_ABI, activeProvider)
    const meme = new Contract(CONTRACTS.meme, ERC20_ABI, activeProvider)
    const stock = new Contract(CONTRACTS.stockToken, ERC20_ABI, activeProvider)
    const vault = new Contract(CONTRACTS.vault, VAULT_ABI, activeProvider)

    const [soldValue, saleValue, quoteToFinishValue, graduatedValue, poolValue, vaultValue] = await Promise.all([
      curve.sold() as Promise<bigint>,
      curve.SALE_ALLOCATION() as Promise<bigint>,
      curve.quoteToFinish() as Promise<bigint>,
      curve.graduated() as Promise<boolean>,
      curve.pool() as Promise<string>,
      vault.availableBalance() as Promise<bigint>,
    ])

    setSold(soldValue)
    setSaleAllocation(saleValue)
    setQuoteToFinish(quoteToFinishValue)
    setGraduated(graduatedValue)
    setPoolAddress(poolValue)
    setVaultBalance(vaultValue)

    if (amountIn > 0n) {
      const quoted = side === 'buy'
        ? await curve.quoteBuy(amountIn) as bigint
        : await curve.quoteSell(amountIn) as bigint
      setQuoteOut(quoted)
    } else {
      setQuoteOut(0n)
    }

    if (account) {
      const [usdcValue, memeValue, stockValue] = await Promise.all([
        usdc.balanceOf(account) as Promise<bigint>,
        meme.balanceOf(account) as Promise<bigint>,
        stock.balanceOf(account) as Promise<bigint>,
      ])
      setUsdcBalance(usdcValue)
      setMemeBalance(memeValue)
      setStockBalance(stockValue)
    }
  }, [account, activeProvider, amountIn, side])

  const ensureNetwork = useCallback(async (provider: InjectedProvider) => {
    try {
      await provider.request({ method: 'wallet_switchEthereumChain', params: [{ chainId: CHAIN_ID_HEX }] })
    } catch (error) {
      const code = typeof error === 'object' && error && 'code' in error ? (error as { code?: number }).code : undefined
      if (code !== 4902) throw error
      await provider.request({
        method: 'wallet_addEthereumChain',
        params: [{
          chainId: CHAIN_ID_HEX,
          chainName: 'Robinhood Chain Testnet',
          nativeCurrency: { name: 'ETH', symbol: 'ETH', decimals: 18 },
          rpcUrls: [RPC_URL],
          blockExplorerUrls: [EXPLORER_URL],
        }],
      })
    }
  }, [])

  const connectWallet = useCallback(async (kind: WalletKind) => {
    const provider = findProvider(kind)
    if (!provider) {
      setStatus(kind === 'metamask' ? '没有检测到 MetaMask。' : '没有检测到 OKX Web3 钱包。')
      return
    }

    setPending(true)
    try {
      await ensureNetwork(provider)
      const accounts = await provider.request({ method: 'eth_requestAccounts' }) as string[]
      const currentChain = await provider.request({ method: 'eth_chainId' }) as string
      setWalletProvider(provider)
      setWalletKind(kind)
      setAccount(accounts[0] || '')
      setChainId(Number.parseInt(currentChain, 16))
      setStatus(`${kind === 'metamask' ? 'MetaMask' : 'OKX Wallet'} 已连接。`)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '钱包连接失败。')
    } finally {
      setPending(false)
    }
  }, [ensureNetwork])

  const getSigner = useCallback(async () => {
    if (!walletProvider || !signerProvider || !account) throw new Error('请先连接钱包。')
    await ensureNetwork(walletProvider)
    return signerProvider.getSigner()
  }, [account, ensureNetwork, signerProvider, walletProvider])

  const faucet = useCallback(async () => {
    setPending(true)
    try {
      const signer = await getSigner()
      const usdc = new Contract(CONTRACTS.usdc, ERC20_ABI, signer)
      const tx = await usdc.mint(account, parseUnits('10000', 6))
      setStatus(`测试 USDC 领取交易已提交：${tx.hash}`)
      await tx.wait()
      setStatus('已领取 10,000 MockUSDC。')
      await refresh()
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '领取测试 USDC 失败。')
    } finally {
      setPending(false)
    }
  }, [account, getSigner, refresh])

  const approveIfNeeded = useCallback(async (tokenAddress: string, spender: string, required: bigint) => {
    const signer = await getSigner()
    const owner = await signer.getAddress()
    const token = new Contract(tokenAddress, ERC20_ABI, signer)
    const allowance = await token.allowance(owner, spender) as bigint
    if (allowance >= required) return
    const tx = await token.approve(spender, required)
    setStatus(`授权交易已提交：${tx.hash}`)
    await tx.wait()
  }, [getSigner])

  const trade = useCallback(async () => {
    if (amountIn <= 0n) {
      setStatus('请输入有效数量。')
      return
    }
    setPending(true)
    try {
      const signer = await getSigner()
      const curve = new Contract(CONTRACTS.curve, CURVE_ABI, signer)
      const minOut = quoteOut * 99n / 100n
      if (side === 'buy') {
        await approveIfNeeded(CONTRACTS.usdc, CONTRACTS.curve, amountIn)
        const tx = await curve.buy(amountIn, minOut, account)
        setStatus(`买入交易已提交：${tx.hash}`)
        await tx.wait()
        setStatus('买入完成。')
      } else {
        await approveIfNeeded(CONTRACTS.meme, CONTRACTS.curve, amountIn)
        const tx = await curve.sell(amountIn, minOut, account)
        setStatus(`卖出交易已提交：${tx.hash}`)
        await tx.wait()
        setStatus('卖出完成。')
      }
      await refresh()
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '交易失败。')
    } finally {
      setPending(false)
    }
  }, [account, amountIn, approveIfNeeded, getSigner, quoteOut, refresh, side])

  const createProposal = useCallback(async () => {
    setPending(true)
    try {
      const signer = await getSigner()
      const governor = new Contract(CONTRACTS.governor, GOVERNOR_ABI, signer)
      const tx = await governor.createProposal(CONTRACTS.meme)
      setStatus(`回购提案交易已提交：${tx.hash}`)
      await tx.wait()
      setStatus('回购提案已创建。')
      await refresh()
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '创建提案失败，可能尚未满足金库/市值门槛。')
    } finally {
      setPending(false)
    }
  }, [getSigner, refresh])

  useEffect(() => {
    refresh().catch((error) => setStatus(error instanceof Error ? error.message : '链上数据读取失败。'))
  }, [refresh])

  useEffect(() => {
    if (!walletProvider) return
    const handleAccounts = (accounts: unknown) => setAccount(Array.isArray(accounts) ? String(accounts[0] || '') : '')
    const handleChain = (id: unknown) => setChainId(typeof id === 'string' ? Number.parseInt(id, 16) : null)
    walletProvider.on?.('accountsChanged', handleAccounts)
    walletProvider.on?.('chainChanged', handleChain)
    return () => {
      walletProvider.removeListener?.('accountsChanged', handleAccounts)
      walletProvider.removeListener?.('chainChanged', handleChain)
    }
  }, [walletProvider])

  return (
    <div className="sh-page">
      <section className="sh-hero">
        <div>
          <div className="sh-kicker">Stock-token reward Meme DEX · Robinhood Testnet</div>
          <h1>Apple Cult / APEM</h1>
          <p>真实连接 Robinhood Chain Testnet 合约：领取 MockUSDC、授权、买入、卖出、查看奖励金库。</p>
        </div>
        <div className="sh-wallet-card">
          <div className="sh-wallet-row">
            <button onClick={() => connectWallet('metamask')} disabled={pending}>
              MetaMask
            </button>
            <button onClick={() => connectWallet('okx')} disabled={pending}>
              OKX Wallet
            </button>
          </div>
          <strong>{shortAddress(account)}</strong>
          <span>{walletKind ? `${walletKind.toUpperCase()} · ` : ''}{networkReady ? 'Robinhood Testnet' : '请切换 Robinhood Testnet'}</span>
        </div>
      </section>

      <section className="sh-grid sh-stats">
        <div><span>曲线进度</span><strong>{progress.toFixed(2)}%</strong><em>{formatToken(sold, 18, 2)} / 800M APEM</em></div>
        <div><span>完成曲线还需</span><strong>{formatUsd(quoteToFinish)}</strong><em>MockUSDC</em></div>
        <div><span>奖励金库</span><strong>{formatUsd(vaultBalance)}</strong><em>{vaultReady ? '可创建提案' : '未达 10,000 USDC'}</em></div>
        <div><span>阶段</span><strong>{graduated ? 'AMM' : 'Bonding Curve'}</strong><em>{graduated ? shortAddress(poolAddress) : '公平发射中'}</em></div>
      </section>

      <div className="sh-main-grid">
        <section className="sh-panel sh-chart-panel">
          <div className="sh-panel-head">
            <div>
              <h2>链上市场</h2>
              <p>APEM 永久绑定 AAPL 股票代币奖励标的。</p>
            </div>
            <button onClick={() => refresh()} disabled={pending}>刷新链上状态</button>
          </div>
          <div className="sh-progress-track"><div style={{ width: `${Math.min(progress, 100)}%` }} /></div>
          <div className="sh-market-meta">
            <div><span>Meme Token</span><strong>{CONTRACTS.meme}</strong></div>
            <div><span>Bonding Curve</span><strong>{CONTRACTS.curve}</strong></div>
            <div><span>Fee Vault</span><strong>{CONTRACTS.vault}</strong></div>
            <div><span>Mock USDC</span><strong>{CONTRACTS.usdc}</strong></div>
          </div>
        </section>

        <section className="sh-panel sh-trade-panel">
          <div className="sh-tabs">
            <button className={side === 'buy' ? 'active' : ''} onClick={() => { setSide('buy'); setAmount('100') }}>买入</button>
            <button className={side === 'sell' ? 'active' : ''} onClick={() => { setSide('sell'); setAmount('1000') }}>卖出</button>
          </div>
          <label>{side === 'buy' ? '支付 USDC' : '卖出 APEM'}</label>
          <div className="sh-amount">
            <input value={amount} onChange={(event) => setAmount(event.target.value)} inputMode="decimal" />
            <span>{side === 'buy' ? 'USDC' : 'APEM'}</span>
          </div>
          <div className="sh-quote">
            <span>预计获得</span>
            <strong>{side === 'buy' ? `${formatToken(quoteOut, 18, 2)} APEM` : `${formatToken(quoteOut, 6, 2)} USDC`}</strong>
          </div>
          <div className="sh-fees">
            <div><span>股票奖励金库</span><strong>0.50%</strong></div>
            <div><span>LP / 曲线储备</span><strong>0.30%</strong></div>
            <div><span>协议金库</span><strong>0.20%</strong></div>
          </div>
          <button className="sh-primary" onClick={trade} disabled={pending || !account || !networkReady || quoteOut === 0n}>
            {pending ? '处理中...' : side === 'buy' ? '授权并买入' : '授权并卖出'}
          </button>
          <button className="sh-secondary" onClick={faucet} disabled={pending || !account || !networkReady}>
            领取 10,000 MockUSDC
          </button>
        </section>
      </div>

      <section className="sh-grid sh-account-grid">
        <div className="sh-panel">
          <h2>我的资产</h2>
          <div className="sh-balance-row"><span>USDC</span><strong>{formatToken(usdcBalance, 6, 2)}</strong></div>
          <div className="sh-balance-row"><span>APEM</span><strong>{formatToken(memeBalance, 18, 2)}</strong></div>
          <div className="sh-balance-row"><span>rAAPL</span><strong>{formatToken(stockBalance, 18, 4)}</strong></div>
        </div>
        <div className="sh-panel">
          <h2>回购治理</h2>
          <p className="sh-muted">金库达到 10,000 USDC 且市值门槛满足后，可创建回购提案。</p>
          <button className="sh-secondary" onClick={createProposal} disabled={pending || !account || !networkReady || !canCreateProposal}>
            {canCreateProposal ? '创建回购提案' : '等待门槛达成'}
          </button>
        </div>
        <div className="sh-panel">
          <h2>状态</h2>
          <p className="sh-status">{status}</p>
          <a href={`${EXPLORER_URL}/address/${CONTRACTS.meme}`} target="_blank" rel="noreferrer">查看 Meme 合约</a>
        </div>
      </section>
    </div>
  )
}

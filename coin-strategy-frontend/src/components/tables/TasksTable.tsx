import type { Task } from '@/types/task'
import { compactAddress, formatMoney, formatPercent, truncateText } from '@/utils/format'

interface TasksTableProps {
  tasks: Task[]
  type: 'executable' | 'opportunity'
}

export function TasksTable({ tasks, type }: TasksTableProps) {
  if (tasks.length === 0) {
    return (
      <div className="table-wrap">
        <table>
          <tbody>
            <tr>
              <td className="empty-state">
                {type === 'executable' ? '暂无可执行任务' : '暂无机会任务'}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    )
  }

  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>钱包</th>
            <th>市场</th>
            <th>Outcome</th>
            <th>推荐仓位</th>
            <th>跟投概率</th>
            <th>阻塞</th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((task) => (
            <tr key={task.id}>
              <td title={task.walletAddress}>{compactAddress(task.walletAddress)}</td>
              <td title={task.question}>{truncateText(task.question, 36)}</td>
              <td>{task.outcome}</td>
              <td>{formatMoney(task.recommendedStakeUsdc)}</td>
              <td>{formatPercent(task.calibratedFollowProbability || task.followProbability)}</td>
              <td>
                {task.blockerCount > 0 ? (
                  <span className="badge warn">{task.blockerCount} 个</span>
                ) : (
                  <span className="badge accent">通过</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// Collector worklist logic over Camunda's REST task API (GET /engine-rest/task + process variables).

export interface CamundaTask {
  id: string;
  name: string;
  taskDefinitionKey: string; // callCustomer | findContact | approveAgency
  created: string;
  due: string | null;
  assignee: string | null;
  processInstanceId: string;
}

export interface AccountVars {
  customerId: string;
  customerName: string;
  overdueAmount: number; // dollars
  daysLate: number;
  segment: 'STRATEGIC' | 'STANDARD' | 'SMALL';
  reason: string;
}

export interface WorkItem extends CamundaTask, AccountVars {
  priority: number;
  slaBreached: boolean;
  hoursToDue: number | null;
}

/**
 * Expected recovery per call: bigger and older balances first, strategic accounts boosted,
 * overdue tasks jump the queue. Log-scaled so one huge account doesn't bury everything else.
 */
export function priority(v: AccountVars, hoursToDue: number | null): number {
  const amount = Math.log10(Math.max(1, v.overdueAmount));
  const age = Math.min(v.daysLate, 120) / 30;
  const seg = v.segment === 'STRATEGIC' ? 1.5 : v.segment === 'SMALL' ? 0.6 : 1;
  const sla = hoursToDue !== null && hoursToDue < 0 ? 2 : hoursToDue !== null && hoursToDue < 8 ? 1 : 0;
  return Math.round((amount * 2 + age) * seg * 100 + sla * 300) / 100;
}

export function buildWorklist(tasks: CamundaTask[], vars: Record<string, AccountVars>, now: number, me: string | null = null): WorkItem[] {
  return tasks
    .filter((t) => vars[t.processInstanceId])
    .filter((t) => me === null || t.assignee === null || t.assignee === me)
    .map((t) => {
      const v = vars[t.processInstanceId];
      const hoursToDue = t.due ? (Date.parse(t.due) - now) / 3_600_000 : null;
      return { ...t, ...v, hoursToDue, slaBreached: hoursToDue !== null && hoursToDue < 0, priority: priority(v, hoursToDue) };
    })
    .sort((a, b) => b.priority - a.priority || a.created.localeCompare(b.created));
}

/** Body for POST /engine-rest/task/{id}/complete after a call. */
export function completeCallBody(outcome: 'PROMISE_TO_PAY' | 'DISPUTE' | 'NO_ANSWER', promiseDate?: string, today = new Date()) {
  if (outcome === 'PROMISE_TO_PAY') {
    if (!promiseDate || !/^\d{4}-\d{2}-\d{2}$/.test(promiseDate)) throw new Error('promise date required (YYYY-MM-DD)');
    const d = Date.parse(promiseDate + 'T00:00:00Z');
    const t = Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate());
    if (d < t) throw new Error('promise date is in the past');
    if (d > t + 45 * 86_400_000) throw new Error('promises over 45 days need a payment plan instead');
  }
  return {
    variables: {
      callOutcome: { value: outcome, type: 'String' },
      ...(promiseDate ? { promiseDate: { value: promiseDate, type: 'String' } } : {}),
    },
  };
}

import { describe, it, expect } from 'vitest';
import { buildWorklist, completeCallBody, priority, CamundaTask, AccountVars } from '../src/worklist';

const now = Date.parse('2023-09-01T12:00:00Z');
const task = (id: string, pi: string, due: string | null, assignee: string | null = null): CamundaTask =>
  ({ id, name: 'Call customer', taskDefinitionKey: 'callCustomer', created: '2023-08-30T09:00:00Z', due, assignee, processInstanceId: pi });
const v = (amount: number, days: number, segment: AccountVars['segment'] = 'STANDARD'): AccountVars =>
  ({ customerId: 'C', customerName: 'Cust', overdueAmount: amount, daysLate: days, segment, reason: '' });

describe('worklist', () => {
  it('prioritises bigger, older, strategic balances and SLA breaches', () => {
    expect(priority(v(50_000, 40), null)).toBeGreaterThan(priority(v(500, 40), null));
    expect(priority(v(5_000, 80), null)).toBeGreaterThan(priority(v(5_000, 20), null));
    expect(priority(v(5_000, 40, 'STRATEGIC'), null)).toBeGreaterThan(priority(v(5_000, 40), null));
    expect(priority(v(500, 40), -1)).toBeGreaterThan(priority(v(500, 40), 48));
  });

  it('builds a sorted list, hides others\' claimed tasks, flags breaches', () => {
    const tasks = [task('t1', 'p1', '2023-09-03T00:00:00Z'), task('t2', 'p2', '2023-08-31T00:00:00Z'), task('t3', 'p3', null, 'kim'), task('t4', 'missing', null)];
    const vars = { p1: v(1_000, 30), p2: v(1_000, 30), p3: v(90_000, 100) };
    const list = buildWorklist(tasks, vars, now, 'alex');
    expect(list.map((t) => t.id)).toEqual(['t2', 't1']);
    expect(list[0].slaBreached).toBe(true);
    expect(buildWorklist(tasks, vars, now).map((t) => t.id)[0]).toBe('t3');
  });

  it('validates promise-to-pay before completing the task', () => {
    const today = new Date('2023-09-01T10:00:00Z');
    expect(completeCallBody('PROMISE_TO_PAY', '2023-09-15', today).variables.promiseDate?.value).toBe('2023-09-15');
    expect(() => completeCallBody('PROMISE_TO_PAY', undefined, today)).toThrow(/required/);
    expect(() => completeCallBody('PROMISE_TO_PAY', '2023-08-01', today)).toThrow(/past/);
    expect(() => completeCallBody('PROMISE_TO_PAY', '2023-12-31', today)).toThrow(/payment plan/);
    expect(completeCallBody('NO_ANSWER')).toEqual({ variables: { callOutcome: { value: 'NO_ANSWER', type: 'String' } } });
  });
});

import { useCallback, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { AccountVars, buildWorklist, CamundaTask, completeCallBody, WorkItem } from './worklist';

const API = '/engine-rest';
const ME = new URLSearchParams(location.search).get('user') ?? 'collector1';

async function json<T>(url: string, init?: RequestInit): Promise<T> {
  const r = await fetch(url, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } });
  if (!r.ok) throw new Error(`${r.status} ${await r.text()}`);
  return r.status === 204 ? (undefined as T) : r.json();
}

async function load(): Promise<WorkItem[]> {
  const tasks = await json<CamundaTask[]>(`${API}/task?candidateGroup=collectors&includeAssignedTasks=true&taskDefinitionKey=callCustomer`);
  const vars: Record<string, AccountVars> = {};
  await Promise.all([...new Set(tasks.map((t) => t.processInstanceId))].map(async (pi) => {
    const raw = await json<Record<string, { value: unknown }>>(`${API}/process-instance/${pi}/variables`);
    vars[pi] = {
      customerId: String(raw.customerId?.value), customerName: String(raw.customerName?.value ?? raw.customerId?.value),
      overdueAmount: Number(raw.overdueAmount?.value ?? 0), daysLate: Number(raw.daysLate?.value ?? 0),
      segment: (raw.segment?.value as AccountVars['segment']) ?? 'STANDARD', reason: String(raw.reason?.value ?? ''),
    };
  }));
  return buildWorklist(tasks, vars, Date.now(), ME);
}

function App() {
  const [items, setItems] = useState<WorkItem[]>([]);
  const [active, setActive] = useState<WorkItem | null>(null);
  const [promise, setPromise] = useState('');
  const [error, setError] = useState<string | null>(null);
  const refresh = useCallback(() => load().then(setItems).catch((e) => setError(e.message)), []);
  useEffect(() => { refresh(); const t = setInterval(refresh, 60_000); return () => clearInterval(t); }, [refresh]);

  async function claim(t: WorkItem) {
    if (!t.assignee) await json(`${API}/task/${t.id}/claim`, { method: 'POST', body: JSON.stringify({ userId: ME }) });
    setActive({ ...t, assignee: ME });
  }

  async function complete(outcome: 'PROMISE_TO_PAY' | 'DISPUTE' | 'NO_ANSWER') {
    if (!active) return;
    try {
      await json(`${API}/task/${active.id}/complete`, { method: 'POST', body: JSON.stringify(completeCallBody(outcome, promise || undefined)) });
      setActive(null); setPromise(''); refresh();
    } catch (e) { setError((e as Error).message); }
  }

  return (
    <main style={{ fontFamily: 'system-ui', padding: 16, maxWidth: 900 }}>
      <h1>Collections worklist</h1>
      {error && <p style={{ color: '#c62828' }}>{error}</p>}
      <table width="100%">
        <thead><tr><th align="left">Customer</th><th align="right">Overdue</th><th align="right">Days</th><th>Due</th><th /></tr></thead>
        <tbody>
          {items.map((t) => (
            <tr key={t.id} style={{ background: t.slaBreached ? '#fdecea' : undefined }}>
              <td>{t.customerName} {t.segment === 'STRATEGIC' && <b>★</b>}<div style={{ fontSize: 12, color: '#666' }}>{t.reason}</div></td>
              <td align="right">${t.overdueAmount.toLocaleString()}</td>
              <td align="right">{t.daysLate}</td>
              <td align="center">{t.hoursToDue === null ? '–' : t.slaBreached ? 'overdue' : `${Math.round(t.hoursToDue)}h`}</td>
              <td><button onClick={() => claim(t)}>{t.assignee === ME ? 'Open' : 'Claim'}</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {active && (
        <section style={{ marginTop: 24, borderTop: '1px solid #ddd', paddingTop: 12 }}>
          <h2>Call {active.customerName}</h2>
          <label>Promise date <input type="date" value={promise} onChange={(e) => setPromise(e.target.value)} /></label>
          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            <button onClick={() => complete('PROMISE_TO_PAY')} disabled={!promise}>Promise to pay</button>
            <button onClick={() => complete('DISPUTE')}>Dispute</button>
            <button onClick={() => complete('NO_ANSWER')}>No answer</button>
          </div>
        </section>
      )}
    </main>
  );
}

createRoot(document.getElementById('root')!).render(<App />);

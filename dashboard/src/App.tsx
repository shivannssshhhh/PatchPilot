import { useCallback, useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Activity, ArrowDownRight, ArrowUpRight, Check, ChevronDown, CircleHelp, Clock3, Command, ExternalLink, GitPullRequest, RefreshCw, Search, ShieldCheck, ShieldX, Sparkles, X } from 'lucide-react';

const API = import.meta.env.VITE_API_URL || 'http://localhost:8000';
type Incident = { id: string; status: string; payload: Record<string, any>; createdAt: string; updatedAt: string; declineReport?: string | null };
type Detail = { incident: Incident; steps: { name: string; status: string; details: Record<string, any> }[]; artifacts: { type: string; content: any }[] };
const stages = ['Capture', 'Triage', 'Reproduce', 'Patch', 'Verify', 'Score', 'Deliver', 'Review'];
const pretty = (s: string) => s?.replaceAll('_', ' ') || 'unknown';
const bugOptions = [
  { id: 'pagination', label: 'Pagination', path: '/orders?page=2', method: 'GET', body: undefined },
  { id: 'users', label: 'User profile', path: '/users/1', method: 'GET', body: undefined },
  { id: 'discount', label: 'Discount code', path: '/cart/discount', method: 'POST', body: { code: 'FORGE10', subtotal: 10 } },
  { id: 'inventory', label: 'Inventory sync', path: '/inventory/sync', method: 'POST', body: { items: [] } },
  { id: 'payments', label: 'Payments (refusal demo)', path: '/payments/charge', method: 'POST', body: { amount: 5 } },
];

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API}${path}`, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } });
  if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
  return response.json();
}

export default function App() {
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [filter, setFilter] = useState('all');
  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState('');
  const [demoOpen, setDemoOpen] = useState(false);
  const [activeBug, setActiveBug] = useState('pagination');
  const [apiState, setApiState] = useState<'online'|'offline'|'checking'>('checking');

  const refresh = useCallback(async () => {
    try {
      const [rows] = await Promise.all([request<Incident[]>('/api/incidents'), request('/api/health')]);
      setIncidents(rows); setApiState('online');
      if (!selected && rows[0]) setSelected(rows[0].id);
    } catch { setApiState('offline'); }
  }, [selected]);

  useEffect(() => { void refresh(); const timer = window.setInterval(refresh, 5000); return () => clearInterval(timer); }, [refresh]);
  useEffect(() => {
    if (!selected) { setDetail(null); return; }
    let alive = true;
    request<Detail>(`/api/incidents/${selected}`).then(v => { if (alive) setDetail(v); }).catch(() => { if (alive) setDetail(null); });
    return () => { alive = false; };
  }, [selected, incidents]);
  useEffect(() => {
    if (!selected || apiState !== 'online') return;
    const stream = new EventSource(`${API}/api/incidents/events`);
    stream.addEventListener('incident', () => void refresh());
    return () => stream.close();
  }, [selected, apiState, refresh]);
  useEffect(() => { if (!toast) return; const timer = window.setTimeout(() => setToast(''), 3400); return () => clearTimeout(timer); }, [toast]);

  const filtered = useMemo(() => incidents.filter(i => {
    const matchesFilter = filter === 'all' || (filter === 'review' ? i.status === 'ready_for_review' : i.status === filter);
    const text = `${i.id} ${i.payload?.message ?? ''} ${i.payload?.request?.path ?? ''}`.toLowerCase();
    return matchesFilter && text.includes(query.toLowerCase());
  }), [incidents, filter, query]);
  const today = incidents.filter(i => new Date(i.createdAt).toDateString() === new Date().toDateString()).length;
  const reviewCount = incidents.filter(i => i.status === 'ready_for_review').length;
  const declinedCount = incidents.filter(i => i.status === 'declined').length;

  const act = async (action: 'approve'|'reject'|'retry') => {
    if (!selected) return;
    setBusy(true);
    try { await request(`/api/incidents/${selected}/${action}`, { method: 'POST', body: action === 'reject' ? JSON.stringify({ reason: 'Rejected from dashboard review' }) : undefined }); await refresh(); setToast(`Incident ${action}d`); }
    catch (e) { setToast(e instanceof Error ? e.message : 'Action failed'); }
    finally { setBusy(false); }
  };
  const trigger = async () => {
    setBusy(true);
    const bug = bugOptions.find(v => v.id === activeBug)!;
    try {
      await fetch('http://localhost:5000/__demo/bugs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ active: [bug.id] }) });
      await fetch(`http://localhost:5000${bug.path}`, { method: bug.method, headers: { 'Content-Type': 'application/json' }, body: bug.body ? JSON.stringify(bug.body) : undefined });
      await refresh(); setToast(`${bug.label} bug triggered. Incident capture may take a moment.`); setDemoOpen(false);
    } catch { setToast('Could not reach ShopLite at localhost:5000. Start the sample app and try again.'); }
    finally { setBusy(false); }
  };
  const resetDemo = async () => {
    try {
      const response = await fetch('http://localhost:5000/__demo/bugs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ active: [] }) });
      if (!response.ok) throw new Error('ShopLite did not accept the reset');
      setToast('Seeded bugs disabled. Incident history is unchanged.');
    } catch { setToast('Could not reach ShopLite at localhost:5000.'); }
  };

  const statusIndex: Record<string, number> = { captured: 0, triaging: 1, reproducing: 2, patching: 3, verifying: 4, scoring: 5, ready_for_review: 7, approved: 7, rejected: 7, failed: 2, declined: 2 };
  const activeIndex = statusIndex[detail?.incident.status || 'captured'] ?? 0;
  const payload = detail?.incident.payload || {};
  const route = payload.request?.path || 'Unknown route';
  const exception = payload.exceptionType || 'Captured incident';

  return <div className="shell">
    <aside className="sidebar">
      <a className="brand" href="#"><span className="brand-mark"><Sparkles size={18}/></span><span>patchpilot<small>SUPERVISED REPAIR</small></span></a>
      <div className="workspace"><span className="workspace-dot"/> ShopLite demo <ChevronDown size={14}/></div>
      <div className="nav-label">WORKSPACE</div>
      <button className="nav-item active"><Activity size={17}/> Overview <span className="nav-count">{incidents.length}</span></button>
      <button className="nav-item" onClick={() => setFilter('review')}><ShieldCheck size={17}/> Needs review <span className="nav-count amber">{reviewCount}</span></button>
      <button className="nav-item" onClick={() => setDemoOpen(true)}><Command size={17}/> Demo controls</button>
      <div className="sidebar-bottom"><div className="safety-card"><ShieldCheck size={17}/><div><b>Human approval required</b><span>PatchPilot never deploys fixes.</span></div></div><button className="nav-item"><CircleHelp size={17}/> Docs & help</button><div className="user-row"><div className="avatar">PP</div><div><b>Local workspace</b><span>Community edition</span></div><ChevronDown size={14}/></div></div>
    </aside>

    <main className="main">
      <header className="topbar"><div className="breadcrumbs">Workspace <span>/</span> <b>Overview</b></div><div className="top-actions"><span className={`connection ${apiState}`}><i/> API {apiState}</span><button className="icon-btn" title="Refresh" onClick={() => void refresh()}><RefreshCw size={16}/></button><button className="help-button"><CircleHelp size={16}/> Help</button></div></header>
      <div className="content">
        <div className="page-heading"><div><div className="eyebrow">TUESDAY, {new Date().toLocaleDateString('en-US',{month:'long',day:'numeric',year:'numeric'}).toUpperCase()}</div><h1>Good evening, builder <span>✦</span></h1><p>Keep your app healthy. Review every proposed fix with evidence.</p></div><button className="primary-btn" onClick={() => setDemoOpen(true)}><Sparkles size={16}/> Trigger a demo bug</button></div>
        <section className="stats-grid">
          <Stat icon={<Activity size={16}/>} label="Incidents today" value={today} foot="From ShopLite" tone="cyan" trend={<ArrowUpRight size={14}/>}/>
          <Stat icon={<GitPullRequest size={16}/>} label="Ready for review" value={reviewCount} foot="Human decision needed" tone="violet" trend={<Clock3 size={14}/>}/>
          <Stat icon={<ShieldX size={16}/>} label="Declined safely" value={declinedCount} foot="Sensitive or unverified" tone="rose" trend={<ArrowDownRight size={14}/>}/>
          <Stat icon={<ShieldCheck size={16}/>} label="Trust checks" value="—" foot="Evidence based" tone="green" trend={<Check size={14}/>}/>
        </section>

        <div className="section-head"><div><h2>Incident feed</h2><p>Live updates from your application</p></div><button className="subtle-btn" onClick={() => void refresh()}><RefreshCw size={14}/> Refresh</button></div>
        <div className="feed-layout">
          <section className="feed-card">
            <div className="feed-toolbar"><div className="filter-tabs">{[['all','All'],['review','Needs review'],['approved','Approved'],['declined','Declined'],['failed','Failed']].map(([id,label])=><button key={id} className={filter===id?'selected':''} onClick={()=>setFilter(id)}>{label}{id==='all'&&<span>{incidents.length}</span>}</button>)}</div><label className="search"><Search size={15}/><input placeholder="Search incidents…" value={query} onChange={e=>setQuery(e.target.value)}/><kbd>⌘ K</kbd></label></div>
            <div className="incident-list">{filtered.length ? filtered.map(i=><button key={i.id} className={`incident-row ${selected===i.id?'chosen':''}`} onClick={()=>setSelected(i.id)}><span className={`incident-icon ${i.status}`}><Activity size={17}/></span><span className="incident-copy"><b>{i.payload?.exceptionType || 'Application error'} <span>·</span> {i.payload?.request?.path || 'Unknown path'}</b><small>{i.payload?.message || 'No error message'} <i>·</i> {new Date(i.createdAt).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'})}</small></span><Status status={i.status}/><span className="row-chevron">›</span></button>) : <div className="empty"><div className="empty-icon"><Activity size={19}/></div><b>{apiState==='offline'?'Backend is offline':'No incidents match'}</b><span>{apiState==='offline'?'Start the Spring Boot API on port 8000.':'Trigger a ShopLite bug to see it appear here.'}</span></div>}</div>
            <footer className="feed-footer"><span><i className="live-dot"/> Updates live via event stream</span><button onClick={()=>setFilter('all')}>View all incidents <ArrowUpRight size={14}/></button></footer>
          </section>
          <aside className="detail-card">
            {!detail ? <div className="detail-empty"><Activity size={22}/><b>Select an incident</b><span>Review captured evidence and pipeline status.</span></div> : <>
              <div className="detail-top"><div><span className="eyebrow">INCIDENT DETAIL</span><button className="copy-id" onClick={()=>navigator.clipboard?.writeText(detail.incident.id)}>{detail.incident.id.slice(0,8)} <span>↗</span></button></div><Status status={detail.incident.status}/></div>
              <h3>{exception}</h3><p className="detail-sub">{payload.message || 'No message provided'}</p>
              <div className="route-box"><span className="method">{payload.request?.method || 'ERR'}</span><code>{route}</code><ExternalLink size={14}/></div>
              <div className="pipeline-title"><b>Repair pipeline</b><span>{pretty(detail.incident.status)}</span></div>
              <div className="stage-list">{stages.map((stage,index)=>{
                const completed = index < activeIndex || detail.incident.status === 'ready_for_review' && index < 6 || ['approved','rejected'].includes(detail.incident.status) && index < 7;
                const current = index===activeIndex;
                const blocked = current && ['failed','declined'].includes(detail.incident.status);
                return <div className={`stage ${completed?'done':''} ${current?'current':''} ${blocked?'blocked':''}`} key={stage}><span className="stage-marker">{completed?<Check size={12}/>:blocked?<X size={10}/>:index+1}</span><span>{stage}</span>{current&&<i/>}</div>;
              })}</div>
              <div className="evidence-title"><b>Captured evidence</b><span>{Object.keys(payload).length} fields</span></div>
              <div className="evidence-grid"><div><small>EXCEPTION</small><b>{exception}</b></div><div><small>APP VERSION</small><b>{payload.appVersion || 'Unknown'}</b></div><div><small>REPORTED</small><b>{new Date(detail.incident.createdAt).toLocaleString()}</b></div></div>
              {detail.incident.declineReport&&<div className="decline-box"><ShieldX size={16}/><span><b>Why PatchPilot stopped</b><br/>{detail.incident.declineReport}</span></div>}
              <div className="review-note"><ShieldCheck size={15}/><span>Review every change before merging. PatchPilot does not deploy.</span></div>
              <div className="action-row">{detail.incident.status==='ready_for_review'?<><button className="reject-btn" disabled={busy} onClick={()=>void act('reject')}><X size={15}/> Reject</button><button className="approve-btn" disabled={busy} onClick={()=>void act('approve')}><Check size={15}/> Approve</button></>:<button className="subtle-btn full" disabled={busy} onClick={()=>void act('retry')}><RefreshCw size={14}/> Retry pipeline</button>}</div>
            </>}
          </aside>
        </div>
        <div className="disclaimer"><ShieldCheck size={14}/> PatchPilot is a supervised prototype. The current pipeline records incident state; automated LLM patch generation is not enabled yet.</div>
      </div>
    </main>
    <AnimatePresence>{demoOpen&&<><motion.div className="scrim" initial={{opacity:0}} animate={{opacity:1}} exit={{opacity:0}} onClick={()=>setDemoOpen(false)}/><motion.aside className="demo-drawer" initial={{x:420}} animate={{x:0}} exit={{x:420}} transition={{type:'spring',damping:28}}><div className="drawer-head"><div><span className="eyebrow">LOCAL DEMO</span><h2>Break the app</h2></div><button className="icon-btn" onClick={()=>setDemoOpen(false)}><X size={18}/></button></div><p>Trigger a seeded ShopLite error. PatchPilot will capture it in the incident feed.</p><label className="field-label">CHOOSE A SCENARIO</label><div className="bug-options">{bugOptions.map(b=><button key={b.id} className={activeBug===b.id?'bug-option chosen':'bug-option'} onClick={()=>setActiveBug(b.id)}><span className="radio"/><span><b>{b.label}</b><small>{b.method} {b.path}</small></span>{b.id==='payments'&&<em>REFUSAL</em>}</button>)}</div><div className="drawer-callout"><ShieldCheck size={17}/><span>Payment errors are sensitive. The intended behavior is to refuse unsafe fixes.</span></div><button className="primary-btn drawer-trigger" disabled={busy} onClick={()=>void trigger()}><Sparkles size={16}/> {busy?'Triggering…':'Trigger selected bug'}</button><button className="subtle-btn drawer-reset" onClick={()=>void resetDemo()}><RefreshCw size={14}/> Reset demo switches</button><small className="drawer-foot">Requires ShopLite on localhost:5000 and API on localhost:8000.</small></motion.aside></>}</AnimatePresence>
    <AnimatePresence>{toast&&<motion.div className="toast" initial={{y:20,opacity:0}} animate={{y:0,opacity:1}} exit={{y:12,opacity:0}}><Activity size={15}/>{toast}</motion.div>}</AnimatePresence>
  </div>;
}

function Stat({icon,label,value,foot,tone,trend}:{icon:React.ReactNode;label:string;value:React.ReactNode;foot:string;tone:string;trend:React.ReactNode}) { return <div className="stat-card"><div className="stat-top"><span className={`stat-icon ${tone}`}>{icon}</span><span className={`stat-trend ${tone}`}>{trend}</span></div><div className="stat-value">{value}</div><div className="stat-label">{label}</div><div className="stat-foot">{foot}</div></div>; }
function Status({status}:{status:string}) { return <span className={`status-pill ${status}`}><i/>{pretty(status)}</span>; }

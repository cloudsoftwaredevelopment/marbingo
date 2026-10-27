import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { ROLE_LABELS } from '../lib/format';
import { categoriesFor, getArticle, search, visibleFor } from './help/registry';
import Markup from './help/Markup';
import '../styles/help.css';

const APP_LABEL = { web: 'Back office', android: 'Agent app' };

function Block({ b, go }) {
  switch (b.type) {
    case 'h':
      return <h3 className="kb-h">{b.text}</h3>;
    case 'steps':
      return <ol className="kb-steps">{b.items.map((it, i) => <li key={i}><Markup text={it} onNavigate={go} /></li>)}</ol>;
    case 'list':
      return <ul className="kb-list">{b.items.map((it, i) => <li key={i}><Markup text={it} onNavigate={go} /></li>)}</ul>;
    case 'tip':
      return <div className="kb-callout tip"><span className="kb-callout-tag">Tip</span><p><Markup text={b.text} onNavigate={go} /></p></div>;
    case 'note':
      return <div className="kb-callout note"><span className="kb-callout-tag">Note</span><p><Markup text={b.text} onNavigate={go} /></p></div>;
    case 'warn':
      return <div className="kb-callout warn"><span className="kb-callout-tag">Important</span><p><Markup text={b.text} onNavigate={go} /></p></div>;
    case 'faq':
      return (
        <dl className="kb-faq">
          {b.items.map((it, i) => (
            <div key={i}>
              <dt><Markup text={it.q} onNavigate={go} /></dt>
              <dd><Markup text={it.a} onNavigate={go} /></dd>
            </div>
          ))}
        </dl>
      );
    case 'p':
    default:
      return <p className="kb-p"><Markup text={b.text} onNavigate={go} /></p>;
  }
}

function ArticleView({ article, go }) {
  return (
    <article className="kb-article card">
      <div className="kb-chips">
        <span className="kb-chip">{APP_LABEL[article.app]}</span>
        <span className="kb-chip ghost">{article.category}</span>
      </div>
      <h1 className="kb-title">{article.title}</h1>
      {article.summary && <p className="kb-summary">{article.summary}</p>}
      <div className="kb-body">
        {article.blocks.map((b, i) => <Block key={i} b={b} go={go} />)}
      </div>
    </article>
  );
}

function Card({ article, go }) {
  return (
    <button type="button" className="kb-card" onClick={() => go(article.id)}>
      <span className="kb-card-app">{APP_LABEL[article.app]}</span>
      <span className="kb-card-title">{article.title}</span>
      {article.summary && <span className="kb-card-sum">{article.summary}</span>}
    </button>
  );
}

export default function Help() {
  const { user } = useAuth();
  const { articleId } = useParams();
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [appFilter, setAppFilter] = useState('all'); // all | web | android
  const searchRef = useRef(null);

  const mine = useMemo(() => visibleFor(user), [user]);
  const go = (id) => navigate(`/help/${id}`);
  const article = articleId ? getArticle(articleId) : null;
  const allowed = article && mine.some((a) => a.id === article.id);

  // Scroll to top when the open article changes.
  useEffect(() => { window.scrollTo(0, 0); }, [articleId]);

  const pool = useMemo(() => (appFilter === 'all' ? mine : mine.filter((a) => a.app === appFilter)), [mine, appFilter]);
  const results = useMemo(() => (q.trim() ? search(pool, q.trim()) : null), [pool, q]);
  const groups = useMemo(() => categoriesFor(pool), [pool]);

  if (articleId) {
    if (!article || !allowed) {
      return (
        <div className="page-col kb-page">
          <button type="button" className="kb-back" onClick={() => navigate('/help')}>← All help</button>
          <p className="empty card">{article ? "This article isn't available for your role." : "That help article wasn't found."}</p>
        </div>
      );
    }
    return (
      <div className="page-col kb-page">
        <button type="button" className="kb-back" onClick={() => navigate('/help')}>← All help</button>
        <ArticleView article={article} go={go} />
      </div>
    );
  }

  return (
    <div className="page-col kb-page">
      <header className="page-head">
        <p className="eyebrow">Help centre</p>
        <h1 className="page-title">How to use Marbingo</h1>
        <p className="kb-lead">Step-by-step guides for the back office and the Marbingo Field agent app. Showing the guides for <b>{ROLE_LABELS[user?.role] || 'your role'}</b>.</p>
      </header>

      <div className="kb-search-row">
        <div className="kb-search">
          <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>
          <input ref={searchRef} type="search" className="input" placeholder="Search help… (e.g. deliver an order, printer, work hours)"
            value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search help" />
          {q && <button type="button" className="kb-clear" aria-label="Clear search" onClick={() => { setQ(''); searchRef.current?.focus(); }}>×</button>}
        </div>
        <div role="group" aria-label="Which app" className="kb-apps">
          {[['all', 'Everything'], ['web', 'Back office'], ['android', 'Agent app']].map(([k, l]) => (
            <button key={k} type="button" className="pill kb-pill" aria-pressed={appFilter === k} onClick={() => setAppFilter(k)}>{l}</button>
          ))}
        </div>
      </div>

      {results ? (
        <section aria-live="polite" className="kb-results">
          <p className="hint">{results.length === 0 ? 'No guides match your search.' : `${results.length} guide${results.length === 1 ? '' : 's'} found`}</p>
          <div className="kb-grid">
            {results.map((a) => <Card key={a.id} article={a} go={go} />)}
          </div>
        </section>
      ) : (
        <div className="kb-cats">
          {groups.map((g) => (
            <section key={g.name} className="kb-cat" aria-label={g.name}>
              <h2 className="kb-cat-title">{g.name}</h2>
              <div className="kb-grid">
                {g.items.map((a) => <Card key={a.id} article={a} go={go} />)}
              </div>
            </section>
          ))}
          {!groups.length && <p className="empty card">No guides here yet.</p>}
        </div>
      )}
    </div>
  );
}

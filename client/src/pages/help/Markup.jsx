import { Fragment } from 'react';
import { Link } from 'react-router-dom';
import { getArticle } from './registry';

// Inline markup: **bold** for on-screen labels, [[id|text]] for links to other articles.
const TOKEN = /(\*\*[^*]+\*\*|\[\[[a-z0-9-]+\|[^\]]+\]\])/g;

export default function Markup({ text, onNavigate }) {
  if (!text) return null;
  const parts = String(text).split(TOKEN);
  return (
    <>
      {parts.map((part, i) => {
        if (!part) return null;
        if (part.startsWith('**') && part.endsWith('**')) {
          return <b key={i} className="kb-ui">{part.slice(2, -2)}</b>;
        }
        const link = /^\[\[([a-z0-9-]+)\|([^\]]+)\]\]$/.exec(part);
        if (link) {
          const target = getArticle(link[1]);
          const label = link[2];
          if (!target) return <Fragment key={i}>{label}</Fragment>;
          return (
            <Link key={i} to={`/help/${link[1]}`} className="kb-link"
              onClick={onNavigate ? (e) => { e.preventDefault(); onNavigate(link[1]); } : undefined}>
              {label}
            </Link>
          );
        }
        return <Fragment key={i}>{part}</Fragment>;
      })}
    </>
  );
}

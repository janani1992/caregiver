import { Link, Route, Routes, useParams } from 'react-router-dom';
import CreateCirclePage from './features/circles/CreateCirclePage';
import AcceptInvitePage from './features/invites/AcceptInvitePage';
import InviteMembersPanel from './features/invites/InviteMembersPanel';

function CircleHomePage() {
  const { circleId } = useParams();
  return (
    <main className="page-shell">
      <header className="brand"><span className="brand-mark">✳</span> care circle</header>
      <div className="circle-home-stack">
        <section className="success-card" aria-labelledby="circle-created-title">
          <div className="success-icon" aria-hidden="true">✓</div>
          <p className="eyebrow">A little more supported</p>
          <h1 id="circle-created-title">Your circle is ready.</h1>
          <p className="muted">Invite the people who help care for your loved one.</p>
          <p className="circle-id">Circle ID <span>{circleId}</span></p>
          <Link className="text-link" to="/circles/new">Create another circle</Link>
        </section>
        <InviteMembersPanel circleId={circleId ?? ''} />
      </div>
    </main>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<CreateCirclePage />} />
      <Route path="/circles/new" element={<CreateCirclePage />} />
      <Route path="/circles/:circleId" element={<CircleHomePage />} />
      <Route path="/invites/:token" element={<AcceptInvitePage />} />
    </Routes>
  );
}

import { Link, Route, Routes, useParams } from 'react-router-dom';
import CreateCirclePage from './features/circles/CreateCirclePage';
import AcceptInvitePage from './features/invites/AcceptInvitePage';
import CircleMembersPanel from './features/members/CircleMembersPanel';

function CircleHomePage() {
  const { circleId } = useParams();
  return (
    <main className="page-shell">
      <header className="brand"><span className="brand-mark">✳</span> care circle</header>
      <CircleMembersPanel circleId={circleId ?? ''} />
      <Link className="text-link create-another-link" to="/circles/new">Create another circle</Link>
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

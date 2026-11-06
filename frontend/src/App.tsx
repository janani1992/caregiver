import { Link, Route, Routes, useParams } from 'react-router-dom';
import CreateCirclePage from './features/circles/CreateCirclePage';

function CircleHomePage() {
  const { circleId } = useParams();
  return (
    <main className="page-shell">
      <header className="brand"><span className="brand-mark">✳</span> care circle</header>
      <section className="success-card" aria-labelledby="circle-created-title">
        <div className="success-icon" aria-hidden="true">✓</div>
        <p className="eyebrow">A little more supported</p>
        <h1 id="circle-created-title">Your circle is ready.</h1>
        <p className="muted">Next, invite the people who help care for your loved one.</p>
        <p className="circle-id">Circle ID <span>{circleId}</span></p>
        <Link className="text-link" to="/circles/new">Create another circle</Link>
      </section>
    </main>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<CreateCirclePage />} />
      <Route path="/circles/new" element={<CreateCirclePage />} />
      <Route path="/circles/:circleId" element={<CircleHomePage />} />
    </Routes>
  );
}

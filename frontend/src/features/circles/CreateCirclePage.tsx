import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createCircle } from '../../api/circles';

type Attempt = { name: string; timezone: string; id: string; key: string };

const timezones = [
  { value: 'Asia/Singapore', label: 'Singapore (GMT+8)' },
  { value: 'Asia/Jakarta', label: 'Jakarta (GMT+7)' },
  { value: 'Asia/Manila', label: 'Manila (GMT+8)' },
  { value: 'Asia/Kuala_Lumpur', label: 'Kuala Lumpur (GMT+8)' },
];

export default function CreateCirclePage() {
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [timezone, setTimezone] = useState('Asia/Singapore');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const attempt = useRef<Attempt | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmedName = name.trim();
    if (!trimmedName) {
      setError('Add a name for your circle to continue.');
      return;
    }

    setError('');
    setSubmitting(true);
    if (!attempt.current || attempt.current.name !== trimmedName || attempt.current.timezone !== timezone) {
      attempt.current = {
        name: trimmedName,
        timezone,
        id: crypto.randomUUID(),
        key: crypto.randomUUID(),
      };
    }

    try {
      const circle = await createCircle(
        { id: attempt.current.id, name: trimmedName, timezone },
        attempt.current.key,
      );
      navigate(`/circles/${circle.id}`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Something went wrong. Please try again.');
      setSubmitting(false);
    }
  }

  return (
    <main className="page-shell">
      <header className="brand"><span className="brand-mark">✳</span> care circle</header>
      <div className="create-layout">
        <section className="intro-panel">
          <div className="intro-orbit orbit-one" />
          <div className="intro-orbit orbit-two" />
          <p className="eyebrow">Care works better together</p>
          <h1>Make room for<br /><em>more support.</em></h1>
          <p className="intro-copy">Bring your family and care team into one calm, shared space.</p>
          <div className="people-art" aria-hidden="true">
            <span className="person person-one">S</span>
            <span className="person person-two">M</span>
            <span className="person person-three">A</span>
            <span className="orbit-label">your care circle</span>
          </div>
          <ul className="benefit-list">
            <li><span>✓</span> One place for everyday updates</li>
            <li><span>✓</span> Everyone knows what needs doing</li>
          </ul>
        </section>

        <section className="form-panel" aria-labelledby="create-title">
          <div className="form-topline"><span className="step-dot">01</span><span>GET STARTED</span></div>
          <h2 id="create-title">Create your circle</h2>
          <p className="muted">Start with a name. You can invite your people next.</p>
          <form onSubmit={handleSubmit} noValidate>
            <label htmlFor="circle-name">What should we call it?</label>
            <input
              autoFocus
              id="circle-name"
              name="name"
              maxLength={80}
              placeholder="e.g. The Tan family"
              value={name}
              onChange={(event) => setName(event.target.value)}
              aria-describedby={error ? 'form-error' : 'name-hint'}
              aria-invalid={Boolean(error)}
            />
            <span className="field-hint" id="name-hint">Keep it simple — you can change this later.</span>

            <label htmlFor="circle-timezone">Circle time zone</label>
            <select id="circle-timezone" value={timezone} onChange={(event) => setTimezone(event.target.value)}>
              {timezones.map((zone) => <option value={zone.value} key={zone.value}>{zone.label}</option>)}
            </select>
            <span className="field-hint">Used to keep updates and reminders on local time.</span>

            {error && <p className="form-error" id="form-error" role="alert">{error}</p>}
            <button type="submit" disabled={submitting}>
              {submitting ? 'Creating your circle…' : 'Create circle'}
              {!submitting && <span aria-hidden="true">→</span>}
            </button>
          </form>
          <p className="privacy-note"><span aria-hidden="true">⌑</span> Your care space is private to the people you invite.</p>
          <Link className="back-link" to="/">Care Circle</Link>
        </section>
      </div>
      <footer className="page-footer">Small steps, shared together.</footer>
    </main>
  );
}

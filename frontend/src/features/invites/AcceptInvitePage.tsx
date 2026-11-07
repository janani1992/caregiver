import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { acceptInvite, getInvitePreview, type InviteAcceptance, type InvitePreview } from '../../api/invites';

const roleNames = { FAMILY: 'family member', HELPER: 'care helper', VIEWER: 'viewer' } as const;

export default function AcceptInvitePage() {
  const { token = '' } = useParams();
  const [preview, setPreview] = useState<InvitePreview | null>(null);
  const [accepted, setAccepted] = useState<InviteAcceptance | null>(null);
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const acceptanceKey = useRef(crypto.randomUUID());

  useEffect(() => {
    let active = true;
    setLoading(true);
    getInvitePreview(token)
      .then((result) => { if (active) setPreview(result); })
      .catch((reason: unknown) => {
        if (active) setError(reason instanceof Error ? reason.message : 'This invite link is unavailable.');
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [token]);

  async function handleAccept() {
    setError('');
    const trimmedDisplayName = displayName.trim();
    if (!trimmedDisplayName) {
      setError('Add your name so the circle knows who joined.');
      return;
    }
    setSubmitting(true);
    try {
      setAccepted(await acceptInvite(token, trimmedDisplayName, acceptanceKey.current));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not accept this invite. Please try again.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="page-shell invite-page-shell">
      <header className="brand"><span className="brand-mark">✳</span> care circle</header>
      <section className="success-card invite-card" aria-labelledby="invite-page-title">
        {loading ? (
          <p className="muted" role="status">Checking your invite…</p>
        ) : accepted ? (
          <>
            <div className="success-icon" aria-hidden="true">✓</div>
            <p className="eyebrow">YOU’RE IN</p>
            <h1 id="invite-page-title">Welcome to the circle.</h1>
            <p className="muted">You joined <strong>{accepted.circleName}</strong> as a {roleNames[accepted.role]}.</p>
          </>
        ) : preview ? (
          <>
            <div className="invite-symbol invite-symbol-large" aria-hidden="true">✳</div>
            <p className="eyebrow">YOU’RE INVITED</p>
            <h1 id="invite-page-title">Join {preview.circleName}.</h1>
            <p className="muted">You’ll join as a <strong>{roleNames[preview.role]}</strong>. You can leave the circle at any time.</p>
            <label className="invite-name-label" htmlFor="invite-display-name">Your name</label>
            <input
              id="invite-display-name"
              autoComplete="name"
              maxLength={80}
              placeholder="e.g. Alex Tan"
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              aria-describedby={error ? 'invite-accept-error' : undefined}
              aria-invalid={Boolean(error)}
            />
            <p className="expiry-note">This invite is valid until {new Date(preview.expiresAt).toLocaleDateString()}.</p>
            <button className="primary-button" type="button" disabled={submitting} onClick={handleAccept}>
              {submitting ? 'Joining…' : 'Accept invite'} {!submitting && <span aria-hidden="true">→</span>}
            </button>
          </>
        ) : (
          <>
            <p className="eyebrow">INVITE UNAVAILABLE</p>
            <h1 id="invite-page-title">This link can’t be used.</h1>
            <p className="muted">{error || 'Ask the circle owner for a new invite link.'}</p>
            <Link className="text-link" to="/">Go to Care Circle</Link>
          </>
        )}
        {error && preview && <p className="form-error" id="invite-accept-error" role="alert">{error}</p>}
      </section>
      <footer className="page-footer">Small steps, shared together.</footer>
    </main>
  );
}

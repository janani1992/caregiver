import { useRef, useState } from 'react';
import { createInvite, type CircleInvite, type InviteRole } from '../../api/invites';

type Attempt = { role: InviteRole; id: string; token: string; key: string };

const roles: { value: InviteRole; label: string; description: string }[] = [
  { value: 'FAMILY', label: 'Family', description: 'Coordinate care and share updates.' },
  { value: 'HELPER', label: 'Helper', description: 'See the shared list and care updates.' },
  { value: 'VIEWER', label: 'Viewer', description: 'Follow along without making changes.' },
];

function createShareToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  let binary = '';
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

export default function InviteMembersPanel({ circleId }: { circleId: string }) {
  const [role, setRole] = useState<InviteRole>('FAMILY');
  const [invite, setInvite] = useState<CircleInvite | null>(null);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const attempt = useRef<Attempt | null>(null);

  async function handleCreate() {
    setError('');
    setCopied(false);
    setSubmitting(true);
    if (!attempt.current || attempt.current.role !== role) {
      attempt.current = {
        role,
        id: crypto.randomUUID(),
        token: createShareToken(),
        key: crypto.randomUUID(),
      };
    }
    try {
      const created = await createInvite(circleId, role, attempt.current.id, attempt.current.token, attempt.current.key);
      setInvite(created);
      attempt.current = null;
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not create an invite. Please try again.');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCopy() {
    if (!invite) return;
    try {
      await navigator.clipboard.writeText(invite.inviteUrl);
      setCopied(true);
    } catch {
      setError('Clipboard access is unavailable. Select and copy the invite link instead.');
    }
  }

  return (
    <section className="invite-panel" aria-labelledby="invite-heading">
      <div className="invite-heading-row">
        <div>
          <p className="eyebrow">GROW YOUR TEAM</p>
          <h2 id="invite-heading">Invite someone in</h2>
          <p className="muted">Choose what they can do. Invite links expire in 7 days.</p>
        </div>
        <span className="invite-symbol" aria-hidden="true">＋</span>
      </div>
      <label htmlFor="invite-role">Give them a role</label>
      <select id="invite-role" value={role} onChange={(event) => setRole(event.target.value as InviteRole)}>
        {roles.map((item) => <option value={item.value} key={item.value}>{item.label} — {item.description}</option>)}
      </select>
      <button className="primary-button" type="button" disabled={submitting} onClick={handleCreate}>
        {submitting ? 'Making a secure link…' : invite ? 'Create another invite' : 'Create invite link'}
        {!submitting && <span aria-hidden="true">→</span>}
      </button>
      {error && <p className="form-error" role="alert">{error}</p>}
      {invite && (
        <div className="invite-result" aria-live="polite">
          <p className="result-label">{roles.find((item) => item.value === invite.role)?.label} invite link</p>
          <div className="copy-row">
            <input aria-label="Invite link" readOnly value={invite.inviteUrl} onFocus={(event) => event.currentTarget.select()} />
            <button type="button" className="copy-button" onClick={handleCopy}>{copied ? 'Copied' : 'Copy'}</button>
          </div>
          <p className="field-hint">Anyone with this private link can join as {invite.role.toLowerCase()} before it expires.</p>
        </div>
      )}
    </section>
  );
}

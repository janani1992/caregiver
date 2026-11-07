import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getCircleMembers, removeCircleMember, type CircleMembers, type MemberRole } from '../../api/members';
import { currentUserId } from '../../api/session';
import InviteMembersPanel from '../invites/InviteMembersPanel';

const roleLabels: Record<MemberRole, string> = {
  OWNER: 'Owner',
  FAMILY: 'Family',
  HELPER: 'Helper',
  VIEWER: 'Viewer',
};

function initials(name: string): string {
  return name.trim().split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? '').join('') || '?';
}

export default function CircleMembersPanel({ circleId }: { circleId: string }) {
  const [roster, setRoster] = useState<CircleMembers | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [pendingRemoval, setPendingRemoval] = useState<string | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setError('');
    try {
      setRoster(await getCircleMembers(circleId));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Circle members could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, [circleId]);

  useEffect(() => { void reload(); }, [reload]);

  async function confirmRemoval(userId: string) {
    setRemovingId(userId);
    setError('');
    try {
      await removeCircleMember(circleId, userId, crypto.randomUUID());
      setPendingRemoval(null);
      await reload();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'This member could not be removed.');
    } finally {
      setRemovingId(null);
    }
  }

  if (loading) {
    return <p className="muted roster-status" role="status">Loading your circle…</p>;
  }

  if (!roster) {
    return (
      <section className="members-empty" aria-labelledby="members-error-title">
        <p className="eyebrow">CIRCLE MEMBERS</p>
        <h1 id="members-error-title">This circle isn’t available.</h1>
        <p className="muted" role="alert">{error}</p>
        <Link className="text-link" to="/">Go to Care Circle</Link>
      </section>
    );
  }

  const isOwner = roster.currentUserRole === 'OWNER';

  return (
    <div className="members-page-content">
      <section className="members-card" aria-labelledby="members-title">
        <div className="members-card-header">
          <div>
            <p className="eyebrow">YOUR CARE TEAM</p>
            <h1 id="members-title">{roster.circleName}</h1>
            <p className="muted">{roster.members.length} active {roster.members.length === 1 ? 'member' : 'members'} · You’re a {roleLabels[roster.currentUserRole].toLowerCase()}.</p>
          </div>
          <span className="invite-symbol" aria-hidden="true">✳</span>
        </div>

        {error && <p className="form-error roster-error" role="alert">{error}</p>}
        <ul className="member-list">
          {roster.members.map((member, index) => {
            const canRemove = isOwner && member.role !== 'OWNER' && member.userId !== currentUserId;
            const isConfirming = pendingRemoval === member.userId;
            return (
              <li className="member-row" key={member.userId}>
                <span className={`member-avatar avatar-${index % 4}`} aria-hidden="true">{initials(member.displayName)}</span>
                <div className="member-details">
                  <strong>{member.displayName}{member.userId === currentUserId && <span className="you-tag">You</span>}</strong>
                  <span>{roleLabels[member.role]} · Joined {new Date(member.joinedAt).toLocaleDateString()}</span>
                </div>
                {canRemove && !isConfirming && (
                  <button className="remove-member-button" type="button" onClick={() => setPendingRemoval(member.userId)}>
                    Remove
                  </button>
                )}
                {canRemove && isConfirming && (
                  <div className="remove-confirm" aria-label={`Remove ${member.displayName}`}>
                    <span>Remove?</span>
                    <button type="button" className="confirm-remove-button" disabled={removingId === member.userId} onClick={() => void confirmRemoval(member.userId)}>
                      {removingId === member.userId ? 'Removing…' : 'Confirm'}
                    </button>
                    <button type="button" className="cancel-remove-button" disabled={removingId === member.userId} onClick={() => setPendingRemoval(null)}>
                      Cancel
                    </button>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
        <p className="member-privacy-note"><span aria-hidden="true">⌑</span> Only people invited to this circle can see its members.</p>
      </section>
      {isOwner && <InviteMembersPanel circleId={circleId} />}
    </div>
  );
}

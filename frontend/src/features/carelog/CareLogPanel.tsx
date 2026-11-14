import { useCallback, useEffect, useState } from 'react';
import { getCircleMembers, type MemberRole } from '../../api/members';
import {
  addCareLogEntry,
  canWriteCareLog,
  getCareLogEntries,
  type CareLogCategory,
  type CareLogEntry,
} from '../../api/careLog';

const categoryLabels: Record<CareLogCategory, string> = {
  GENERAL: 'General update',
  MEDICATION: 'Medication',
  APPOINTMENT: 'Appointment',
  WELLBEING: 'Wellbeing',
  OTHER: 'Other',
};

export default function CareLogPanel({ circleId }: { circleId: string }) {
  const [entries, setEntries] = useState<CareLogEntry[]>([]);
  const [role, setRole] = useState<MemberRole | null>(null);
  const [text, setText] = useState('');
  const [category, setCategory] = useState<CareLogCategory>('GENERAL');
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    setError('');
    try {
      const [page, roster] = await Promise.all([getCareLogEntries(circleId), getCircleMembers(circleId)]);
      setEntries(page.entries);
      setNextCursor(page.nextCursor);
      setRole(roster.currentUserRole);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'The care log could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, [circleId]);

  useEffect(() => { void reload(); }, [reload]);

  async function submitEntry(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const cleanText = text.trim();
    if (!cleanText || saving) return;
    setSaving(true);
    setError('');
    try {
      await addCareLogEntry(circleId, { id: crypto.randomUUID(), text: cleanText, category });
      setText('');
      setCategory('GENERAL');
      await reload();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'The update could not be added.');
    } finally {
      setSaving(false);
    }
  }

  async function loadOlderEntries() {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    setError('');
    try {
      const page = await getCareLogEntries(circleId, nextCursor);
      setEntries((current) => {
        const knownIds = new Set(current.map((entry) => entry.id));
        return [...current, ...page.entries.filter((entry) => !knownIds.has(entry.id))];
      });
      setNextCursor(page.nextCursor);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Older updates could not be loaded.');
    } finally {
      setLoadingMore(false);
    }
  }

  return (
    <section className="care-log-card" aria-labelledby="care-log-title">
      <div className="care-log-heading">
        <div>
          <p className="eyebrow">SMALL UPDATES, SHARED</p>
          <h2 id="care-log-title">Care log</h2>
          <p className="muted">A private timeline for the little things that help everyone stay in sync.</p>
        </div>
        <span className="care-log-icon" aria-hidden="true">⌁</span>
      </div>

      {error && <p className="form-error care-log-error" role="alert">{error}</p>}
      {role && canWriteCareLog(role) && (
        <form className="care-log-form" onSubmit={(event) => void submitEntry(event)}>
          <label htmlFor="care-log-text">Add a care update</label>
          <textarea
            id="care-log-text"
            value={text}
            maxLength={2000}
            onChange={(event) => setText(event.target.value)}
            placeholder="Share an update for your care team…"
            required
            rows={3}
          />
          <div className="care-log-form-actions">
            <select aria-label="Update category" value={category} onChange={(event) => setCategory(event.target.value as CareLogCategory)}>
              {Object.entries(categoryLabels).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
            <button type="submit" disabled={saving || !text.trim()}>{saving ? 'Saving…' : 'Add update'}</button>
          </div>
        </form>
      )}

      {loading ? (
        <p className="care-log-empty" role="status">Loading care updates…</p>
      ) : entries.length === 0 ? (
        <div className="care-log-empty"><span aria-hidden="true">✳</span><p>No updates yet. You can add the first one.</p></div>
      ) : (
        <ol className="care-log-list">
          {entries.map((entry) => (
            <li className="care-log-entry" key={entry.id}>
              <span className="care-log-timeline-dot" aria-hidden="true" />
              <div className="care-log-entry-copy">
                <div className="care-log-entry-meta">
                  <strong>{entry.authorName}</strong>
                  <time dateTime={entry.createdAt}>{new Date(entry.createdAt).toLocaleString()}</time>
                </div>
                <span className="care-log-category">{categoryLabels[entry.category]}</span>
                <p>{entry.text}</p>
              </div>
            </li>
          ))}
        </ol>
      )}

      {!loading && nextCursor && (
        <button className="care-log-more-button" type="button" disabled={loadingMore} onClick={() => void loadOlderEntries()}>
          {loadingMore ? 'Loading…' : 'Load older updates'}
        </button>
      )}
      {role === 'VIEWER' && <p className="member-privacy-note">You can read the care log, but cannot add updates.</p>}
    </section>
  );
}
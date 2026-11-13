import { useCallback, useEffect, useMemo, useState } from 'react';
import { getCircleMembers, type MemberRole } from '../../api/members';
import { currentUserId } from '../../api/session';
import {
  disableBrowserNotifications,
  enableBrowserNotifications,
  getCurrentBrowserPushSubscription,
  getPushConfiguration,
  type PushSubscriptionRecord,
} from '../../api/push';
import {
  acknowledgeUrgentRequest,
  canCreateUrgentRequest,
  canResolveUrgentRequest,
  canTakeUrgentRequest,
  createUrgentRequest,
  getUrgentRequests,
  resolveUrgentRequest,
  type UrgentRequest,
} from '../../api/urgent';

function statusText(request: UrgentRequest): string {
  if (request.state === 'RESOLVED') return `Resolved${request.resolvedByName ? ` by ${request.resolvedByName}` : ''}`;
  if (request.state === 'ON_IT') return `${request.handledByName ?? 'Someone'} is on it`;
  return 'Waiting for someone to take this on';
}

export default function UrgentRequestsPanel({ circleId }: { circleId: string }) {
  const [requests, setRequests] = useState<UrgentRequest[]>([]);
  const [role, setRole] = useState<MemberRole | null>(null);
  const [text, setText] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [pushPublicKey, setPushPublicKey] = useState<string | null>(null);
  const [pushDeliveryEnabled, setPushDeliveryEnabled] = useState(false);
  const [pushSubscription, setPushSubscription] = useState<PushSubscriptionRecord | null>(null);
  const [pushBusy, setPushBusy] = useState(false);
  const [pushError, setPushError] = useState('');
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    setError('');
    try {
      const [list, roster] = await Promise.all([getUrgentRequests(circleId), getCircleMembers(circleId)]);
      setRequests(list.requests);
      setRole(roster.currentUserRole);
      try {
        const [configuration, subscription] = await Promise.all([getPushConfiguration(), getCurrentBrowserPushSubscription()]);
        setPushPublicKey(configuration.publicKey);
        setPushDeliveryEnabled(configuration.deliveryEnabled);
        setPushSubscription(subscription);
      } catch {
        setPushPublicKey(null);
        setPushDeliveryEnabled(false);
        setPushSubscription(null);
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Urgent requests could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, [circleId]);

  useEffect(() => { void reload(); }, [reload]);

  useEffect(() => {
    const requestId = new URLSearchParams(window.location.search).get('urgentRequestId');
    if (!requestId || !requests.length) return;
    const target = document.getElementById(`urgent-request-${requestId}`);
    target?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    target?.classList.add('urgent-request-highlight');
    const timeout = window.setTimeout(() => target?.classList.remove('urgent-request-highlight'), 2400);
    return () => window.clearTimeout(timeout);
  }, [requests]);

  const activeRequests = useMemo(() => requests.filter((request) => request.state !== 'RESOLVED'), [requests]);
  const resolvedRequests = useMemo(() => requests.filter((request) => request.state === 'RESOLVED'), [requests]);

  async function submitRequest(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const cleanText = text.trim();
    if (!cleanText || saving) return;
    setSaving(true);
    setError('');
    try {
      await createUrgentRequest(circleId, cleanText);
      setText('');
      await reload();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'The urgent request could not be created.');
    } finally {
      setSaving(false);
    }
  }

  async function runAction(request: UrgentRequest, action: 'SEEN' | 'ON_IT' | 'RESOLVE') {
    setBusyId(request.id);
    setError('');
    try {
      if (action === 'SEEN' || action === 'ON_IT') await acknowledgeUrgentRequest(request.id, action);
      else await resolveUrgentRequest(request.id);
      await reload();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'The urgent request could not be updated.');
    } finally {
      setBusyId(null);
    }
  }

  async function toggleBrowserNotifications() {
    setPushBusy(true);
    setPushError('');
    try {
      if (pushSubscription) {
        await disableBrowserNotifications(pushSubscription.id);
        setPushSubscription(null);
      } else if (pushPublicKey) {
        const subscription = await enableBrowserNotifications(pushPublicKey);
        setPushSubscription(subscription);
      }
    } catch (reason) {
      setPushError(reason instanceof Error ? reason.message : 'Browser notifications could not be configured.');
    } finally {
      setPushBusy(false);
    }
  }

  function renderRequest(request: UrgentRequest) {
    const canResolve = role !== null && canResolveUrgentRequest(role, request, currentUserId);
    return (
      <li id={`urgent-request-${request.id}`} className={`urgent-request urgent-${request.state.toLowerCase()}`} key={request.id}>
        <span className="urgent-marker" aria-hidden="true">!</span>
        <div className="urgent-request-copy">
          <strong>{request.text}</strong>
          <span>{statusText(request)} · Asked by {request.senderName}</span>
        </div>
        {role && !request.seenByMe && (
          <button className="urgent-secondary-button" type="button" disabled={busyId === request.id} onClick={() => void runAction(request, 'SEEN')}>
            Mark seen
          </button>
        )}
        {role && canTakeUrgentRequest(role) && request.state === 'OPEN' && (
          <button className="urgent-primary-button" type="button" disabled={busyId === request.id} onClick={() => void runAction(request, 'ON_IT')}>
            {busyId === request.id ? 'Saving…' : 'I’ll take it'}
          </button>
        )}
        {request.state !== 'RESOLVED' && canResolve && (
          <button className="urgent-resolve-button" type="button" disabled={busyId === request.id} onClick={() => void runAction(request, 'RESOLVE')}>
            Resolve
          </button>
        )}
      </li>
    );
  }

  return (
    <section className="urgent-card" aria-labelledby="urgent-title">
      <div className="urgent-heading">
        <div>
          <p className="eyebrow">WHEN SOMETHING NEEDS ATTENTION</p>
          <h2 id="urgent-title">Urgent requests</h2>
          <p className="muted">A clear handoff for something that needs a quick response.</p>
        </div>
        <span className="urgent-icon" aria-hidden="true">!</span>
      </div>

      {error && <p className="form-error urgent-error" role="alert">{error}</p>}
      {role && (
        <div className="push-settings">
          <div>
            <strong>Browser alerts</strong>
            <span>
              {pushDeliveryEnabled
                ? pushSubscription ? 'This browser is registered for urgent alerts.' : 'Get an alert when a new request is sent.'
                : 'Delivery provider is not configured; development deliveries are simulated.'}
            </span>
          </div>
          {pushPublicKey && pushDeliveryEnabled ? (
            <button type="button" className="push-settings-button" disabled={pushBusy} onClick={() => void toggleBrowserNotifications()}>
              {pushBusy ? 'Updating…' : pushSubscription ? 'Turn off alerts' : 'Turn on alerts'}
            </button>
          ) : (
            <span className="push-unavailable">Delivery provider not configured</span>
          )}
          {pushError && <p className="form-error push-error" role="alert">{pushError}</p>}
        </div>
      )}
      {role && canCreateUrgentRequest(role) && (
        <form className="urgent-form" onSubmit={(event) => void submitRequest(event)}>
          <label htmlFor="urgent-request-text">What needs attention?</label>
          <div className="urgent-form-row">
            <input
              id="urgent-request-text"
              value={text}
              maxLength={500}
              onChange={(event) => setText(event.target.value)}
              placeholder="e.g. Please pick up Dad’s prescription today"
              required
            />
            <button type="submit" disabled={saving || !text.trim()}>{saving ? 'Sending…' : 'Send request'}</button>
          </div>
        </form>
      )}

      {loading ? (
        <p className="urgent-empty" role="status">Loading urgent requests…</p>
      ) : requests.length === 0 ? (
        <div className="urgent-empty"><span aria-hidden="true">✓</span><p>No urgent requests. Everyone is in the loop.</p></div>
      ) : (
        <>
          <div className="urgent-section-heading"><h3>Needs attention</h3><span>{activeRequests.length}</span></div>
          {activeRequests.length > 0 ? (
            <ul className="urgent-list">{activeRequests.map(renderRequest)}</ul>
          ) : (
            <p className="urgent-no-active">Nothing waiting for a response.</p>
          )}
          {resolvedRequests.length > 0 && (
            <details className="urgent-resolved">
              <summary>Resolved requests <span>{resolvedRequests.length}</span></summary>
              <ul className="urgent-list">{resolvedRequests.map(renderRequest)}</ul>
            </details>
          )}
        </>
      )}
      {role === 'VIEWER' && <p className="member-privacy-note">You can view and acknowledge requests, but cannot take ownership or resolve them.</p>}
    </section>
  );
}

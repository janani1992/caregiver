import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import UrgentRequestsPanel from './UrgentRequestsPanel';
import * as membersApi from '../../api/members';
import * as urgentApi from '../../api/urgent';
import * as pushApi from '../../api/push';

vi.mock('../../api/members', () => ({ getCircleMembers: vi.fn() }));
vi.mock('../../api/session', () => ({ currentUserId: 'fc700cd8-d169-4104-a655-581bcdde920d' }));
vi.mock('../../api/push', () => ({
  disableBrowserNotifications: vi.fn(),
  enableBrowserNotifications: vi.fn(),
  getCurrentBrowserPushSubscription: vi.fn(),
  getPushConfiguration: vi.fn(),
}));
vi.mock('../../api/urgent', () => ({
  acknowledgeUrgentRequest: vi.fn(),
  canCreateUrgentRequest: (role: string) => role === 'OWNER' || role === 'FAMILY',
  canResolveUrgentRequest: (role: string, request: { sender: string; handledBy: string | null }, actorId: string) =>
    role === 'OWNER' || role === 'FAMILY' || request.sender === actorId || request.handledBy === actorId,
  canTakeUrgentRequest: (role: string) => role !== 'VIEWER',
  createUrgentRequest: vi.fn(),
  getUrgentRequests: vi.fn(),
  resolveUrgentRequest: vi.fn(),
}));

const ownerId = '087a4d5a-9a7b-4ab1-b64f-7c7b00b3e104';
const helperId = 'fc700cd8-d169-4104-a655-581bcdde920d';
const request = {
  id: 'request-1',
  circleId: 'circle-1',
  itemId: null,
  text: 'Please bring Dad his prescription',
  state: 'OPEN' as const,
  sender: 'family-1',
  senderName: 'Rina',
  createdAt: '2026-11-09T10:00:00Z',
  handledBy: null,
  handledByName: null,
  handledAt: null,
  resolvedBy: null,
  resolvedByName: null,
  resolvedAt: null,
  version: 1,
  seenByMe: false,
};

function mockRole(role: 'OWNER' | 'FAMILY' | 'HELPER' | 'VIEWER') {
  vi.mocked(membersApi.getCircleMembers).mockResolvedValue({
    circleId: 'circle-1',
    circleName: 'The Tan family',
    currentUserRole: role,
    members: [],
  });
}

describe('UrgentRequestsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(urgentApi.getUrgentRequests).mockResolvedValue({ circleId: 'circle-1', requests: [request] });
    vi.mocked(pushApi.getPushConfiguration).mockResolvedValue({ publicKey: null, deliveryEnabled: false });
    vi.mocked(pushApi.getCurrentBrowserPushSubscription).mockResolvedValue(null);
  });

  it('lets helpers mark requests seen and take them on, without create or resolve controls', async () => {
    mockRole('HELPER');
    render(<UrgentRequestsPanel circleId="circle-1" />);

    expect(await screen.findByText(request.text)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mark seen' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'I’ll take it' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send request' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Resolve' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'I’ll take it' }));
    await waitFor(() => expect(urgentApi.acknowledgeUrgentRequest).toHaveBeenCalledWith(request.id, 'ON_IT'));
  });

  it('allows viewers to mark requests seen but not take ownership or resolve', async () => {
    mockRole('VIEWER');
    render(<UrgentRequestsPanel circleId="circle-1" />);

    expect(await screen.findByRole('button', { name: 'Mark seen' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'I’ll take it' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Resolve' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send request' })).not.toBeInTheDocument();
  });

  it('allows owner/family members to create requests and resolve them', async () => {
    mockRole('OWNER');
    vi.mocked(urgentApi.createUrgentRequest).mockResolvedValue(request);
    vi.mocked(urgentApi.resolveUrgentRequest).mockResolvedValue({ ...request, state: 'RESOLVED', resolvedBy: ownerId });
    render(<UrgentRequestsPanel circleId="circle-1" />);

    const input = await screen.findByLabelText('What needs attention?');
    fireEvent.change(input, { target: { value: 'Please check the gate' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send request' }));
    await waitFor(() => expect(urgentApi.createUrgentRequest).toHaveBeenCalledWith('circle-1', 'Please check the gate'));

    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }));
    await waitFor(() => expect(urgentApi.resolveUrgentRequest).toHaveBeenCalledWith(request.id));
  });

  it('lets the assigned helper resolve their request', async () => {
    mockRole('HELPER');
    const claimed = { ...request, state: 'ON_IT' as const, handledBy: helperId, handledByName: 'Siti' };
    vi.mocked(urgentApi.getUrgentRequests).mockResolvedValue({ circleId: 'circle-1', requests: [claimed] });
    render(<UrgentRequestsPanel circleId="circle-1" />);

    fireEvent.click(await screen.findByRole('button', { name: 'Resolve' }));
    await waitFor(() => expect(urgentApi.resolveUrgentRequest).toHaveBeenCalledWith(request.id));
  });

  it('only requests browser notification permission after the user opts in', async () => {
    mockRole('HELPER');
    vi.mocked(pushApi.getPushConfiguration).mockResolvedValue({ publicKey: 'configured-public-key', deliveryEnabled: true });
    vi.mocked(pushApi.enableBrowserNotifications).mockResolvedValue({
      id: 'subscription-1',
      endpoint: 'https://push.example.test/device',
      createdAt: '2026-11-09T10:00:00Z',
    });
    render(<UrgentRequestsPanel circleId="circle-1" />);

    expect(await screen.findByRole('button', { name: 'Turn on alerts' })).toBeInTheDocument();
    expect(pushApi.enableBrowserNotifications).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Turn on alerts' }));
    await waitFor(() => expect(pushApi.enableBrowserNotifications).toHaveBeenCalledWith('configured-public-key'));
    expect(await screen.findByRole('button', { name: 'Turn off alerts' })).toBeInTheDocument();
  });
});

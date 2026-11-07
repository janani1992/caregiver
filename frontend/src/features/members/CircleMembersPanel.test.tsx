import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import CircleMembersPanel from './CircleMembersPanel';
import * as membersApi from '../../api/members';

vi.mock('../../api/members', () => ({
  getCircleMembers: vi.fn(),
  removeCircleMember: vi.fn(),
}));

const ownerId = '087a4d5a-9a7b-4ab1-b64f-7c7b00b3e104';
const familyId = 'f18d2089-f2ca-412e-a129-d034b3841554';
const helperId = 'fc700cd8-d169-4104-a655-581bcdde920d';

const ownerRoster = {
  circleId: 'circle-1',
  circleName: 'The Tan family',
  currentUserRole: 'OWNER' as const,
  members: [
    { userId: ownerId, displayName: 'Alex Tan', role: 'OWNER' as const, joinedAt: '2026-11-01T10:00:00Z' },
    { userId: familyId, displayName: 'Rina Tan', role: 'FAMILY' as const, joinedAt: '2026-11-02T10:00:00Z' },
    { userId: helperId, displayName: 'Siti', role: 'HELPER' as const, joinedAt: '2026-11-03T10:00:00Z' },
  ],
};

describe('CircleMembersPanel', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows active circle members and does not render private contact fields', async () => {
    vi.mocked(membersApi.getCircleMembers).mockResolvedValue(ownerRoster);
    render(<CircleMembersPanel circleId="circle-1" />);

    expect(await screen.findByRole('heading', { name: 'The Tan family' })).toBeInTheDocument();
    expect(screen.getByText('Rina Tan')).toBeInTheDocument();
    expect(screen.getByText(/Helper · Joined/)).toBeInTheDocument();
    expect(screen.queryByText(/phone|email/i)).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Remove' })).toHaveLength(2);
  });

  it('confirms owner removal then refreshes the roster', async () => {
    vi.mocked(membersApi.getCircleMembers)
      .mockResolvedValueOnce(ownerRoster)
      .mockResolvedValueOnce({ ...ownerRoster, members: ownerRoster.members.filter((member) => member.userId !== helperId) });
    vi.mocked(membersApi.removeCircleMember).mockResolvedValue(undefined);
    render(<CircleMembersPanel circleId="circle-1" />);
    await screen.findByText('Siti');

    const helperRow = screen.getByText('Siti').closest('li');
    if (!helperRow) throw new Error('Expected helper member row');
    fireEvent.click(within(helperRow).getByRole('button', { name: 'Remove' }));
    expect(within(helperRow).getByText('Remove?')).toBeInTheDocument();
    fireEvent.click(within(helperRow).getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(membersApi.removeCircleMember).toHaveBeenCalledWith('circle-1', helperId, expect.any(String)));
    await waitFor(() => expect(screen.queryByText('Siti')).not.toBeInTheDocument());
  });

  it('does not expose removal controls to a family member', async () => {
    vi.mocked(membersApi.getCircleMembers).mockResolvedValue({ ...ownerRoster, currentUserRole: 'FAMILY' });
    render(<CircleMembersPanel circleId="circle-1" />);
    await screen.findByRole('heading', { name: 'The Tan family' });
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument();
    expect(screen.queryByText('Invite someone in')).not.toBeInTheDocument();
  });
});

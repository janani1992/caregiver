import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import CareLogPanel from './CareLogPanel';
import * as membersApi from '../../api/members';
import * as careLogApi from '../../api/careLog';

vi.mock('../../api/members', () => ({ getCircleMembers: vi.fn() }));
vi.mock('../../api/careLog', () => ({
  addCareLogEntry: vi.fn(),
  canWriteCareLog: (role: string) => role !== 'VIEWER',
  getCareLogEntries: vi.fn(),
}));

const entry = {
  id: 'entry-1',
  circleId: 'circle-1',
  text: 'Medicine taken after breakfast',
  category: 'MEDICATION' as const,
  authorId: 'helper-1',
  authorName: 'Siti',
  createdAt: '2026-11-14T08:30:00Z',
};

function mockRole(role: 'OWNER' | 'FAMILY' | 'HELPER' | 'VIEWER') {
  vi.mocked(membersApi.getCircleMembers).mockResolvedValue({
    circleId: 'circle-1',
    circleName: 'The Tan family',
    currentUserRole: role,
    members: [],
  });
}

describe('CareLogPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(careLogApi.getCareLogEntries).mockResolvedValue({ circleId: 'circle-1', entries: [entry], nextCursor: null });
  });

  it('shows entries and lets an active helper add a categorized update', async () => {
    mockRole('HELPER');
    vi.mocked(careLogApi.addCareLogEntry).mockResolvedValue(entry);
    render(<CareLogPanel circleId="circle-1" />);

    expect(await screen.findByText(entry.text)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Add a care update'), { target: { value: 'Dad had a good lunch' } });
    fireEvent.change(screen.getByLabelText('Update category'), { target: { value: 'WELLBEING' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add update' }));

    await waitFor(() => expect(careLogApi.addCareLogEntry).toHaveBeenCalledWith('circle-1', {
      id: expect.any(String),
      text: 'Dad had a good lunch',
      category: 'WELLBEING',
    }));
  });

  it('allows viewers to read entries but hides the add form', async () => {
    mockRole('VIEWER');
    render(<CareLogPanel circleId="circle-1" />);

    expect(await screen.findByText(entry.text)).toBeInTheDocument();
    expect(screen.queryByLabelText('Add a care update')).not.toBeInTheDocument();
    expect(screen.getByText('You can read the care log, but cannot add updates.')).toBeInTheDocument();
  });

  it('loads older entries using the response cursor', async () => {
    mockRole('FAMILY');
    vi.mocked(careLogApi.getCareLogEntries)
      .mockResolvedValueOnce({ circleId: 'circle-1', entries: [entry], nextCursor: 'cursor-1' })
      .mockResolvedValueOnce({ circleId: 'circle-1', entries: [{ ...entry, id: 'entry-2', text: 'Older update' }], nextCursor: null });
    render(<CareLogPanel circleId="circle-1" />);

    fireEvent.click(await screen.findByRole('button', { name: 'Load older updates' }));
    expect(await screen.findByText('Older update')).toBeInTheDocument();
    expect(careLogApi.getCareLogEntries).toHaveBeenLastCalledWith('circle-1', 'cursor-1');
  });
});
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import InviteMembersPanel from './InviteMembersPanel';
import AcceptInvitePage from './AcceptInvitePage';
import * as invitesApi from '../../api/invites';

vi.mock('../../api/invites', () => ({
  createInvite: vi.fn(),
  getInvitePreview: vi.fn(),
  acceptInvite: vi.fn(),
}));

function renderAcceptPage() {
  return render(
    <MemoryRouter initialEntries={['/invites/secure-token']}>
      <Routes>
        <Route path="/invites/:token" element={<AcceptInvitePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('invite flow', () => {
  beforeEach(() => vi.clearAllMocks());

  it('creates a link using a secure token and copies the share URL', async () => {
    vi.mocked(invitesApi.createInvite).mockResolvedValue({
      id: 'invite-1',
      circleId: 'circle-1',
      role: 'FAMILY',
      inviteUrl: 'http://localhost:5173/invites/abc',
      expiresAt: '2026-11-13T00:00:00Z',
    });
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });

    render(<MemoryRouter><InviteMembersPanel circleId="circle-1" /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: /create invite link/i }));

    const linkInput = await screen.findByRole('textbox', { name: 'Invite link' });
    expect(linkInput).toHaveValue('http://localhost:5173/invites/abc');
    const [, role, id, token, key] = vi.mocked(invitesApi.createInvite).mock.calls[0];
    expect(role).toBe('FAMILY');
    expect(id).toEqual(expect.any(String));
    expect(token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(key).toEqual(expect.any(String));

    fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('http://localhost:5173/invites/abc'));
    expect(screen.getByRole('button', { name: 'Copied' })).toBeInTheDocument();
  });

  it('previews the invited role and accepts the invite', async () => {
    vi.mocked(invitesApi.getInvitePreview).mockResolvedValue({
      circleId: 'circle-2',
      circleName: 'The Tan family',
      role: 'HELPER',
      expiresAt: '2026-11-13T00:00:00Z',
    });
    vi.mocked(invitesApi.acceptInvite).mockResolvedValue({
      circleId: 'circle-2',
      circleName: 'The Tan family',
      role: 'HELPER',
      acceptedAt: '2026-11-06T00:00:00Z',
      alreadyAccepted: false,
    });

    renderAcceptPage();
    expect(await screen.findByRole('heading', { name: 'Join The Tan family.' })).toBeInTheDocument();
    expect(screen.getByText(/care helper/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Your name'), { target: { value: 'Rina' } });
    fireEvent.click(screen.getByRole('button', { name: /accept invite/i }));

    expect(await screen.findByRole('heading', { name: 'Welcome to the circle.' })).toBeInTheDocument();
    expect(invitesApi.acceptInvite).toHaveBeenCalledWith('secure-token', 'Rina', expect.any(String));
  });

  it('shows an unavailable state if invite preview fails', async () => {
    vi.mocked(invitesApi.getInvitePreview).mockRejectedValue(new Error('This invite has expired'));
    renderAcceptPage();
    expect(await screen.findByRole('heading', { name: 'This link can’t be used.' })).toBeInTheDocument();
    expect(screen.getByText('This invite has expired')).toBeInTheDocument();
    expect(invitesApi.acceptInvite).not.toHaveBeenCalled();
  });
});

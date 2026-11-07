import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import CreateCirclePage from './CreateCirclePage';
import * as circlesApi from '../../api/circles';

vi.mock('../../api/circles', () => ({ createCircle: vi.fn() }));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/circles/new']}>
      <Routes>
        <Route path="/circles/new" element={<CreateCirclePage />} />
        <Route path="/circles/:circleId" element={<p>Circle created</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CreateCirclePage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('requires a circle name', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: /create circle/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Add your name');
    fireEvent.change(screen.getByLabelText('Your name'), { target: { value: 'Alex Tan' } });
    fireEvent.click(screen.getByRole('button', { name: /create circle/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Add a name for your circle');
    expect(circlesApi.createCircle).not.toHaveBeenCalled();
  });

  it('creates a circle and navigates to it', async () => {
    vi.mocked(circlesApi.createCircle).mockResolvedValue({
      id: 'circle-123',
      name: 'The Tan family',
      timezone: 'Asia/Singapore',
      ownerUserId: 'owner-1',
      role: 'OWNER',
      changeSeq: 0,
    });
    renderPage();
    fireEvent.change(screen.getByLabelText('Your name'), { target: { value: 'Alex Tan' } });
    fireEvent.change(screen.getByLabelText('What should we call it?'), { target: { value: 'The Tan family' } });
    fireEvent.click(screen.getByRole('button', { name: /create circle/i }));

    expect(await screen.findByText('Circle created')).toBeInTheDocument();
    expect(circlesApi.createCircle).toHaveBeenCalledWith(
      expect.objectContaining({ displayName: 'Alex Tan', name: 'The Tan family', timezone: 'Asia/Singapore' }),
      expect.any(String),
    );
  });

  it('shows API errors and permits retry with the same idempotency key', async () => {
    vi.mocked(circlesApi.createCircle)
      .mockRejectedValueOnce(new Error('Service unavailable'))
      .mockResolvedValueOnce({
        id: 'circle-456',
        name: 'Family',
        timezone: 'Asia/Singapore',
        ownerUserId: 'owner-1',
        role: 'OWNER',
        changeSeq: 0,
      });
    renderPage();
      fireEvent.change(screen.getByLabelText('Your name'), { target: { value: 'Alex Tan' } });
    fireEvent.change(screen.getByLabelText('What should we call it?'), { target: { value: 'Family' } });
    fireEvent.click(screen.getByRole('button', { name: /create circle/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Service unavailable');
    const firstKey = vi.mocked(circlesApi.createCircle).mock.calls[0][1];

    fireEvent.click(screen.getByRole('button', { name: /create circle/i }));
    await waitFor(() => expect(screen.getByText('Circle created')).toBeInTheDocument());
    expect(vi.mocked(circlesApi.createCircle).mock.calls[1][1]).toBe(firstKey);
  });
});

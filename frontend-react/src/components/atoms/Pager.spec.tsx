import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Pager } from './Pager.tsx';

describe('Pager', () => {
  it('renders nothing on a single short page', () => {
    const { container } = render(<Pager offset={0} limit={200} count={63} onOffset={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('offers Older when the page is full and steps the offset by one page', async () => {
    const onOffset = vi.fn();
    render(<Pager offset={0} limit={200} count={200} onOffset={onOffset} />);
    expect(screen.getByText('rows 1–200')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '← Newer' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Older →' }));
    expect(onOffset).toHaveBeenCalledWith(200);
  });

  it('steps back with Newer and never goes below zero', async () => {
    const onOffset = vi.fn();
    render(<Pager offset={200} limit={200} count={41} onOffset={onOffset} />);
    expect(screen.getByText('rows 201–241')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Older →' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: '← Newer' }));
    expect(onOffset).toHaveBeenCalledWith(0);
  });
});

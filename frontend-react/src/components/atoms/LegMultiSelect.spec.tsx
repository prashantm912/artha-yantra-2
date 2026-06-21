import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { LegMultiSelect } from './LegMultiSelect.tsx';

describe('LegMultiSelect', () => {
  it('exposes aria state, opens on click, toggles a leg, and filters by search', () => {
    const onToggle = vi.fn();
    render(
      <LegMultiSelect
        options={['57200 CE', '57200 PE', '57100 CE']}
        selected={['57200 CE']}
        onToggle={onToggle}
      />,
    );
    const trigger = screen.getByRole('button', { name: /1 selected/ });
    expect(trigger).toHaveAttribute('aria-haspopup', 'true');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');

    // the selected leg is checked; toggling another fires onToggle with its value.
    expect(screen.getByRole('checkbox', { name: '57200 CE' })).toBeChecked();
    fireEvent.click(screen.getByRole('checkbox', { name: '57200 PE' }));
    expect(onToggle).toHaveBeenCalledWith('57200 PE');

    // the search box filters the option rows.
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search strike' }), {
      target: { value: '57100' },
    });
    expect(screen.queryByRole('checkbox', { name: '57200 CE' })).toBeNull();
    expect(screen.getByRole('checkbox', { name: '57100 CE' })).toBeInTheDocument();
  });

  it('shows the placeholder label when nothing is selected', () => {
    render(<LegMultiSelect options={['57200 CE']} selected={[]} onToggle={vi.fn()} />);
    expect(screen.getByRole('button', { name: /Select strikes/ })).toBeInTheDocument();
  });
});

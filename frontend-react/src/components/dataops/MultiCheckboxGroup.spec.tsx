import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MultiCheckboxGroup } from './MultiCheckboxGroup.tsx';

describe('MultiCheckboxGroup', () => {
  const options = [
    { value: 'NIFTY', label: 'NIFTY' },
    { value: 'SENSEX', label: 'SENSEX' },
  ];

  it('reflects the selected set and toggles via onChange (add then remove)', () => {
    const onChange = vi.fn();
    render(
      <MultiCheckboxGroup
        legend="Indices"
        options={options}
        selected={['NIFTY']}
        onChange={onChange}
      />,
    );
    const boxes = screen.getAllByRole('checkbox') as HTMLInputElement[];
    expect(boxes[0].checked).toBe(true);
    expect(boxes[1].checked).toBe(false);

    fireEvent.click(boxes[1]); // add SENSEX
    expect(onChange).toHaveBeenCalledWith(['NIFTY', 'SENSEX']);

    fireEvent.click(boxes[0]); // remove NIFTY
    expect(onChange).toHaveBeenCalledWith([]);
  });
});

import { cn } from '../../lib/cn.ts';

/**
 * Presentational numbered-step indicator bar.
 * `current` is 0-based: steps before it are done (✓), the current step and
 * done steps render with the accent, future steps render muted.
 */
export function Stepper(props: { steps: string[]; current: number }) {
  const { steps, current } = props;

  return (
    <ol
      aria-label="Progress"
      className="flex items-start gap-0 overflow-x-auto"
    >
      {steps.map((label, index) => {
        const isDone = index < current;
        const isCurrent = index === current;
        const isActive = isDone || isCurrent;
        const isLast = index === steps.length - 1;

        return (
          <li
            key={label}
            aria-current={isCurrent ? 'step' : undefined}
            className="flex min-w-0 flex-1 items-start"
          >
            <div className="flex min-w-0 flex-col items-center">
              <span
                className={cn(
                  'flex h-8 w-8 shrink-0 items-center justify-center rounded-full border text-sm font-semibold',
                  isActive
                    ? 'border-accent bg-accent text-surface-0'
                    : 'border-ay-border bg-surface-1 text-ay-muted',
                )}
              >
                {isDone ? '✓' : index + 1}
              </span>
              <span
                className={cn(
                  'mt-1 max-w-24 text-center text-xs',
                  isActive ? 'text-ay-text' : 'text-ay-muted',
                )}
              >
                {label}
              </span>
            </div>
            {!isLast && (
              <span
                aria-hidden="true"
                className={cn(
                  'mt-4 h-px min-w-6 flex-1',
                  isDone ? 'bg-accent' : 'bg-ay-border',
                )}
              />
            )}
          </li>
        );
      })}
    </ol>
  );
}

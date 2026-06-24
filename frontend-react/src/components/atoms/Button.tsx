import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { Slot } from 'radix-ui';
import { cva, type VariantProps } from 'class-variance-authority';
import { Loader2, type LucideIcon } from 'lucide-react';
import { cn } from '../../lib/cn.ts';

// The app Button atom (revamp §3.1) — built on cva + the radix Slot, themed ONLY via --ay-*
// (NOT shadcn's default tokens). icon-only variant type-REQUIRES ariaLabel; loading keeps the
// text mounted (stable accessible name) and shows a spinner. Focus comes from the global
// :focus-visible rule (index.css §1.4).
const buttonVariants = cva(
  'inline-flex items-center justify-center gap-1.5 rounded-md font-medium ' +
    'transition-[opacity,background-color,border-color] duration-[var(--duration-fast)] ease-[var(--ease-standard)] ' +
    'disabled:opacity-50 disabled:pointer-events-none',
  {
    variants: {
      variant: {
        primary: 'bg-accent text-surface-0 hover:opacity-90',
        secondary: 'bg-surface-2 text-ay-text hover:bg-surface-1',
        outline: 'border border-ay-border bg-surface-1 text-ay-text hover:border-accent',
        ghost: 'text-accent hover:bg-surface-2',
        danger: 'bg-bear text-surface-0 hover:opacity-90',
        icon: 'border border-ay-border bg-surface-1 text-ay-text hover:border-accent px-0',
      },
      size: { sm: 'h-8 px-2 text-body-sm', md: 'h-9 px-4 text-body-sm' },
    },
    compoundVariants: [
      { variant: 'icon', size: 'sm', class: 'size-8 px-0' },
      { variant: 'icon', size: 'md', class: 'size-9 px-0' },
    ],
    defaultVariants: { variant: 'primary', size: 'md' },
  },
);

type Variant = NonNullable<VariantProps<typeof buttonVariants>['variant']>;

// icon-only buttons REQUIRE ariaLabel (axe + role/name); text buttons take their name from children.
type AriaName =
  | { variant: 'icon'; ariaLabel: string }
  | { variant?: Exclude<Variant, 'icon'>; ariaLabel?: never };

type BaseProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'aria-label'> &
  VariantProps<typeof buttonVariants> & {
    loading?: boolean;
    icon?: LucideIcon;
    asChild?: boolean;
  };

export type ButtonProps = BaseProps & AriaName;

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { className, variant, size, loading, disabled, icon: Icon, asChild, ariaLabel, children, ...rest },
  ref,
) {
  const Comp = asChild ? Slot.Root : 'button';
  return (
    <Comp
      ref={ref}
      type={asChild ? undefined : (rest.type ?? 'button')}
      aria-label={ariaLabel}
      aria-busy={loading || undefined}
      disabled={disabled || loading}
      className={cn(buttonVariants({ variant, size }), className)}
      {...rest}
    >
      {loading ? (
        <Loader2 aria-hidden className="size-4 animate-spin motion-reduce:animate-none" />
      ) : (
        Icon && <Icon aria-hidden className="size-4" />
      )}
      {children}
    </Comp>
  );
});

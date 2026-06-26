import { RefreshCw } from 'lucide-react';
import { Button } from './Button.tsx';

// Explicit fetch trigger (oipulse §20.7.3): the chain is reactive (queries refire on context change),
// so Go just forces a refetch. Wraps the Button atom — keeps the accessible name "Go" and shows a
// spinner while loading (text stays mounted, so getByRole('button',{name:'Go'}) is stable).
interface GoButtonProps {
  onClick: () => void;
  loading?: boolean;
  /** Native hover tooltip (#16); defaults to a generic refresh hint. */
  title?: string;
}

export function GoButton({ onClick, loading, title }: GoButtonProps) {
  return (
    <Button
      variant="primary"
      size="md"
      icon={RefreshCw}
      loading={loading}
      onClick={onClick}
      title={title ?? 'Fetch the latest data for the current selection'}
    >
      Go
    </Button>
  );
}

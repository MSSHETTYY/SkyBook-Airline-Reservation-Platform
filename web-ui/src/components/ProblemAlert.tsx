import { Alert, AlertTitle, Button } from '@mui/material';

import type { ApiProblem } from '../api/problem';

interface Props {
  problem: ApiProblem | null | undefined;
  onRetry?: () => void;
}

/** Shows an API problem. MUI gives Alert role="alert", so it is announced as soon as it appears. */
export function ProblemAlert({ problem, onRetry }: Props) {
  if (!problem) return null;
  return (
    <Alert
      severity="error"
      sx={{ my: 2 }}
      action={
        onRetry && (
          <Button color="inherit" size="small" onClick={onRetry}>
            Try again
          </Button>
        )
      }
    >
      <AlertTitle>{problem.title}</AlertTitle>
      {problem.detail}
      {problem.correlationId && (
        <div>
          <small>Reference for support: {problem.correlationId}</small>
        </div>
      )}
    </Alert>
  );
}

import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import Container from '@mui/material/Container';
import Card from '@mui/material/Card';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import TextField from '@mui/material/TextField';
import Button from '@mui/material/Button';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';

import { SEOHead } from '../../components/common/SEOHead';
import { useAuth } from '../../hooks/useAuth';
import { ApiError } from '../../api/client';

export function AdminLogin() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const requested = (location.state as { from?: string } | null)?.from;

  // Never send anyone back to the change-password screen.
  //
  // Changing a password signs the session out while that screen is still
  // mounted, so the admin guard records it as the page the user was trying to
  // reach. Honouring that puts them back on the change form immediately after
  // they succeeded at it - with the fields empty and nothing explaining why.
  // Whether another change is owed is the server's call, handled below.
  const redirectTo =
    requested && !requested.startsWith('/admin/change-password') ? requested : '/admin';

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const signedIn = await signIn(email, password);

      if (!signedIn.roles.includes('ROLE_ADMIN')) {
        // a customer signing in here has a valid session, just not for this
        // area; say so plainly rather than looping them back to the form
        setError('That account does not have access to the back office.');
        return;
      }
      navigate(signedIn.mustChangePassword ? '/admin/change-password' : redirectTo, {
        replace: true,
      });
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError('That email address and password do not match.');
      } else if (err instanceof ApiError && err.status === 429) {
        setError('Too many attempts. Please wait a few minutes and try again.');
      } else {
        setError('We could not sign you in just now. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Box sx={{ minHeight: '100dvh', display: 'grid', placeItems: 'center', bgcolor: 'background.default' }}>
      <Container maxWidth="xs">
        <SEOHead title="Back office" path="/admin/login" noIndex />

        <Stack sx={{ alignItems: 'center', mb: 3 }}>
          <Typography
            sx={{ fontFamily: '"Fraunces", Georgia, serif', fontSize: '1.9rem', fontWeight: 600 }}
          >
            Rainbow Books
          </Typography>
          <Typography
            sx={{
              fontSize: 11,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
              color: 'text.secondary',
            }}
          >
            Back office
          </Typography>
        </Stack>

        <Card sx={{ p: 3 }}>
          <Box component="form" onSubmit={handleSubmit} noValidate>
            <Stack spacing={2.5}>
              {error && <Alert severity="error">{error}</Alert>}

              <TextField
                label="Email address"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="username"
                required
                fullWidth
                autoFocus
              />

              <TextField
                label="Password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                required
                fullWidth
              />

              <Button type="submit" variant="contained" size="large" disabled={submitting}>
                {submitting ? 'Signing in…' : 'Sign in'}
              </Button>
            </Stack>
          </Box>
        </Card>
      </Container>
    </Box>
  );
}

import { createTheme } from '@mui/material/styles';

/**
 * Colours chosen for WCAG 2.1 AA: primary #0b4f8a on white is 8.6:1, secondary #a1124f is 7.4:1,
 * so text and icons in these colours pass the 4.5:1 minimum. Focus is always visible (2.4.7).
 */
export const theme = createTheme({
  palette: {
    primary: { main: '#0b4f8a' },
    secondary: { main: '#a1124f' },
    success: { main: '#1b6e34' },
    warning: { main: '#8a4b00' },
    info: { main: '#01579b' },
    background: { default: '#f5f7fa' },
  },
  shape: { borderRadius: 10 },
  typography: {
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
    h1: { fontSize: '2rem', fontWeight: 700 },
    h2: { fontSize: '1.4rem', fontWeight: 600 },
    h3: { fontSize: '1.15rem', fontWeight: 600 },
  },
  components: {
    MuiButton: { defaultProps: { disableElevation: true }, styleOverrides: { root: { textTransform: 'none' } } },
    MuiCssBaseline: {
      styleOverrides: {
        '*:focus-visible': { outline: '3px solid #f2a900', outlineOffset: '2px' },
        '.visually-hidden': {
          position: 'absolute',
          width: 1,
          height: 1,
          padding: 0,
          margin: -1,
          overflow: 'hidden',
          clip: 'rect(0 0 0 0)',
          whiteSpace: 'nowrap',
          border: 0,
        },
        '@media (prefers-reduced-motion: reduce)': {
          '*, *::before, *::after': {
            animationDuration: '0.01ms !important',
            transitionDuration: '0.01ms !important',
          },
        },
      },
    },
  },
});

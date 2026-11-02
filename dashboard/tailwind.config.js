const v = (name) => `rgb(var(${name}) / <alpha-value>)`

// Every colour is a CSS variable (see index.css) so the whole console switches between light and dark without touching a component.
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"DM Sans Variable"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        display: ['"Sora Variable"', '"DM Sans Variable"', 'ui-sans-serif', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'monospace'],
      },
      boxShadow: { card: 'var(--shadow)', lift: 'var(--shadow-lift)' },
      colors: {
        ink: v('--bg'),
        panel: v('--panel'),
        panel2: v('--panel2'),
        line: v('--line'),
        accent: v('--accent'),
        gold: v('--gold'),
        slate: { 100: v('--fg1'), 200: v('--fg2'), 300: v('--fg3'), 400: v('--fg4'), 500: v('--fg5') },
        emerald: { 400: v('--good') },
        red: { 400: v('--bad'), 500: v('--bad'), 600: v('--bad') },
        amber: { 300: v('--warn'), 400: v('--warn') },
      },
    },
  },
  plugins: [],
}

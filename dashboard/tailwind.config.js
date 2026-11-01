export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Inter Tight Variable"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        display: ['"Instrument Serif"', 'Georgia', 'serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'monospace'],
      },
      colors: {
        ink: '#0a0b0d',
        panel: '#101215',
        panel2: '#14171b',
        line: '#1d2127',
        accent: '#c9a45c',
        // remap the semantic shades the pages use to the house palette
        emerald: { 400: '#86b89a' },
        red: { 400: '#cf6f63', 500: '#b9584d', 600: '#a84c42' },
        amber: { 300: '#e0b873', 400: '#d7a65a' },
        slate: { 100: '#f1ede4', 200: '#e8e4dc', 300: '#c4c0b7', 400: '#8b8f98', 500: '#666a73' },
      },
    },
  },
  plugins: [],
}

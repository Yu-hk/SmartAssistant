/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        background: 'var(--nova-bg-page)',
        foreground: 'var(--nova-text-primary)',
        muted: {
          DEFAULT: 'var(--nova-bg-component)',
          foreground: 'var(--nova-text-secondary)',
        },
        border: 'var(--nova-border)',
        input: 'var(--nova-bg-component)',
        card: {
          DEFAULT: 'var(--nova-bg-container)',
          foreground: 'var(--nova-text-primary)',
        },
        accent: {
          DEFAULT: 'var(--nova-accent)',
          foreground: 'var(--nova-text-anti)',
          light: 'var(--nova-accent-light)',
          glow: 'var(--nova-accent-glow)',
        },
        secondary: {
          DEFAULT: 'var(--nova-secondary)',
          hover: 'var(--nova-secondary-hover)',
        },
        primary: {
          DEFAULT: 'var(--nova-text-primary)',
          foreground: 'var(--nova-bg-page)',
        }
      },
      fontFamily: {
        sans: ['Inter', 'Noto Sans SC', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'SF Mono', 'monospace'],
      },
      borderRadius: {
        'xl': '16px',
        '2xl': '20px',
        '3xl': '24px',
      },
      boxShadow: {
        'glow': '0 0 24px var(--nova-accent-glow)',
        'glow-lg': '0 0 40px var(--nova-accent-glow)',
      },
      animation: {
        'cursor-blink': 'blink 1s infinite',
        'fade-in-up': 'fade-in-up 0.4s cubic-bezier(0.16, 1, 0.3, 1)',
        'scale-in': 'scale-in 0.3s cubic-bezier(0.16, 1, 0.3, 1)',
        'float': 'float 6s ease-in-out infinite',
        'breathe': 'breathe 3s ease-in-out infinite',
      },
      keyframes: {
        blink: {
          '0%, 50%': { opacity: '1' },
          '51%, 100%': { opacity: '0' },
        },
        'fade-in-up': {
          '0%': { opacity: '0', transform: 'translateY(16px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        'scale-in': {
          '0%': { opacity: '0', transform: 'scale(0.9)' },
          '100%': { opacity: '1', transform: 'scale(1)' },
        },
        float: {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%': { transform: 'translateY(-8px)' },
        },
        breathe: {
          '0%, 100%': { boxShadow: '0 0 12px var(--nova-accent-glow)' },
          '50%': { boxShadow: '0 0 24px var(--nova-accent-glow), 0 0 48px rgba(99, 102, 241, 0.1)' },
        },
      },
    },
  },
  plugins: [],
  corePlugins: {
    preflight: false,
  }
}

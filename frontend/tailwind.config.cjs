/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./index.html', './src/**/*.{vue,js}'],
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#f1faf6',
          100: '#ddf4ea',
          500: '#4fc9a0',
          600: '#1f8d6b',
          900: '#143f34'
        }
      }
    }
  },
  plugins: [],
  corePlugins: {
    preflight: false
  }
}

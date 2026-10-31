import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

/* global process */
// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      // override with PDLC_BACKEND=http://localhost:8081 to run a second backend alongside
      '/api': process.env.PDLC_BACKEND ?? 'http://localhost:8080',
    },
  },
})

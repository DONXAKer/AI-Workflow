import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  define: {
    global: 'globalThis',
  },
  server: {
    port: 5120,
    proxy: {
      '/api': 'http://localhost:8020',
      '/ws': { target: 'http://localhost:8020', ws: true }
    }
  }
})

import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Production build is emitted straight into the Spring Boot jar's static resources (single-container deploy).
export default defineConfig({
  plugins: [react()],
  build: { outDir: '../services/auction-engine/src/main/resources/static', emptyOutDir: true },
  server: { port: 5173, proxy: { '/api': 'http://localhost:8091', '/actuator': 'http://localhost:8091' } },
})

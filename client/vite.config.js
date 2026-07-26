import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    // Honor the PORT env var if set, otherwise default to 5173.
    port: process.env.PORT ? Number(process.env.PORT) : 5173,
  },
})

import { defineConfig } from 'vite';

// Backend port can be overridden: $env:API_PORT=8081; npm run dev
const apiPort = process.env.API_PORT || 8080;

export default defineConfig({
  server: {
    port: 5173,
    proxy: { '/api': { target: `http://127.0.0.1:${apiPort}`, changeOrigin: true } },
  },
});

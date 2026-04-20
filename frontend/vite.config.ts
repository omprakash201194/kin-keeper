import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'
import path from 'path'

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'apple-touch-icon.png'],
      manifest: {
        name: 'Kin-Keeper',
        short_name: 'Kin-Keeper',
        description: 'AI-powered family document vault',
        theme_color: '#0a0807',
        background_color: '#0a0807',
        display: 'standalone',
        start_url: '/',
        scope: '/',
        icons: [
          { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'any maskable' },
        ],
      },
      workbox: {
        // reason: don't cache /api/* — auth token freshness and live data matter more
        // than offline API access; we only precache the app shell.
        navigateFallbackDenylist: [/^\/api\//],
        // reason: when a new SW ships (e.g. the nginx MIME fix), skipWaiting lets it
        // activate immediately instead of waiting for every tab to be closed, and
        // clientsClaim has it take over existing pages. Without these, users who
        // installed an older broken version stay stuck on the old SW until they
        // manually close every tab.
        skipWaiting: true,
        clientsClaim: true,
        cleanupOutdatedCaches: true,
        runtimeCaching: [
          {
            urlPattern: /^\/api\/.*/i,
            handler: 'NetworkOnly',
          },
        ],
      },
    }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})

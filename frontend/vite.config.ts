import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'
import path from 'path'

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      // reason: flipped from generateSW to injectManifest so we can add a 'push'
      // event handler for reminder notifications. Workbox still precaches the
      // app shell via self.__WB_MANIFEST — we just own the shell.
      strategies: 'injectManifest',
      srcDir: 'src',
      filename: 'sw.ts',
      injectManifest: {
        // keep the usual app-shell precache; don't include Firestore etc.
        globPatterns: ['**/*.{js,css,html,svg,png,ico,webmanifest}'],
      },
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
        // reason: lets the installed PWA appear as a share destination on
        // Android/Chrome. When the user shares text (e.g. an SMS) from another
        // app, the browser sends a GET to /share with the fields as query
        // params. The /share route on the frontend reads them, stages a chat,
        // and navigates into it. File sharing via POST is deferred — it needs
        // a custom service worker to intercept multipart/form-data; see
        // ROADMAP.md.
        share_target: {
          action: '/share',
          method: 'GET',
          params: {
            title: 'title',
            text: 'text',
            url: 'url',
          },
        },
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
